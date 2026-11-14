package app.wyzecamrtsp

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** TLS to the cameras. Wyze cams use a self-signed certificate, so it is not verified
 *  (same as the desktop script); traffic stays on your LAN. */
object Tls {
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    private val factory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }.socketFactory
    }

    /**
     * Opens a TLS connection. If [expectKey] is set (a [key] seen before), the camera must present
     * that same key, otherwise [WrongCameraException] is thrown: after DHCP hands out addresses
     * again, an old IP may now belong to a different camera.
     */
    fun connect(host: String, port: Int, timeoutMs: Int, expectKey: String = ""): SSLSocket {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), timeoutMs)
            raw.soTimeout = timeoutMs
            raw.tcpNoDelay = true
            val s = factory.createSocket(raw, host, port, true) as SSLSocket
            s.startHandshake()
            if (expectKey.isNotEmpty() && key(s) != expectKey) {
                runCatching { s.close() }
                throw WrongCameraException()
            }
            return s
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    }

    /**
     * A camera's identity: SHA-256 of the public key in its TLS certificate. Each Wyze cam makes
     * its own key pair, so this tells cameras apart and survives IP changes. (Android 10+ doesn't
     * let apps read the ARP table, so the MAC address usually isn't available.)
     */
    fun key(s: SSLSocket): String = runCatching {
        val cert = s.session.peerCertificates.first()
        MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")
}

class WrongCameraException : IOException(WRONG_CAMERA)
const val WRONG_CAMERA = "a different camera is now at this IP address"

/** An RTSP message on the wire: either a text request/response or an interleaved '$' data frame. */
sealed class RtspMsg {
    class Data(val bytes: ByteArray) : RtspMsg()
    class Text(var start: String, val headers: MutableList<Pair<String, String>>, var body: ByteArray) : RtspMsg() {
        fun header(name: String) = headers.firstOrNull { it.first.equals(name, true) }?.second
        fun headersNamed(name: String) = headers.filter { it.first.equals(name, true) }.map { it.second }
        fun remove(name: String) { headers.removeAll { it.first.equals(name, true) } }
        fun set(name: String, value: String) { remove(name); headers += name to value }
        val cseq get() = header("CSeq")?.trim() ?: ""
        val status get() = if (start.startsWith("RTSP/")) start.split(' ').getOrNull(1)?.toIntOrNull() ?: 0 else 0
        val method get() = start.substringBefore(' ')

        fun encode(): ByteArray {
            val sb = StringBuilder(start).append("\r\n")
            for ((k, v) in headers) if (!k.equals("Content-Length", true)) sb.append(k).append(": ").append(v).append("\r\n")
            if (body.isNotEmpty()) sb.append("Content-Length: ").append(body.size).append("\r\n")
            sb.append("\r\n")
            return sb.toString().toByteArray(Charsets.ISO_8859_1) + body
        }
    }

    companion object {
        /** Reads one message, or returns null at end of stream. */
        fun read(input: InputStream): RtspMsg? {
            var first = input.read()
            while (first == '\r'.code || first == '\n'.code) first = input.read()   // stray blank lines
            if (first < 0) return null
            if (first == '$'.code) {
                val hdr = ByteArray(3)
                readFully(input, hdr)
                val len = ((hdr[1].toInt() and 0xFF) shl 8) or (hdr[2].toInt() and 0xFF)
                val out = ByteArray(4 + len)
                out[0] = '$'.code.toByte(); hdr.copyInto(out, 1)
                readFully(input, out, 4, len)
                return Data(out)
            }
            val start = readLine(input, first) ?: return null
            val headers = mutableListOf<Pair<String, String>>()
            while (true) {
                val line = readLine(input, input.read()) ?: return null
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers += line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            val len = headers.firstOrNull { it.first.equals("Content-Length", true) }?.second?.trim()?.toIntOrNull() ?: 0
            val body = ByteArray(len.coerceIn(0, 1 shl 20))
            readFully(input, body)
            return Text(start, headers, body)
        }

        private fun readLine(input: InputStream, firstByte: Int): String? {
            if (firstByte < 0) return null
            val b = ByteArrayOutputStream()
            var c = firstByte
            while (c >= 0 && c != '\n'.code) {
                if (c != '\r'.code) b.write(c)
                if (b.size() > 16384) throw IOException("RTSP header line too long")
                c = input.read()
            }
            if (c < 0 && b.size() == 0) return null
            return b.toString("ISO-8859-1")
        }

        private fun readFully(input: InputStream, buf: ByteArray, off: Int = 0, len: Int = buf.size - off) {
            var n = 0
            while (n < len) {
                val r = input.read(buf, off + n, len - n)
                if (r < 0) throw IOException("stream ended mid-message")
                n += r
            }
        }
    }
}

/** RTSP Digest / Basic authentication, answering the camera's WWW-Authenticate challenge. */
class RtspAuth(private val user: String, private val pass: String) {
    private var realm = ""
    private var nonce = ""
    private var opaque: String? = null
    private var qop: String? = null
    private var algorithm = "MD5"
    private var basic = false
    private var ready = false
    private var nc = 0

    val hasChallenge @Synchronized get() = ready

