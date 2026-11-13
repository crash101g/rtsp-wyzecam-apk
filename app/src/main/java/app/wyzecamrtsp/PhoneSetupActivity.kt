package app.wyzecamrtsp

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Shows a QR code; the phone that scans it gets a web page to type the login, names and settings. */
class PhoneSetupActivity : Activity() {
    private var server: SetupServer? = null
    private lateinit var log: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(64), dp(40), dp(64), dp(40))
        }
        setContentView(root)

        val ip = Scanner.localAddress(this)?.first
        if (ip == null) {
            root.addView(text("This TV isn't connected to a network.\nConnect it to the same Wi-Fi as your phone and cameras, then try again.", 22f))
            return
        }
        val srv = SetupServer(this) { msg -> addLog(msg) }
        server = srv
        val url = "http://$ip:${srv.port}/?k=${srv.key}"

        val qr = ImageView(this).apply { setImageBitmap(qr(url, dp(340))); setBackgroundColor(Color.WHITE); setPadding(dp(14), dp(14), dp(14), dp(14)) }
        root.addView(qr, LinearLayout.LayoutParams(dp(368), dp(368)))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), 0, 0, 0) }
        right.addView(text("Set up from your phone", 32f))
        right.addView(text("1. Point your phone's camera at the QR code and open the link.\n" +
            "2. Type the RTSP username and password, name your cameras, and tap Save to TV.\n\n" +
            "Your phone must be on the same Wi-Fi network as this TV.", 19f, dim = true).apply { setPadding(0, dp(12), 0, dp(12)) })
        right.addView(text("Or type this address in the phone's browser:", 16f, dim = true))
        right.addView(text(url, 20f).apply { setTextColor(getColor(R.color.accent)); setPadding(0, dp(4), 0, dp(16)) })
        log = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(log)
        addLog("Waiting for your phone…")
        val done = pill("Done") { finish() }
        right.addView(done, LinearLayout.LayoutParams(dp(220), -2).apply { topMargin = dp(20) })
        root.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        done.requestFocus()
    }

    private fun addLog(msg: String) {
        if (log.childCount >= 6) log.removeViewAt(0)
        log.addView(text("• $msg", 17f))
    }

    override fun onDestroy() {
        server?.close()
        super.onDestroy()
    }

    private fun qr(content: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
        val px = IntArray(size * size) { if (m[it % size, it / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
