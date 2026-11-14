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
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * A fake camera that behaves like the SmolRTSP server on Wyze cams: PLAY answers 200, but no
 * RTP is sent unless *every* track in the SDP (audio and video) was SET UP, and the SDP has no
 * SPS/PPS. Seen in the field as "no SPS/PPS from camera (no data after PLAY)".
 */
class AllTracksCameraTest {
    private lateinit var cam: SSLServerSocket
    private val seen = CopyOnWriteArrayList<String>()      // "METHOD uri [Session]" the camera received
    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1f, 0xac.toByte(), 0x2b)
    private val pps = byteArrayOf(0x68, 0xee.toByte(), 0x3c, 0x80.toByte())

    @Before fun startCamera() {
        RtspsProxy.forgetPrepared()
        val ks = KeyStore.getInstance("PKCS12")
        javaClass.classLoader!!.getResourceAsStream("fakecam.p12").use { ks.load(it, "testpass".toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "testpass".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        cam = ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        thread(isDaemon = true) {
            while (true) {
                val s = runCatching { cam.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(s) } }
            }
        }
    }

    @After fun stop() { cam.close() }

    private fun rtp(pt: Int, payload: ByteArray) =
        byteArrayOf(0x80.toByte(), pt.toByte(), 0, 1, 0, 0, 0, 1, 0, 0, 0, 7) + payload

    private fun frame(ch: Int, pkt: ByteArray) =
        byteArrayOf('$'.code.toByte(), ch.toByte(), (pkt.size shr 8).toByte(), pkt.size.toByte()) + pkt

    private fun serve(s: Socket) {
        val input = s.getInputStream().buffered()
        val out = s.getOutputStream()
        val channels = mutableMapOf<String, Int>()            // track -> first interleaved channel
        while (true) {
            val m = RtspMsg.read(input) as? RtspMsg.Text ?: break
            val uri = m.start.split(' ')[1]
            seen += "${m.method} $uri ${m.header("Session") ?: ""}".trim()
            val resp = RtspMsg.Text("RTSP/1.0 200 OK", mutableListOf("CSeq" to m.cseq), ByteArray(0))
            when (m.method) {
                "DESCRIBE" -> {
                    resp.headers += "Content-Type" to "application/sdp"
                    resp.body = ("v=0\r\no=SmolRTSP 1 1 IN IP4 0.0.0.0\r\ns=SmolRTSP example\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n" +
                        "m=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000/1\r\n" +
                        "a=fmtp:97 streamtype=5; profile-level-id=1; mode=AAC-hbr; sizelength=13; indexlength=3; indexdeltalength=3; config=1408\r\n" +
                        "a=control:audio\r\n" +
                        "m=video 0 RTP/AVP 96\r\na=control:video\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1\r\n").toByteArray()
                }
                "SETUP" -> {
                    val t = m.header("Transport") ?: ""
                    channels[uri.substringAfterLast('/')] = Regex("""interleaved=(\d+)""").find(t)!!.groupValues[1].toInt()
                    resp.headers += "Transport" to t
                    resp.headers += "Session" to "777"
                }
                "PLAY" -> resp.headers += "Range" to "npt=now-"
            }
            out.write(resp.encode()); out.flush()
            if (m.method == "PLAY" && channels.keys.containsAll(listOf("audio", "video"))) {
                val v = channels.getValue("video"); val a = channels.getValue("audio")
                out.write(frame(a, rtp(97, byteArrayOf(0, 16, 1, 2))))
                out.write(frame(v, rtp(96, sps)))
                out.write(frame(v, rtp(96, pps)))
                out.write(frame(a, rtp(97, byteArrayOf(0, 16, 3, 4))))
                out.write(frame(v, rtp(96, byteArrayOf(0x65, 1, 2, 3))))
                out.flush()
            }
        }
    }

    @Test fun prepareSetsUpEveryTrackAndCatchesSpsPps() {
        val proxy = RtspsProxy("localhost", cam.localPort, "u", "p")
        assertNull(proxy.prepare("stream1"))
        assertTrue(seen.toString(), seen.any { it.startsWith("SETUP rtsps://localhost:${cam.localPort}/stream1/audio") })
        assertTrue(seen.toString(), seen.any { it.startsWith("SETUP rtsps://localhost:${cam.localPort}/stream1/video 777") })
        proxy.close()
    }

    @Test fun playerThatSetsUpVideoOnlyStillGetsVideo() {
        val proxy = RtspsProxy("localhost", cam.localPort, "u", "p", keepAudio = false)
        assertNull(proxy.prepare("stream1"))
        val url = proxy.url("stream1")
        val sock = Socket("127.0.0.1", url.substringAfter("127.0.0.1:").substringBefore('/').toInt())
        sock.soTimeout = 5000
        val input = sock.getInputStream().buffered()
        val out = sock.getOutputStream()
        var cseq = 0
        fun req(line: String, vararg h: Pair<String, String>): RtspMsg.Text {
            val m = RtspMsg.Text(line, mutableListOf("CSeq" to "${++cseq}", *h), ByteArray(0))
            out.write(m.encode()); out.flush()
            return RtspMsg.read(input) as RtspMsg.Text
        }

        val d = req("DESCRIBE $url RTSP/1.0", "Accept" to "application/sdp")
        val sdp = String(d.body)
        assertFalse("audio hidden from the player", sdp.contains("m=audio"))
        assertTrue("SPS/PPS from prepare", sdp.contains("sprop-parameter-sets="))
        // Media3 sets up only the tracks it was given: just the video.
        assertEquals(200, req("SETUP $url/video RTSP/1.0", "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1").status)
        val play = req("PLAY $url RTSP/1.0", "Session" to "777")
        assertEquals(200, play.status)
        assertEquals("player only sees answers to its own requests", "$cseq", play.cseq)

        // The proxy set up the audio on the player's session before the PLAY…
        val last = seen.takeLast(3)
        assertTrue(last.toString(), last[0].startsWith("SETUP") && last[0].endsWith("/stream1/video"))
        assertTrue(last.toString(), last[1].startsWith("SETUP rtsps://localhost:${cam.localPort}/stream1/audio 777"))
        assertTrue(last.toString(), last[2].startsWith("PLAY"))
        // …and the player gets only the video frames, on its own channel.
        repeat(3) {
            val f = RtspMsg.read(input) as RtspMsg.Data
            assertEquals(0, f.bytes[1].toInt())
            assertEquals(96, f.bytes[5].toInt() and 0x7F)
        }
        sock.close()
        proxy.close()
    }
}
