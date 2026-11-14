package app.wyzecamrtsp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.ScrollView

/** Camera list: OK on a camera opens its options. ◀ / ▶ on a row moves it up / down the order. */
class CamerasActivity : Activity() {
    private lateinit var list: LinearLayout
    private var focusIp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = column()
        setContentView(ScrollView(this).apply { addView(list); isFillViewport = true })
    }

    override fun onResume() { super.onResume(); build() }

    private fun build() {
        list.removeAllViews()
        list.addView(text("Cameras", 32f))
        list.addView(text("OK: options  ·  ◀ ▶: move up / down in the grid order", 16f, dim = true).apply { setPadding(dp(4), dp(4), 0, dp(16)) })
        if (Store.cams.isEmpty()) {
            list.addView(Row(this, "Scan network for cameras") { runScan { build() } })
            list.getChildAt(2).requestFocus()
            return
        }
        var toFocus: Row? = null
        Store.cams.forEachIndexed { i, c ->
            val state = buildString {
                append(c.ip)
                if (!c.enabled) append("  ·  HIDDEN")
                append(if (c.ok) "  ·  ✓ working" else "  ·  ✗ ${c.error.ifEmpty { "not working" }}")
                when (c.stream) { MAIN -> append("  ·  always main"); SUB -> append("  ·  always substream") }
                if (c.codec.isNotEmpty()) append("  ·  ${c.codec}")
            }
            val r = Row(this, "${i + 1}.  ${c.name}", state) { options(c) }
            r.alpha = if (c.enabled) 1f else 0.6f
            r.setOnKeyListener { _, k, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (k) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { move(c, -1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { move(c, 1); true }
                    else -> false
                }
            }
            list.addView(r)
            if (c.ip == focusIp || toFocus == null && i == 0) toFocus = r
        }
        list.addView(Row(this, "+ Add a camera by IP address") {
            ask("Camera IP address", ipPrefix(), Kind.IP) { ip ->
                if (!Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(ip)) { toast("That isn't an IP address"); return@ask }
                val c = Store.addCam(ip); Store.save(); focusIp = ip; testCamera(c) { build() }
            }
        })
        list.addView(Row(this, "Scan network again", "Finds new cameras and IP changes; keeps names and order") { runScan { build() } })
        toFocus?.requestFocus()
    }

    private fun move(c: Cam, d: Int) {
        val i = Store.cams.indexOf(c); val j = i + d
        if (j !in Store.cams.indices) return
        Store.cams.removeAt(i); Store.cams.add(j, c); Store.save()
        focusIp = c.ip
        build()
    }

    private fun options(c: Cam) {
        focusIp = c.ip
        val items = listOf("Watch full screen", "Rename",
            if (c.enabled) "Hide from grid" else "Show in grid",
            "Move up", "Move down", "Stream quality", "Test connection", "Change IP address", "Remove")
        choose(c.name, items) { w ->
            when (w) {
                0 -> startActivity(Intent(this, FullscreenActivity::class.java).putExtra("ip", c.ip))
                1 -> ask("Rename camera", c.name) { n -> if (n.isNotBlank()) { c.name = n; Store.save(); build() } }
                2 -> { c.enabled = !c.enabled; Store.save(); build() }
                3 -> move(c, -1)
                4 -> move(c, 1)
                5 -> choose("Stream for ${c.name}", listOf("Automatic", "Always main stream (HD)", "Always substream (lighter)"),
                        when (c.stream) { MAIN -> 1; SUB -> 2; else -> 0 }) { s ->
                    c.stream = when (s) { 1 -> MAIN; 2 -> SUB; else -> null }; Store.save(); build()
                }
                6 -> testCamera(c) { build() }
                7 -> ask("New IP address for ${c.name}", c.ip, Kind.IP) { ip ->
                    if (Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(ip) && Store.cams.none { it !== c && it.ip == ip }) {
                        // Pointing it at an address by hand: forget the old identity, the new one is learned on connect.
                        c.ip = ip; c.key = ""; c.mac = ""; Store.save(); focusIp = ip; testCamera(c) { build() }
                    } else toast("Invalid or already used")
                }
                8 -> confirm("Remove ${c.name}?", "A later scan will find it again if it's still on the network. To keep it out of the grid, hide it instead.", "Remove") {
                    Store.cams.remove(c); Store.save(); build()
                }
            }
        }
    }
}
