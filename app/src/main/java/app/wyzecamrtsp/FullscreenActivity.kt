package app.wyzecamrtsp

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** One camera filling the screen (main stream by default). ◀ ▶ flips between cameras. */
class FullscreenActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var info: LinearLayout
    private lateinit var infoTitle: TextView
    private lateinit var infoSub: TextView
    private var tile: CameraTile? = null
    private var index = 0
    private var okDownAt = 0L
    private var longFired = false

    private val cams get() = Store.visible.ifEmpty { Store.cams }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
        info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xB3000000.toInt())
            setPadding(dp(36), dp(20), dp(36), dp(20))
        }
        infoTitle = text("", 28f); infoSub = text("", 17f, dim = true)
        info.addView(infoTitle); info.addView(infoSub)
        setContentView(root)
        val ip = intent.getStringExtra("ip")
        index = cams.indexOfFirst { it.ip == ip }.coerceAtLeast(0)
    }

    override fun onResume() {
        super.onResume()
        show(index)
    }

    override fun onPause() {
        tile?.stop()
        super.onPause()
    }

    private fun show(i: Int) {
        val list = cams
        if (list.isEmpty()) { finish(); return }
        index = (i + list.size) % list.size
        tile?.let { it.stop(); root.removeView(it) }
        root.removeView(info)
        val t = CameraTile(this, list[index], full = true)
        t.onStatus = { if (info.visibility == View.VISIBLE) updateInfo() }
        root.addView(t, FrameLayout.LayoutParams(-1, -1))
        root.addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        tile = t
        t.start()
        showInfo(4500)
    }

    private fun updateInfo() {
        val t = tile ?: return
        val c = t.cam
        val list = cams
        infoTitle.text = "${c.name}    ${index + 1} / ${list.size}"
        val stream = if (t.activeStream == MAIN) "main stream (HD)" else "substream"
        val nav = if (list.size > 1) "◀ ▶ switch camera  ·  " else ""
        infoSub.text = "${c.ip}  ·  $stream${if (Store.fullAudio) "  ·  sound on" else ""}\n" +
            "${nav}Hold OK: options  ·  Back: grid"
    }

    private fun showInfo(ms: Long) {
        updateInfo()
        info.visibility = View.VISIBLE
        ui.removeCallbacksAndMessages(null)
        if (ms > 0) ui.postDelayed({ info.visibility = View.GONE }, ms)
    }

    private fun options() {
        val t = tile ?: return
        val cam = t.cam
        val items = listOf(
            if (t.activeStream == MAIN) "Switch to substream (lighter)" else "Switch to main stream (HD)",
            if (Store.fullAudio) "Sound: On  (turn off)" else "Sound: Off  (turn on)",
            "Rename", "Reconnect", "Test connection", "Back to grid")
        choose(cam.name, items) { which ->
            when (which) {
                0 -> { cam.stream = if (t.activeStream == MAIN) SUB else MAIN; Store.save(); show(index) }
                1 -> { Store.fullAudio = !Store.fullAudio; Store.save(); show(index) }
                2 -> ask("Rename camera", cam.name) { n -> if (n.isNotBlank()) { cam.name = n; Store.save(); t.refreshLabel(); showInfo(3000) } }
                3 -> t.reconnect()
                4 -> testCamera(cam)
                5 -> finish()
            }
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        val isOk = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (isOk) {
            // Short press = info, long press (0.6 s) = options.
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (e.repeatCount == 0) { okDownAt = e.eventTime; longFired = false }
                else if (!longFired && e.eventTime - okDownAt > 600) { longFired = true; options() }
            } else if (e.action == KeyEvent.ACTION_UP && !longFired) {
                if (info.visibility == View.VISIBLE) info.visibility = View.GONE else showInfo(5000)
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        when (k) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_PAGE_UP -> { show(index - 1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_PAGE_DOWN -> { show(index + 1); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> { showInfo(5000); return true }
            KeyEvent.KEYCODE_MENU -> { options(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> { tile?.reconnect(); return true }
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                val i = k - KeyEvent.KEYCODE_1
                if (i < cams.size) show(i)
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }
}
