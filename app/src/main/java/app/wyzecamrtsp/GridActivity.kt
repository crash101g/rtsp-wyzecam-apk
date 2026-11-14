package app.wyzecamrtsp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.sqrt

/** Lays tiles out in an even grid: columns = ceil(sqrt(n)), like the desktop script. */
class CamGrid(ctx: Context) : ViewGroup(ctx) {
    var cols = 1; private set
    private val gap = ctx.dp(3)
    /** Display order; lets tiles be rearranged without detaching (and so restarting) them. */
    var order: List<View> = emptyList()

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w); val height = MeasureSpec.getSize(h)
        val n = childCount.coerceAtLeast(1)
        cols = ceil(sqrt(n.toDouble())).toInt()
        val rows = ceil(n / cols.toDouble()).toInt()
        val cw = (width - gap * (cols - 1)) / cols
        val ch = (height - gap * (rows - 1)) / rows
        for (i in 0 until childCount) getChildAt(i).measure(
            MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val n = childCount
        if (n == 0) return
        val rows = ceil(n / cols.toDouble()).toInt()
        val cw = (width - gap * (cols - 1)) / cols
        val ch = (height - gap * (rows - 1)) / rows
        val kids = if (order.size == n) order else (0 until n).map { getChildAt(it) }
        // Centre a short last row.
        for (i in 0 until n) {
            val row = i / cols; val col = i % cols
            val inRow = if (row == rows - 1) n - row * cols else cols
            val xOff = (width - (inRow * cw + (inRow - 1) * gap)) / 2
            val x = xOff + col * (cw + gap); val y = row * (ch + gap)
            kids[i].layout(x, y, x + cw, y + ch)
        }
    }
}

class GridActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var grid: CamGrid
    private lateinit var toolbar: LinearLayout
    private lateinit var toolbarScroll: HorizontalScrollView
    private lateinit var hint: TextView
    private lateinit var empty: LinearLayout
    private val tiles = mutableListOf<CameraTile>()
    private var focusIndex = 0
    private var moving: CameraTile? = null
    private var lastBack = 0L
    private var autoScanned = false
    /** Selection border + camera numbers showing (see [CameraTile.navigating]). */
    private var navigating = false
    private val endNavigating = Runnable {
        // Keep them while rearranging or in the menu bar; otherwise fade out after idle time.
        if (moving != null || toolbarScroll.visibility == View.VISIBLE) keepNavigating() else setNavigating(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
        grid = CamGrid(this)
        root.addView(grid, FrameLayout.LayoutParams(-1, -1))

        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(14), dp(24), dp(14))
        }
        toolbarScroll = HorizontalScrollView(this).apply {
            addView(toolbar)
            setBackgroundColor(0xE6101418.toInt())
            visibility = View.GONE
            isHorizontalScrollBarEnabled = false
        }
        root.addView(toolbarScroll, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        hint = text("", 16f).apply {
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(20), dp(10), dp(20), dp(10))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(80), dp(40), dp(80), dp(40))
            visibility = View.GONE
        }
        root.addView(empty, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        // Hide the menu bar as soon as focus leaves it.
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (toolbarScroll.visibility == View.VISIBLE && newFocus != null && !isInside(newFocus, toolbar)) hideToolbar(false)
            (newFocus as? CameraTile)?.let { focusIndex = tiles.indexOf(it).coerceAtLeast(0) }
        }
    }

    override fun onResume() {
        super.onResume()
        build()
        if (Store.hasLogin && Store.cams.isEmpty() && !autoScanned) {
            autoScanned = true
            runScan { build() }
        }
    }

    override fun onPause() {
        // Stop every stream before another screen opens, so the full-screen view never
        // competes with the grid for the camera's limited viewer slots.
        tiles.forEach { it.stop() }
        super.onPause()
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: Any? = v
        while (p is View) { if (p === parent) return true; p = p.parent }
        return false
    }

    // ------------------------------------------------------------------ build

    private fun build() {
        tiles.forEach { it.stop() }
        tiles.clear()
        grid.removeAllViews()
        moving = null
        val cams = Store.visible
        buildToolbar()
        if (cams.isEmpty()) { showEmpty(); return }
        empty.visibility = View.GONE
        grid.visibility = View.VISIBLE
        val n = cams.size
        cams.forEachIndexed { i, cam ->
            val t = CameraTile(this, cam, full = false)
            t.navigating = navigating
            t.setIndex(i)
            t.isClickable = true
            t.isLongClickable = true
            t.setOnClickListener { if (moving != null) endMove() else openFull(cam) }
            t.setOnLongClickListener { if (moving == null) options(t); true }
            t.resyncOffset = if (Store.resyncMin > 0) i * (Store.resyncMin * 60_000L / n) else 0L
            tiles += t
            grid.addView(t)
            t.start(delayMs = i * 250L)     // stagger connections
        }
        grid.order = tiles.toList()
        focusIndex = focusIndex.coerceIn(0, tiles.size - 1)
        tiles[focusIndex].requestFocus()
        if (Store.hintsShown < 3) {
            Store.hintsShown++; Store.save()
            showHint("OK: full screen  ·  Hold OK: camera options  ·  ▲ from the top row: menu  ·  1–9: jump to camera", 9000)
        }
    }

    private fun buildToolbar() {
        toolbar.removeAllViews()
        fun add(label: String, action: () -> Unit) {
            toolbar.addView(pill(label) { hideToolbar(true); action() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
        }
        add("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
        add("Set up from phone") { startActivity(Intent(this, PhoneSetupActivity::class.java)) }
        add("Cameras") { startActivity(Intent(this, CamerasActivity::class.java)) }
        if (tiles.isNotEmpty() || Store.visible.isNotEmpty()) {
            add("Rearrange") { tiles.getOrNull(focusIndex)?.let { startMove(it) } }
            add(if (Store.showNames) "Names: On" else "Names: Off") { toggleNames() }
            add("Reconnect all") { reconnectAll() }
        }
        val hidden = Store.cams.count { !it.enabled }
        if (hidden > 0) add("Show hidden ($hidden)") { Store.cams.forEach { it.enabled = true }; Store.save(); build() }
        add("Scan network") { runScan { build() } }
        add("Help") { help() }
    }

    private fun showEmpty() {
        grid.visibility = View.GONE
        empty.removeAllViews()
        empty.visibility = View.VISIBLE
        fun btn(label: String, action: () -> Unit) =
            empty.addView(pill(label, action), LinearLayout.LayoutParams(dp(420), -2).apply { topMargin = dp(14) })
        empty.addView(text(getString(R.string.app_name), 34f).apply { gravity = Gravity.CENTER })
        when {
            !Store.hasLogin -> {
                empty.addView(text("Watch your Wyze cameras' RTSP streams in a live grid — straight from the cameras, no cloud.\n\n" +
                    "1. In the Wyze phone app, turn on RTSP for each camera:\n    camera → Settings → Advanced Settings → RTSP, and set a username + password.\n" +
                    "2. Enter that username and password here. The easiest way is from your phone.", 18f, dim = true)
                    .apply { setPadding(0, dp(16), 0, dp(10)) })
                btn("Set up from your phone (easiest)") { startActivity(Intent(this, PhoneSetupActivity::class.java)) }
                btn("Enter login with the remote") { startActivity(Intent(this, SettingsActivity::class.java).putExtra("login", true)) }
                btn("Help") { help() }
            }
            Store.cams.isEmpty() -> {
                empty.addView(text("No cameras yet. Scan the network to find them, or add one by its IP address.", 18f, dim = true)
                    .apply { setPadding(0, dp(16), 0, dp(10)); gravity = Gravity.CENTER })
                btn("Scan network") { runScan { build() } }
                btn("Add camera by IP address") { addByIp() }
                btn("Set up from your phone") { startActivity(Intent(this, PhoneSetupActivity::class.java)) }
                btn("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
            }
            else -> {
                empty.addView(text("All cameras are hidden.", 18f, dim = true).apply { setPadding(0, dp(16), 0, dp(10)) })
                btn("Show all cameras") { Store.cams.forEach { it.enabled = true }; Store.save(); build() }
                btn("Cameras") { startActivity(Intent(this, CamerasActivity::class.java)) }
                btn("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
            }
        }
        empty.getChildAt(2)?.requestFocus()
    }

    private fun addByIp() = ask("Camera IP address", ipPrefix(), Kind.IP, "e.g. 192.168.1.243") { ip ->
        if (!Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(ip)) { toast("That isn't an IP address"); return@ask }
        val c = Store.addCam(ip); Store.save()
        testCamera(c) { build() }
    }

    // ------------------------------------------------------------------ actions

    private fun openFull(cam: Cam) {
        startActivity(Intent(this, FullscreenActivity::class.java).putExtra("ip", cam.ip))
    }

    private fun options(t: CameraTile) {
        val cam = t.cam
        val streamName = when (cam.stream) { MAIN -> "Main (HD)"; SUB -> "Substream"; else -> "Automatic" }
        val items = listOf("Watch full screen", "Move this camera", "Rename", "Hide from grid",
            "Reconnect", "Stream quality: $streamName", "Test connection", "Settings")
        choose(cam.name, items) { which ->
            when (which) {
                0 -> openFull(cam)
                1 -> startMove(t)
                2 -> ask("Rename camera", cam.name) { n -> if (n.isNotBlank()) { cam.name = n; Store.save(); t.refreshLabel() } }
                3 -> confirm("Hide ${cam.name}?", "It stays saved. Bring it back with “Show hidden” in the menu (press ▲ from the top row).", "Hide") {
                    cam.enabled = false; Store.save(); build()
                }
                4 -> t.reconnect()
                5 -> choose("Stream for ${cam.name}", listOf(
                        "Automatic (grid: ${if (Store.gridStream == SUB) "substream" else "main"}, full screen: ${if (Store.fullStream == MAIN) "main" else "substream"})",
                        "Always main stream (HD)", "Always substream (lighter)"),
                        when (cam.stream) { MAIN -> 1; SUB -> 2; else -> 0 }) { s ->
                    cam.stream = when (s) { 1 -> MAIN; 2 -> SUB; else -> null }; Store.save(); t.reconnect()
                }
                6 -> testCamera(cam)
                7 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
    }

    private fun startMove(t: CameraTile) {
        moving?.setMoving(false)
        moving = t
        t.setMoving(true)
        t.requestFocus()
        showHint("Moving ${t.cam.name}: use the arrows to move it  ·  OK or Back when done", 0)
    }

    private fun endMove() {
        val t = moving ?: return
        t.setMoving(false)
        moving = null
        // Save the new order: visible cameras in tile order, hidden ones keep their relative spots after.
        val order = tiles.map { it.cam }
        val hidden = Store.cams.filter { !it.enabled }
        Store.cams.clear(); Store.cams += order; Store.cams += hidden
        Store.save()
        tiles.forEachIndexed { i, x -> x.setIndex(i) }
        showHint("Layout saved", 2500)
    }

    private fun moveBy(delta: Int) {
        val t = moving ?: return
        val i = tiles.indexOf(t); val j = i + delta
        if (j !in tiles.indices) return
        tiles[i] = tiles[j]; tiles[j] = t
        grid.order = tiles.toList()
        grid.requestLayout()
        tiles.forEachIndexed { k, x -> x.setIndex(k) }
        focusIndex = j
        t.requestFocus()
    }

    private fun toggleNames() {
        Store.showNames = !Store.showNames; Store.save()
        tiles.forEach { it.refreshLabel() }
        buildToolbar()
        showHint(if (Store.showNames) "Names on" else "Names off", 1500)
    }

    private fun reconnectAll() {
        tiles.forEach { it.reconnect() }
        showHint("Reconnecting all cameras to live", 2000)
    }

    private fun help() = message("Remote control keys", """
        In the grid
          Arrows — pick a camera
          OK — watch it full screen
          Hold OK (or Menu) — options: move, rename, hide, stream quality, test
          ▲ from the top row — menu bar (Settings, Phone setup, Rearrange, Names, Scan…)
          1–9 — jump to that camera   ·   0 — menu bar
          Play/Pause — reconnect all cameras to live
          Back twice — exit

        Full screen
          ◀ ▶ (or Channel up/down) — previous / next camera
          OK — show the camera name and status
          Hold OK (or Menu) — options
          Back — return to the grid

        A dark grey tile means that camera has no video right now; it keeps retrying on its own.
        Typing with the remote is slow: use “Set up from phone” to enter logins and names on your phone.
    """.trimIndent())

    // ------------------------------------------------------------------ toolbar & hints

    private fun showToolbar() {
        buildToolbar()
        toolbarScroll.visibility = View.VISIBLE
        toolbar.getChildAt(0)?.requestFocus()
    }

    private fun hideToolbar(refocus: Boolean) {
        toolbarScroll.visibility = View.GONE
        if (refocus) tiles.getOrNull(focusIndex)?.requestFocus()
    }

    private fun setNavigating(on: Boolean) {
        navigating = on
        tiles.forEach { it.navigating = on }
    }

    /** Show the selection and restart the countdown to hiding it. */
    private fun keepNavigating() {
        if (!navigating) setNavigating(true)
        ui.removeCallbacks(endNavigating)
        ui.postDelayed(endNavigating, NAV_IDLE_MS)
    }

    private fun showHint(msg: String, ms: Long) {
        hint.text = msg
        hint.visibility = View.VISIBLE
        ui.removeCallbacksAndMessages("hint")
        if (ms > 0) ui.postAtTime({ hint.visibility = View.GONE }, "hint", SystemClock.uptimeMillis() + ms)
    }

    // ------------------------------------------------------------------ keys

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val k = e.keyCode
        val focused = currentFocus
        val inToolbar = toolbarScroll.visibility == View.VISIBLE && focused != null && isInside(focused, toolbar)

        // Selection hidden: the first arrow / OK press just shows which camera is selected, so
        // OK never opens a camera you couldn't see was picked.
        val wasHidden = !navigating
        keepNavigating()
        if (wasHidden && moving == null && focused is CameraTile && k in REVEAL_KEYS) return true

        moving?.let {
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (tiles.indexOf(it) % grid.cols != 0) moveBy(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (tiles.indexOf(it) % grid.cols != grid.cols - 1) moveBy(1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { moveBy(-grid.cols); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { moveBy(grid.cols); return true }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> { endMove(); return true }
            }
            return true
        }

        when (k) {
            KeyEvent.KEYCODE_DPAD_UP -> if (!inToolbar && focused is CameraTile && tiles.indexOf(focused) < grid.cols) {
                showToolbar(); return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (inToolbar) { hideToolbar(true); return true }
            KeyEvent.KEYCODE_BACK -> {
                if (toolbarScroll.visibility == View.VISIBLE) { hideToolbar(true); return true }
                val now = SystemClock.uptimeMillis()
                if (now - lastBack > 2500) { lastBack = now; showHint("Press Back again to exit", 2500); return true }
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_SETTINGS -> {
                if (k == KeyEvent.KEYCODE_SETTINGS) { startActivity(Intent(this, SettingsActivity::class.java)); return true }
                (focused as? CameraTile)?.let { options(it); return true }
                showToolbar(); return true
            }
            KeyEvent.KEYCODE_0 -> { showToolbar(); return true }
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                tiles.getOrNull(k - KeyEvent.KEYCODE_1)?.let { hideToolbar(false); it.requestFocus() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> { reconnectAll(); return true }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (tiles.isNotEmpty()) tiles[(focusIndex + 1) % tiles.size].requestFocus(); return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP -> {
                if (tiles.isNotEmpty()) tiles[(focusIndex - 1 + tiles.size) % tiles.size].requestFocus(); return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    companion object {
        /** How long the selection border and numbers stay up after the last key press. */
        const val NAV_IDLE_MS = 5_000L
        private val REVEAL_KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
    }
}
