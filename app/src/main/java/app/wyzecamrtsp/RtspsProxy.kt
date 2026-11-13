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
                val base = d.header("Content-Base") ?: d.header("Content-Location") ?: uri
                val ctl = t.control
                val trackUri = when {
                    ctl.isNullOrEmpty() || ctl == "*" -> base
                    ctl.startsWith("rtsp", ignoreCase = true) -> ctl
                    else -> base.trimEnd('/') + "/" + ctl.trimStart('/')
                }
                val su = c.request("SETUP", trackUri, "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1")
                if (su.status != 200) return "camera refused SETUP (${su.start.removePrefix("RTSP/1.0 ")})"
                val sid = su.header("Session")?.substringBefore(';')?.trim()
                    ?: return "camera sent no session id"
                val ch = Regex("""interleaved=(\d+)""").find(su.header("Transport") ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val p = c.request("PLAY", base, "Session" to sid, "Range" to "npt=0.000-")
                val params = if (p.status == 200) c.catchH264(ch, 10_000) else null
                runCatching { c.send("TEARDOWN", base, "Session" to sid) }
                if (params == null) return "camera sent no H.264 setup data (SPS/PPS)"
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
                        is RtspMsg.Data -> out.send(clientLock, m.bytes)
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

        /** Reads interleaved RTP on [channel] until an SPS and a PPS have gone by. */
        fun catchH264(channel: Int, ms: Long): H264Params? {
            val end = System.currentTimeMillis() + ms
            val nals = RtpH264()
            sock.soTimeout = 2000
            while (System.currentTimeMillis() < end) {
                val m = try { RtspMsg.read(input) } catch (e: SocketTimeoutException) { continue } ?: return null
                if (m !is RtspMsg.Data || (m.bytes[1].toInt() and 0xFF) != channel) continue
                nals.add(m.bytes, 4)
                val sps = nals.sps; val pps = nals.pps
                if (sps != null && pps != null) return H264Params(sps, pps)
            }
            return null
        }
    }

    companion object {
        private const val TAG = "RtspsProxy"
        private val SESSION_TIMEOUT = Regex("""timeout\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val NPT_RANGE = Regex("""npt[:=]([.\d]+|now)\s?-\s?([.\d]+)?""")

        /** Streams already checked by [prepare] ("host:port/path"). */
        private val ready = ConcurrentHashMap.newKeySet<String>()
        /** SPS/PPS caught from the stream, for cameras whose SDP leaves them out. */
        private val spropCache = ConcurrentHashMap<String, H264Params>()

        /** Forget what [prepare] learned (e.g. after the camera list or login changes). */
        fun forgetPrepared() { ready.clear(); spropCache.clear() }
    }
}

/** Pulls SPS/PPS NAL units out of H.264 RTP packets (RFC 6184: single NAL, STAP-A, FU-A). */
internal class RtpH264 {
    var sps: ByteArray? = null; private set
    var pps: ByteArray? = null; private set
    private var fu: java.io.ByteArrayOutputStream? = null

    /** [b] holds one RTP packet starting at [off]. */
    fun add(b: ByteArray, off: Int) {
        if (b.size - off < 13 || (b[off].toInt() and 0xC0) != 0x80) return
        val first = b[off].toInt()
        var hl = 12 + 4 * (first and 0x0F)
        if (first and 0x10 != 0) {
            if (b.size - off < hl + 4) return
            hl += 4 + 4 * (((b[off + hl + 2].toInt() and 0xFF) shl 8) or (b[off + hl + 3].toInt() and 0xFF))
        }
        val pad = if (first and 0x20 != 0) b[b.size - 1].toInt() and 0xFF else 0
        val start = off + hl; val end = b.size - pad
        if (start >= end) return
        when (b[start].toInt() and 0x1F) {
            in 1..23 -> nal(b.copyOfRange(start, end))
            24 -> {                                   // STAP-A
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
            else -> {}
        }
    }

    private fun nal(n: ByteArray) {
        if (n.isEmpty()) return
        when (n[0].toInt() and 0x1F) { 7 -> sps = n; 8 -> pps = n }
    }
}