    /** Returns true if a usable challenge was found. */
    @Synchronized
    fun challenge(values: List<String>): Boolean {
        val digest = values.firstOrNull { it.trim().startsWith("Digest", true) }
        if (digest != null) {
            val p = params(digest.trim().substring(6))
            realm = p["realm"] ?: ""
            nonce = p["nonce"] ?: return false
            opaque = p["opaque"]
            qop = p["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it.equals("auth", true) }
            algorithm = p["algorithm"] ?: "MD5"
            basic = false; nc = 0; ready = true
            return true
        }
        if (values.any { it.trim().startsWith("Basic", true) }) {
            basic = true; ready = true
            return true
        }
        return false
    }

    @Synchronized
    fun header(method: String, uri: String): String? {
        if (!ready) return null
        if (basic) return "Basic " + android.util.Base64.encodeToString(
            "$user:$pass".toByteArray(), android.util.Base64.NO_WRAP)
        val ha1 = md5("$user:$realm:$pass")
        val ha2 = md5("$method:$uri")
        val sb = StringBuilder("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\"")
        if (qop != null) {
            nc++
            val ncs = "%08x".format(nc)
            val cnonce = "%016x".format(SecureRandom().nextLong())
            sb.append(", response=\"").append(md5("$ha1:$nonce:$ncs:$cnonce:$qop:$ha2")).append('"')
            sb.append(", qop=$qop, nc=$ncs, cnonce=\"$cnonce\"")
        } else {
            sb.append(", response=\"").append(md5("$ha1:$nonce:$ha2")).append('"')
        }
        opaque?.let { sb.append(", opaque=\"").append(it).append('"') }
        if (!algorithm.equals("MD5", true)) sb.append(", algorithm=").append(algorithm)
        return sb.toString()
    }

    private fun params(s: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^,\s]+))""").findAll(s).forEach {
            out[it.groupValues[1].lowercase()] = it.groups[2]?.value ?: it.groupValues[3]
        }
        return out
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

/** Result of checking one camera with a real login (used by the scanner and "Test login"). */
data class ProbeResult(val ok: Boolean, val error: String = "", val codec: String = "", val server: String = "",
                       val key: String = "")

object RtspProbe {
    /** Unauthenticated OPTIONS: does this host speak RTSPS at all? Returns the Server header or null. */
    fun options(ip: String, port: Int, timeoutMs: Int = 3000): String? = try {
        Tls.connect(ip, port, timeoutMs).use { s ->
            val req = RtspMsg.Text("OPTIONS rtsps://$ip:$port/ RTSP/1.0",
                mutableListOf("CSeq" to "1", "User-Agent" to "wyzecam-rtsp-tv"), ByteArray(0))
            s.outputStream.write(req.encode()); s.outputStream.flush()
            val r = RtspMsg.read(s.inputStream.buffered()) as? RtspMsg.Text
            if (r != null && r.start.startsWith("RTSP/")) r.header("Server") ?: "" else null
        }
    } catch (e: Exception) { null }

    /** DESCRIBE with the login: proves the stream exists and the username/password work. */
    fun describe(ip: String, port: Int, path: String, user: String, pass: String, timeoutMs: Int = 8000): ProbeResult {
        var key = ""
        return describe(ip, port, path, user, pass, timeoutMs) { key = it }.copy(key = key)
    }

    private fun describe(ip: String, port: Int, path: String, user: String, pass: String, timeoutMs: Int,
                         onKey: (String) -> Unit): ProbeResult = try {
        Tls.connect(ip, port, timeoutMs).use { s ->
            onKey(Tls.key(s))
            val input = s.inputStream.buffered()
            val out = s.outputStream
            val uri = "rtsps://$ip:$port/$path"
            val auth = RtspAuth(user, pass)
            var resp: RtspMsg.Text? = null
            for (cseq in 1..2) {
                val req = RtspMsg.Text("DESCRIBE $uri RTSP/1.0",
                    mutableListOf("CSeq" to "$cseq", "Accept" to "application/sdp", "User-Agent" to "wyzecam-rtsp-tv"),
                    ByteArray(0))
                auth.header("DESCRIBE", uri)?.let { req.set("Authorization", it) }
                out.write(req.encode()); out.flush()
                do { val m = RtspMsg.read(input) ?: throw IOException("camera closed the connection")
                     resp = m as? RtspMsg.Text } while (resp == null)
                if (resp!!.status != 401 || cseq == 2 || !auth.challenge(resp!!.headersNamed("WWW-Authenticate"))) break
            }
            val r = resp!!
            when (r.status) {
                200 -> {
                    val sdp = String(r.body, Charsets.ISO_8859_1)
                    val codec = Regex("""a=rtpmap:\d+\s+([A-Za-z0-9-]+)/""").findAll(sdp)
                        .map { it.groupValues[1].uppercase() }.firstOrNull { it.startsWith("H26") } ?: ""
                    ProbeResult(true, codec = codec, server = r.header("Server") ?: "")
                }
                401 -> ProbeResult(false, "login rejected (check username/password)")
                404 -> ProbeResult(false, "stream $path not found")
                453, 503 -> ProbeResult(false, "camera busy (too many viewers?)")
                else -> ProbeResult(false, r.start.removePrefix("RTSP/1.0 "))
            }
        }
    } catch (e: java.net.SocketTimeoutException) {
        ProbeResult(false, "timed out")
    } catch (e: java.net.ConnectException) {
        ProbeResult(false, "camera refused the connection")
    } catch (e: Exception) {
        ProbeResult(false, e.message ?: e.javaClass.simpleName)
    }
}

/** Write helper shared by proxy threads. */
internal fun OutputStream.send(lock: Any, bytes: ByteArray) = synchronized(lock) { write(bytes); flush() }
