package app.wyzecamrtsp

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Media3's RTSP player only speaks plain rtsp://, and Wyze cams only serve rtsps:// (RTSP over TLS)
 * with a login. This tiny proxy listens on 127.0.0.1 and, for each player connection:
 *  - opens a TLS connection to the camera,
 *  - rewrites URLs both ways (rtsp://127.0.0.1:N/... <-> rtsps://camera:322/...),
 *  - answers the camera's Digest/Basic login challenge itself, so the player never sees a 401,
 *  - passes the interleaved RTP data ('$' frames, RTP-over-TCP) straight through,
 *  - cleans up the camera's SDP so Media3 accepts it (see [Sdp]); if the camera leaves the
 *    H.264 SPS/PPS out of the SDP, [prepare] catches them from the live stream first,
 *  - sends RTSP TEARDOWN when the player goes away, so the camera frees the session
 *    (Wyze cams refuse new viewers once dead sessions pile up).
 */
class RtspsProxy(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val pass: String,
    private val keepAudio: Boolean = false,
) : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val auth = RtspAuth(user, pass)
    private val sessions = ConcurrentHashMap.newKeySet<Session>()
    @Volatile private var closed = false

    /** Set when the camera rejected the login even after we answered its challenge. */
    @Volatile var loginRejected = false
        private set
    @Volatile var lastError: String = ""
        private set

    private val localBase = "rtsp://127.0.0.1:${server.localPort}"
    private val remoteBase = "rtsps://$host:$port"
    private val remoteUrl = Regex("""rtsps?://(?:[^/@\s"]*@)?${Regex.escape(host)}(?::\d+)?""")

    fun url(path: String) = "$localBase/$path"

    private fun key(path: String) = "$host:$port/$path"

    /**
     * Run once before starting the player (on a background thread). Checks the stream with the
     * login and, if the camera's SDP has no H.264 SPS/PPS (Media3 can't start without them),
     * plays the stream for a moment to catch them from the video itself. The result is cached
     * per stream, so reconnects skip this.
     * Returns null when the stream is ready to play, or a short reason it can't.
     */
    fun prepare(path: String): String? {
        val key = key(path)
        if (key in ready) return null
        val uri = "$remoteBase/$path"
        return try {
            Tls.connect(host, port, 8000).use { s ->
                val c = Conn(s, RtspAuth(user, pass))
                val d = c.request("DESCRIBE", uri, "Accept" to "application/sdp")
                when (d.status) {
                    200 -> {}
                    401 -> { loginRejected = true; return "login rejected — check username/password" }
                    404 -> return "stream $path not found on the camera"
                    453, 503 -> return "camera busy (too many viewers?)"
                    else -> return "camera answered ${d.start.removePrefix("RTSP/1.0 ")}"
                }
                loginRejected = false
                val sdp = String(d.body, Charsets.ISO_8859_1)
                Log.i(TAG, "$host/$path SDP:\n$sdp")
                val t = Sdp.h264Track(sdp)
                if (t == null || Sdp.hasUsableSprop(t)) { ready += key; return null }

                // No SPS/PPS in the SDP: start the stream and take them from the video.
                // Every track is SET UP, not just the video: some firmware (SmolRTSP on Wyze
                // cams) answers PLAY with 200 but sends nothing until all tracks are set up.
                val base = d.header("Content-Base") ?: d.header("Content-Location") ?: uri
                var sid: String? = null
                for ((i, tr) in Sdp.tracks(sdp).withIndex()) {
                    val h = mutableListOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=${2 * i}-${2 * i + 1}")
                    sid?.let { h += "Session" to it }
                    val su = c.request("SETUP", trackUri(base, tr.control), *h.toTypedArray())
                    if (su.status != 200) {
                        if (tr.type != "video" || tr.payloadType != t.payloadType) continue   // extra tracks are optional
                        sid?.let { s -> runCatching { c.send("TEARDOWN", base, "Session" to s) } }
                        return "camera refused SETUP (${su.start.removePrefix("RTSP/1.0 ")})"
                    }
                    sid = sid ?: su.header("Session")?.substringBefore(';')?.trim()
                        ?: return "camera sent no session id"
                }
                if (sid == null) return "camera sent no session id"
                val p = c.request("PLAY", base, "Session" to sid, "Range" to "npt=0.000-")
                if (p.status != 200) {
                    runCatching { c.send("TEARDOWN", base, "Session" to sid) }
                    return "camera refused PLAY (${p.start.removePrefix("RTSP/1.0 ")})"
                }
                val (params, seen) = c.catchH264(t.payloadType, 15_000)
                runCatching { c.send("TEARDOWN", base, "Session" to sid) }
                Log.i(TAG, "$host/$path stream check: $seen")
                if (params == null) return "no SPS/PPS from camera ($seen)"
                Log.i(TAG, "$host/$path: SPS/PPS caught from the stream (${params.sprop})")
                spropCache[key] = params
                ready += key
                null
            }
        } catch (e: SocketTimeoutException) {
            "camera not responding"
        } catch (e: ConnectException) {
            "camera refused the connection"
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    init {
        thread(name = "proxy-accept-$host", isDaemon = true) {
            while (!closed) {
                val client = try { server.accept() } catch (e: Exception) { break }
                Session(client).also { sessions += it }.start()
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        // Players normally close their own socket (which triggers a polite TEARDOWN); force the
        // rest shut a little later so nothing leaks.
        val left = sessions.toList()
        if (left.isNotEmpty()) thread(isDaemon = true) {
            Thread.sleep(3000)
            left.forEach { it.shutdown() }
        }
    }

    private inner class Session(private val client: Socket) {
        private var upstream: Socket? = null
        private val upLock = Any()
        private val clientLock = Any()
        private val pending = ConcurrentHashMap<String, RtspMsg.Text>()
        private val retried = ConcurrentHashMap.newKeySet<String>()
        @Volatile private var sessionId: String? = null
        @Volatile private var lastUri: String? = null
        @Volatile private var tornDown = false
        @Volatile private var done = false

        /**
         * Tracks left out of the player's SDP (e.g. audio when it's off). Some firmware (SmolRTSP
         * on Wyze cams) sends no video unless every track is SET UP, so the proxy sets these up
         * itself before the player's PLAY and drops their responses and data.
         */
        @Volatile private var hiddenTracks: List<String> = emptyList()
        @Volatile private var hiddenSetUp = false
        private val hiddenChannels = ConcurrentHashMap.newKeySet<Int>()
        private val hiddenReplies = ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()

        fun start() = thread(name = "proxy-$host", isDaemon = true) {
            try {
                client.tcpNoDelay = true
                val up = Tls.connect(host, port, 8000)
                up.soTimeout = 0       // the player has its own timeouts and keep-alives
                upstream = up
                thread(name = "proxy-down-$host", isDaemon = true) { downstream(up) }
                upstreamLoop(up)
            } catch (e: Exception) {
                if (!done) {
                    lastError = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "$host: ${e.message}")
                }
            } finally {
                teardownAndClose()
            }
        }

        /** Player -> camera. */
        private fun upstreamLoop(up: Socket) {
            val input = client.getInputStream().buffered()
            val out = up.getOutputStream()
            while (true) {
                val m = RtspMsg.read(input) ?: break
                when (m) {
                    is RtspMsg.Data -> out.send(upLock, m.bytes)
                    is RtspMsg.Text -> {
                        rewriteToCamera(m)
                        if (m.method == "TEARDOWN") tornDown = true
                        if (m.method == "PLAY") setUpHiddenTracks(out)
                        pending[m.cseq] = m
                        out.send(upLock, m.encode())
                    }
                }
            }
        }

        /** Camera -> player. */
        private fun downstream(up: Socket) {
            try {
                val input = up.getInputStream().buffered(65536)
                val upOut = up.getOutputStream()
                val out = client.getOutputStream()
                while (true) {
                    val m = RtspMsg.read(input) ?: break
                    when (m) {
                        is RtspMsg.Data -> if ((m.bytes[1].toInt() and 0xFF) !in hiddenChannels) out.send(clientLock, m.bytes)
                        is RtspMsg.Text -> {
                            val cseq = m.cseq
                            val req = pending[cseq]
                            if (m.status == 401 && req != null && retried.add(cseq) &&
                                auth.challenge(m.headersNamed("WWW-Authenticate"))) {
                                // Answer the login challenge and resend the same request.
                                authorize(req)
                                upOut.send(upLock, req.encode())
                                continue
                            }
                            pending.remove(cseq)
                            val latch = hiddenReplies.remove(cseq)
                            if (latch != null) {
                                // Our own SETUP of a hidden track: note its channels, don't forward.
                                INTERLEAVED.find(m.header("Transport") ?: "")?.let { r ->
                                    val a = r.groupValues[1].toInt()
                                    hiddenChannels += a
                                    hiddenChannels += r.groupValues[2].toIntOrNull() ?: (a + 1)
                                }
                                if (m.status != 200) Log.i(TAG, "$host: hidden track SETUP refused (${m.start})")
                                latch.countDown()
                                continue
                            }
                            if (req?.method == "DESCRIBE" && m.status == 200 && m.body.isNotEmpty()) fixSdp(req, m)
                            if (m.status == 401) loginRejected = true
                            if (m.status in 200..299) loginRejected = false
                            m.header("Session")?.let { sessionId = it.substringBefore(';').trim() }
                            rewriteToPlayer(m)
                            out.send(clientLock, m.encode())
                        }
                    }
                }
            } catch (e: Exception) {
                if (!done) lastError = e.message ?: e.javaClass.simpleName
            } finally {
                shutdown()
            }
        }

        private fun fixSdp(req: RtspMsg.Text, m: RtspMsg.Text) {
            val path = (req.start.split(' ').getOrNull(1) ?: "").removePrefix("$remoteBase/").substringBefore('/')
            val sdp = String(m.body, Charsets.ISO_8859_1)
            val fixed = Sdp.fix(sdp, spropCache[key(path)], keepAudio)
            if (fixed != sdp) Log.i(TAG, "$host/$path SDP for the player:\n$fixed")
            m.body = fixed.toByteArray(Charsets.ISO_8859_1)

            val base = m.header("Content-Base") ?: m.header("Content-Location") ?: req.start.split(' ').getOrNull(1) ?: return
            val kept = Sdp.tracks(fixed).map { it.control }.toSet()
            hiddenTracks = Sdp.tracks(sdp).filter { it.control !in kept && !it.control.isNullOrEmpty() && it.control != "*" }
                .map { trackUri(base, it.control) }
        }

        /** Sets up the [hiddenTracks] on the player's session, once, right before its first PLAY. */
        private fun setUpHiddenTracks(out: java.io.OutputStream) {
            val sid = sessionId ?: return
            if (hiddenSetUp) return
            hiddenSetUp = true
            for ((i, uri) in hiddenTracks.withIndex()) {
                val ch = HIDDEN_CHANNEL + 2 * i
                val cseq = "${HIDDEN_CSEQ + i}"
                hiddenChannels += ch; hiddenChannels += ch + 1
                val req = RtspMsg.Text("SETUP $uri RTSP/1.0", mutableListOf("CSeq" to cseq, "Session" to sid,
                    "Transport" to "RTP/AVP/TCP;unicast;interleaved=$ch-${ch + 1}", "User-Agent" to "wyzecam-rtsp-tv"), ByteArray(0))
                authorize(req)
                val latch = java.util.concurrent.CountDownLatch(1)
                hiddenReplies[cseq] = latch
                pending[cseq] = req
                out.send(upLock, req.encode())
                if (!latch.await(4, java.util.concurrent.TimeUnit.SECONDS)) {
                    hiddenReplies.remove(cseq); pending.remove(cseq)
                    Log.w(TAG, "$host: no answer to hidden track SETUP $uri")
                }
            }
        }

        private fun authorize(req: RtspMsg.Text) {
            val uri = req.start.split(' ').getOrNull(1) ?: return
            auth.header(req.method, uri)?.let { req.set("Authorization", it) }
        }

        private fun rewriteToCamera(m: RtspMsg.Text) {
            m.start = m.start.replace(localBase, remoteBase)
            for (i in m.headers.indices) {
                val (k, v) = m.headers[i]
                if (v.contains(localBase)) m.headers[i] = k to v.replace(localBase, remoteBase)
            }
            m.remove("Authorization")
            m.start.split(' ').getOrNull(1)?.takeIf { it.startsWith("rtsps://") }?.let { lastUri = it }
            authorize(m)
        }

        private fun rewriteToPlayer(m: RtspMsg.Text) {
            for (i in m.headers.indices) {
                val (k, v) = m.headers[i]
                if (remoteUrl.containsMatchIn(v)) m.headers[i] = k to remoteUrl.replace(v, localBase)
            }
            // Media3 rejects Session headers with extra parameters and non-npt Range headers.
            m.header("Session")?.let { v ->
                val id = v.substringBefore(';').trim()
                val timeout = SESSION_TIMEOUT.find(v)?.groupValues?.get(1)
                m.set("Session", if (timeout != null) "$id;timeout=$timeout" else id)
            }
            m.header("Range")?.let { if (!NPT_RANGE.matches(it.trim())) m.set("Range", "npt=0.000-") }
            if (m.body.isNotEmpty()) {
                val text = String(m.body, Charsets.ISO_8859_1)
                if (remoteUrl.containsMatchIn(text)) m.body = remoteUrl.replace(text, localBase).toByteArray(Charsets.ISO_8859_1)
            }
        }

        /** If the player vanished without a TEARDOWN, send one ourselves so the camera frees the slot. */
        private fun teardownAndClose() {
            val sid = sessionId
            val uri = lastUri
            val up = upstream
            if (!tornDown && sid != null && uri != null && up != null && !up.isClosed) {
                runCatching {
                    val base = uri.substringBeforeLast('/', uri).let { if (it.count { c -> c == '/' } >= 3) it else uri }
                    val req = RtspMsg.Text("TEARDOWN $base RTSP/1.0",
                        mutableListOf("CSeq" to "9999", "Session" to sid, "User-Agent" to "wyzecam-rtsp-tv"), ByteArray(0))
                    authorize(req)
                    up.getOutputStream().send(upLock, req.encode())
                    Thread.sleep(300)
                }
            }
            shutdown()
        }

        fun shutdown() {
            done = true
            runCatching { client.close() }
            runCatching { upstream?.close() }
            sessions -= this
        }
    }

    /** A plain request/response RTSP client on one camera connection (used by [prepare]). */
    private class Conn(s: Socket, private val auth: RtspAuth) {
        private val input = s.getInputStream().buffered(65536)
        private val out = s.getOutputStream()
        private val sock = s
        private var cseq = 0

        fun request(method: String, uri: String, vararg h: Pair<String, String>): RtspMsg.Text {
            var r: RtspMsg.Text? = null
            for (attempt in 0..1) {
                send(method, uri, *h)
                r = response(cseq)
                if (r.status != 401 || attempt == 1 || !auth.challenge(r.headersNamed("WWW-Authenticate"))) break
            }
            return r!!
        }

        fun send(method: String, uri: String, vararg h: Pair<String, String>) {
            val req = RtspMsg.Text("$method $uri RTSP/1.0",
                mutableListOf("CSeq" to "${++cseq}", "User-Agent" to "wyzecam-rtsp-tv", *h), ByteArray(0))
            auth.header(method, uri)?.let { req.set("Authorization", it) }
            out.write(req.encode()); out.flush()
        }

        private fun response(n: Int): RtspMsg.Text {
            while (true) {
                val m = RtspMsg.read(input) ?: throw IOException("camera closed the connection")
                if (m is RtspMsg.Text && m.status > 0 && m.cseq == "$n") return m
            }
        }

        /**
         * Reads interleaved RTP until an SPS and a PPS have gone by. Packets are matched by RTP
         * payload type rather than interleaved channel (some cameras don't use the channel they
         * announced). Returns the parameters (or null) and a summary of what was seen.
         */
        fun catchH264(payloadType: Int, ms: Long): Pair<H264Params?, String> {
            val end = System.currentTimeMillis() + ms
            val nals = RtpH264(payloadType)   // audio is SET UP too, so skip its packets
            val channels = sortedSetOf<Int>()
            var frames = 0
            sock.soTimeout = 3000
            while (System.currentTimeMillis() < end) {
                val m = try { RtspMsg.read(input) } catch (e: SocketTimeoutException) { continue }
                    ?: return null to "camera closed the stream (${nals.summary})"
                if (m !is RtspMsg.Data) continue
                frames++; channels += m.bytes[1].toInt() and 0xFF
                nals.add(m.bytes, 4)
                val sps = nals.sps; val pps = nals.pps
                if (sps != null && pps != null) return H264Params(sps, pps) to nals.summary
            }
            val what = if (frames == 0) "no data after PLAY" else "$frames frames on ch ${channels.joinToString(",")}; ${nals.summary}"
            return null to what
        }
    }

    companion object {
        private const val TAG = "RtspsProxy"
        private val SESSION_TIMEOUT = Regex("""timeout\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val NPT_RANGE = Regex("""npt[:=]([.\d]+|now)\s?-\s?([.\d]+)?""")

        /** Interleaved channels for tracks the proxy SETs UP behind the player's back. */
        private const val HIDDEN_CHANNEL = 40
        private const val HIDDEN_CSEQ = 8000
        private val INTERLEAVED = Regex("""interleaved=(\d+)(?:-(\d+))?""")

        /** Resolves an SDP a=control value against the stream's base URL. */
        private fun trackUri(base: String, ctl: String?) = when {
            ctl.isNullOrEmpty() || ctl == "*" -> base
            ctl.startsWith("rtsp", ignoreCase = true) -> ctl
            else -> base.trimEnd('/') + "/" + ctl.trimStart('/')
        }

        /** Streams already checked by [prepare] ("host:port/path"). */
        private val ready = ConcurrentHashMap.newKeySet<String>()
        /** SPS/PPS caught from the stream, for cameras whose SDP leaves them out. */
        private val spropCache = ConcurrentHashMap<String, H264Params>()

        /** Forget what [prepare] learned (e.g. after the camera list or login changes). */
        fun forgetPrepared() { ready.clear(); spropCache.clear() }
    }
}

/**
 * Pulls SPS/PPS NAL units out of H.264 RTP packets: RFC 6184 (single NAL, STAP-A, FU-A) and the
 * non-standard variant some camera firmware sends, with Annex-B start codes (00 00 01) inside
 * the RTP payload. Also keeps a short summary of what went by, for error messages.
 */
internal class RtpH264(private val payloadType: Int = -1) {
    var sps: ByteArray? = null; private set
    var pps: ByteArray? = null; private set
    var packets = 0; private set
    private val nalTypes = sortedSetOf<Int>()
    private val payloadTypes = sortedSetOf<Int>()
    private var firstBytes = ""
    private var fu: java.io.ByteArrayOutputStream? = null

    val summary: String get() =
        if (packets == 0) "no video packets" else
        "$packets pkts, pt ${payloadTypes.joinToString(",")}, NAL ${nalTypes.joinToString(",")}, start $firstBytes"

    /** [b] holds one RTP packet starting at [off]. */
    fun add(b: ByteArray, off: Int) {
        if (b.size - off < 13 || (b[off].toInt() and 0xC0) != 0x80) return
        val first = b[off].toInt()
        val pt = b[off + 1].toInt() and 0x7F
        if (payloadType >= 0 && pt != payloadType) return
        packets++; payloadTypes += pt
        var hl = 12 + 4 * (first and 0x0F)
        if (first and 0x10 != 0) {
            if (b.size - off < hl + 4) return
            hl += 4 + 4 * (((b[off + hl + 2].toInt() and 0xFF) shl 8) or (b[off + hl + 3].toInt() and 0xFF))
        }
        val pad = if (first and 0x20 != 0) b[b.size - 1].toInt() and 0xFF else 0
        val start = off + hl; val end = b.size - pad
        if (start >= end) return
        if (firstBytes.isEmpty()) firstBytes = (start until minOf(end, start + 6)).joinToString("") { "%02x".format(b[it].toInt() and 0xFF) }
        if (isStartCode(b, start, end)) { annexB(b, start, end); return }
        when (b[start].toInt() and 0x1F) {
            in 1..23 -> nal(b.copyOfRange(start, end))
            24 -> {                                   // STAP-A
                nalTypes += 24
                var i = start + 1
                while (i + 2 <= end) {
                    val n = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
                    i += 2
                    if (n == 0 || i + n > end) break
                    nal(b.copyOfRange(i, i + n)); i += n
                }
            }
            28 -> {                                   // FU-A
                if (start + 2 > end) return
                val h = b[start + 1].toInt()
                if (h and 0x80 != 0) fu = java.io.ByteArrayOutputStream().apply {
                    write((b[start].toInt() and 0xE0) or (h and 0x1F))
                }
                val f = fu ?: return
                f.write(b, start + 2, end - start - 2)
                if (h and 0x40 != 0) { nal(f.toByteArray()); fu = null }
            }
            else -> nalTypes += b[start].toInt() and 0x1F
        }
    }

    private fun isStartCode(b: ByteArray, i: Int, end: Int) =
        i + 3 <= end && b[i].toInt() == 0 && b[i + 1].toInt() == 0 &&
            (b[i + 2].toInt() == 1 || (i + 4 <= end && b[i + 2].toInt() == 0 && b[i + 3].toInt() == 1))

    /** Splits an Annex-B byte run (00 00 01 / 00 00 00 01 separated) into NAL units. */
    private fun annexB(b: ByteArray, from: Int, end: Int) {
        val starts = mutableListOf<Int>()     // index of first NAL byte after each start code
        var i = from
        while (i + 3 <= end) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) { starts += i + 3; i += 3 } else i++
        }
        for ((k, s) in starts.withIndex()) {
            var e = if (k + 1 < starts.size) starts[k + 1] - 3 else end
            while (e > s && b[e - 1].toInt() == 0) e--     // trailing zero of a 4-byte start code
            if (e > s) nal(b.copyOfRange(s, e), split = false)
        }
    }

    private fun nal(n: ByteArray, split: Boolean = true) {
        if (n.isEmpty()) return
        if (split && isStartCode(n, 0, n.size)) { annexB(n, 0, n.size); return }
        val t = n[0].toInt() and 0x1F
        nalTypes += t
        // Some firmware glues SPS+PPS(+IDR) into one NAL with start codes in between.
        var own = n
        if (split && (t == 7 || t == 8 || t == 5 || t == 6)) {
            for (j in 1 until n.size - 2) if (isStartCode(n, j, n.size)) {
                var e = j
                while (e > 1 && n[e - 1].toInt() == 0) e--
                own = n.copyOfRange(0, e)
                annexB(n, j, n.size)
                break
            }
        }
        when (t) { 7 -> sps = own; 8 -> pps = own }
    }
}
