package app.wyzecamrtsp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * A fake camera with the SDP quirks that make Media3 fail with "io unspecified":
 * no SPS/PPS in the H.264 fmtp, two payload types on the m= line, an empty s= line, an AAC
 * track without its config, a Session header with extra parameters and a non-npt Range.
 * SPS/PPS only arrive in-band (STAP-A) after PLAY, like many IP cameras.
 */
class QuirkyCameraTest {
    private val user = "admin"
    private val pass = "secret"
    private lateinit var cam: SSLServerSocket
    private val seen = CopyOnWriteArrayList<String>()
    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1F, 0xDA.toByte(), 0x01, 0x40, 0x16, 0xEC.toByte())
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

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

    private fun rtp(payload: ByteArray, seq: Int): ByteArray {
        val hdr = byteArrayOf(0x80.toByte(), 96, (seq shr 8).toByte(), seq.toByte(), 0, 0, 0, 1, 0, 0, 0, 7)
        val p = hdr + payload
        return byteArrayOf('$'.code.toByte(), 0, (p.size shr 8).toByte(), p.size.toByte()) + p
    }

    private fun serve(s: Socket, port: Int) {
        val input = s.getInputStream().buffered()
        val out = s.getOutputStream()
        val nonce = "n0nce"
        while (true) {
            val m = RtspMsg.read(input) as? RtspMsg.Text ?: break
            seen += m.start
            val uri = m.start.split(' ')[1]
            val a = m.header("Authorization")
            val ok = m.method == "OPTIONS" || a != null && run {
                val p = Regex("""(\w+)="?([^",]*)"?""").findAll(a.removePrefix("Digest ")).associate { it.groupValues[1] to it.groupValues[2] }
                val ha1 = md5("$user:cam:$pass"); val ha2 = md5("${m.method}:${p["uri"]}")
                p["uri"] == uri && p["response"] == md5("$ha1:$nonce:$ha2")
            }
            val resp = RtspMsg.Text("", mutableListOf("CSeq" to m.cseq), ByteArray(0))
            if (!ok) {
                resp.start = "RTSP/1.0 401 Unauthorized"
                resp.headers += "WWW-Authenticate" to "Digest realm=\"cam\", nonce=\"$nonce\""
                out.write(resp.encode()); out.flush(); continue
            }
            resp.start = "RTSP/1.0 200 OK"
            when (m.method) {
                "OPTIONS" -> resp.headers += "Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN"
                "DESCRIBE" -> {
                    resp.headers += "Content-Base" to "rtsps://localhost:$port/stream1/"
                    resp.headers += "Content-Type" to "application/sdp"
                    resp.body = ("v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\ns=\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\na=control:*\r\n" +
                        "m=video 0 RTP/AVP 96 97\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;profile-level-id=42e01f;\r\n" +
                        "a=control:trackID=0\r\nm=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000/1\r\na=control:trackID=1\r\n").toByteArray()
                }
                "SETUP" -> {
                    resp.headers += "Session" to "ABC123;timeout=60;foo=bar"
                    resp.headers += "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"
                }
                "PLAY" -> { resp.headers += "Session" to "ABC123"; resp.headers += "Range" to "clock=20261113T000000Z-" }
            }
            out.write(resp.encode()); out.flush()
            if (m.method == "PLAY") {
                // A non-IDR slice, then SPS+PPS in one STAP-A, then an IDR slice.
                out.write(rtp(byteArrayOf(0x41, 1, 2, 3), 1))
                val stap = byteArrayOf(24) + byteArrayOf(0, sps.size.toByte()) + sps + byteArrayOf(0, pps.size.toByte()) + pps
                out.write(rtp(stap, 2))
                out.write(rtp(byteArrayOf(0x65, 9, 9, 9), 3))
                out.flush()
            }
        }
    }

    @After fun stop() { cam.close(); RtspsProxy.forgetPrepared() }

    @Test fun prepareCatchesSpsPpsAndPlayerGetsAFixedSdp() {
        val proxy = RtspsProxy("localhost", cam.localPort, user, pass)
        assertNull(proxy.prepare("stream1"))
        val until = System.currentTimeMillis() + 3000
        while (seen.none { it.startsWith("TEARDOWN") } && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("prepare tore its session down", seen.any { it.startsWith("TEARDOWN") })
        val sprop = Base64.getEncoder().encodeToString(sps) + "," + Base64.getEncoder().encodeToString(pps)

        // Now act like Media3 through the proxy.
        val url = proxy.url("stream1")
        val sock = Socket("127.0.0.1", url.substringAfter("127.0.0.1:").substringBefore('/').toInt())
        sock.soTimeout = 5000
        val input = sock.getInputStream().buffered(); val out = sock.getOutputStream()
        var cseq = 0
        fun req(line: String, vararg h: Pair<String, String>): RtspMsg.Text {
            out.write(RtspMsg.Text(line, mutableListOf("CSeq" to "${++cseq}", *h), ByteArray(0)).encode()); out.flush()
            while (true) { val m = RtspMsg.read(input); if (m is RtspMsg.Text) return m }
        }
        assertEquals(200, req("OPTIONS $url RTSP/1.0").status)
        val d = req("DESCRIBE $url RTSP/1.0", "Accept" to "application/sdp")
        assertEquals(200, d.status)
        val sdp = String(d.body)
        assertTrue(sdp, sdp.contains("sprop-parameter-sets=$sprop"))
        assertTrue(sdp, sdp.contains("\r\nm=video 0 RTP/AVP 96\r\n"))
        assertTrue(sdp, sdp.contains("\r\ns=Camera\r\n"))
        assertFalse("AAC track without config is dropped", sdp.contains("m=audio"))
        assertFalse(sdp, Regex("(?m)^[a-z]=\\s*\\r?$").containsMatchIn(sdp))
        assertEquals("${d.body.size}", d.header("Content-Length"))

        val s = req("SETUP $url/trackID=0 RTSP/1.0", "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1")
        assertEquals("ABC123;timeout=60", s.header("Session"))
        val p = req("PLAY $url/ RTSP/1.0", "Session" to "ABC123")
        assertEquals(200, p.status)
        assertEquals("npt=0.000-", p.header("Range"))
        sock.close(); proxy.close()

        // A second proxy for the same stream reuses what prepare learned (no extra PLAY).
        val plays = seen.count { it.startsWith("PLAY") }
        assertNull(RtspsProxy("localhost", cam.localPort, user, pass).prepare("stream1"))
        assertEquals(plays, seen.count { it.startsWith("PLAY") })
    }

    @Test fun prepareReportsWrongLogin() {
        val r = RtspsProxy("localhost", cam.localPort, user, "wrong").prepare("stream1")
        assertTrue(r ?: "null", r!!.startsWith("login rejected"))
    }

    @Test fun sdpFixKeepsGoodSpropAndSimpleAudio() {
        val sp = Base64.getEncoder().encodeToString(sps); val pp = Base64.getEncoder().encodeToString(pps)
        val sei = Base64.getEncoder().encodeToString(byteArrayOf(6, 5, 1))
        val sdp = "v=0\no=- 0 0 IN IP4 1.2.3.4\ns=x\nt=0 0\nm=video 0 RTP/AVP 96\na=rtpmap:96 H264/90000\n" +
            "a=fmtp:96 profile-level-id=4d001f; sprop-parameter-sets=$sp,$pp,$sei; bogus\na=control:track0\n" +
            "m=audio 0 RTP/AVP 8\na=control:track1\n"
        val withAudio = Sdp.fix(sdp, null, keepAudio = true)
        assertTrue(withAudio, withAudio.contains("a=fmtp:96 profile-level-id=4d001f;sprop-parameter-sets=$sp,$pp;packetization-mode=1\r\n"))
        assertTrue(withAudio, withAudio.contains("m=audio 0 RTP/AVP 8\r\na=control:track1"))
        assertFalse(Sdp.fix(sdp, null, keepAudio = false).contains("m=audio"))
        assertTrue(Sdp.hasUsableSprop(Sdp.h264Track(sdp)!!))
    }
}
