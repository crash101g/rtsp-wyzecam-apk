package app.wyzecamrtsp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * Drives the proxy the way Media3 does (plain RTSP, RTP over TCP) against a fake Wyze-style
 * camera: RTSPS with a self-signed cert and Digest auth (qop=auth).
 */
class RtspsProxyTest {
    private val user = "admin"
    private val pass = "p@ss:word"
    private lateinit var cam: SSLServerSocket
    private val seen = CopyOnWriteArrayList<String>()      // request lines the camera received
    private val authOk = CopyOnWriteArrayList<Boolean>()
    private val teardown = CountDownLatch(1)

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Before fun startCamera() {
        val ks = KeyStore.getInstance("PKCS12")
        javaClass.classLoader!!.getResourceAsStream("fakecam.p12").use { ks.load(it, "testpass".toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "testpass".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        cam = ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        val port = cam.localPort
        thread(isDaemon = true) {
            while (true) {
                val s = runCatching { cam.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(s, port) } }
            }
        }
    }

    private fun serve(s: Socket, port: Int) {
        val input = s.getInputStream().buffered()
        val out = s.getOutputStream()
        val nonce = "abc123"
        while (true) {
            val m = RtspMsg.read(input) as? RtspMsg.Text ?: break
            seen += m.start
            val uri = m.start.split(' ')[1]
            val a = m.header("Authorization")
            val ok = a != null && run {
                val p = Regex("""(\w+)="?([^",]*)"?""").findAll(a.removePrefix("Digest ")).associate { it.groupValues[1] to it.groupValues[2] }
                val ha1 = md5("$user:fakecam:$pass"); val ha2 = md5("${m.method}:${p["uri"]}")
                p["uri"] == uri && p["response"] == md5("$ha1:$nonce:${p["nc"]}:${p["cnonce"]}:${p["qop"]}:$ha2")
            }
            authOk += ok
            val resp = RtspMsg.Text("", mutableListOf("CSeq" to m.cseq), ByteArray(0))
            if (!ok) {
                resp.start = "RTSP/1.0 401 Unauthorized"
                resp.headers += "WWW-Authenticate" to "Digest realm=\"fakecam\", nonce=\"$nonce\", qop=\"auth\""
                out.write(resp.encode()); out.flush(); continue
            }
            resp.start = "RTSP/1.0 200 OK"
            when (m.method) {
                "DESCRIBE" -> {
                    resp.headers += "Content-Base" to "rtsps://localhost:$port/stream0/"
                    resp.headers += "Content-Type" to "application/sdp"
                    resp.body = ("v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n" +
                        "a=control:rtsps://localhost:$port/stream0/trackID=0\r\n").toByteArray()
                }
                "SETUP" -> { resp.headers += "Session" to "1234;timeout=60"; resp.headers += "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1" }
                "PLAY" -> resp.headers += "Session" to "1234"
                "TEARDOWN" -> teardown.countDown()
            }
            out.write(resp.encode()); out.flush()
            if (m.method == "PLAY") repeat(3) { i ->
                val payload = ByteArray(1000 + i) { it.toByte() }
                out.write(byteArrayOf('$'.code.toByte(), 0, (payload.size shr 8).toByte(), payload.size.toByte()) + payload)
                out.flush()
            }
        }
    }

    @After fun stop() { cam.close() }

    @Test fun proxiesLoginRewritesUrlsPassesDataAndTearsDown() {
        val proxy = RtspsProxy("localhost", cam.localPort, user, pass)
        val url = proxy.url("stream0")
        val lp = url.substringAfter("127.0.0.1:").substringBefore('/').toInt()
        val sock = Socket("127.0.0.1", lp)
        sock.soTimeout = 5000
        val input = sock.getInputStream().buffered()
        val out = sock.getOutputStream()
        var cseq = 0
        fun req(line: String, vararg h: Pair<String, String>): RtspMsg.Text {
            val m = RtspMsg.Text(line, mutableListOf("CSeq" to "${++cseq}", *h), ByteArray(0))
            out.write(m.encode()); out.flush()
            return RtspMsg.read(input) as RtspMsg.Text
        }

        assertEquals(200, req("OPTIONS $url RTSP/1.0").status)
        val d = req("DESCRIBE $url RTSP/1.0", "Accept" to "application/sdp")
        assertEquals(200, d.status)
        assertEquals("$url/", d.header("Content-Base"))
        assertTrue(String(d.body).contains("a=control:$url/trackID=0"))
        assertEquals("${d.body.size}", d.header("Content-Length"))
        val s = req("SETUP $url/trackID=0 RTSP/1.0", "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1")
        assertEquals(200, s.status)
        assertEquals(200, req("PLAY $url/ RTSP/1.0", "Session" to "1234").status)
        repeat(3) { i ->
            val f = RtspMsg.read(input) as RtspMsg.Data
            assertEquals(4 + 1000 + i, f.bytes.size)
        }
        // Every request reached the camera as rtsps://localhost:<camport>/…
        assertTrue(seen.all { it.split(' ')[1].startsWith("rtsps://localhost:${cam.localPort}/") })
        assertTrue(seen.first().startsWith("OPTIONS"))

        // Player vanishes without a TEARDOWN: the proxy must send one so the camera frees the slot.
        sock.close()
        assertTrue("proxy sent TEARDOWN", teardown.await(3, TimeUnit.SECONDS))
        assertTrue(seen.last().startsWith("TEARDOWN rtsps://localhost:${cam.localPort}/stream0"))
        assertTrue("last request was authenticated", authOk.last())
        proxy.close()
    }

    @Test fun probeDescribesWithLogin() {
        val r = RtspProbe.describe("localhost", cam.localPort, MAIN, user, pass)
        assertTrue(r.error, r.ok)
        assertEquals("H264", r.codec)
        val bad = RtspProbe.describe("localhost", cam.localPort, MAIN, user, "wrong")
        assertTrue(!bad.ok && bad.error.startsWith("login rejected"))
        assertTrue(RtspProbe.options("localhost", cam.localPort) != null)
    }
}
