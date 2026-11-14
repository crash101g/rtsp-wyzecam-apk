package app.wyzecamrtsp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView

/** Every setting as a big D-pad row. Values are chosen from lists wherever possible, so typing is rare. */
class SettingsActivity : Activity() {
    private lateinit var list: android.widget.LinearLayout
    private val refresh = { build() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = column()
        setContentView(ScrollView(this).apply { addView(list); isFillViewport = true })
        build()
        Store.addListener(refresh)
        if (intent.getBooleanExtra("login", false)) editUser(thenPassword = true)
    }

    override fun onDestroy() { Store.removeListener(refresh); super.onDestroy() }

    override fun onResume() { super.onResume(); build() }

    private fun section(title: String) = list.addView(text(title, 15f, dim = true).apply {
        setPadding(dp(6), dp(18), 0, dp(8)); isAllCaps = true; letterSpacing = 0.08f
    })

    private fun row(title: String, value: String = "", onClick: (Row) -> Unit) = Row(this, title, value, onClick).also { list.addView(it) }

    private fun build() {
        val focusedTitle = (currentFocus as? Row)?.title
        list.removeAllViews()
        list.addView(text("Settings", 32f))

        section("Login")
        row("Set up from your phone", "Scan a QR code and type the login, camera names and more on your phone") {
            startActivity(Intent(this, PhoneSetupActivity::class.java))
        }
        row("RTSP username", Store.user.ifEmpty { "Not set" }) { editUser(false) }
        row("RTSP password", if (Store.pass.isEmpty()) "Not set" else "•".repeat(Store.pass.length.coerceAtMost(12))) { editPassword() }
        row("Test login", "Try the login on the first camera") {
            val c = Store.visible.firstOrNull() ?: Store.cams.firstOrNull()
            if (c == null) message("No cameras", "Scan the network or add a camera first.") else testCamera(c)
        }

        section("Cameras")
        row("Scan network for cameras", Store.cams.size.let { if (it == 0) "No cameras saved yet" else "$it camera(s) saved — names and order are kept" }) {
            runScan { build() }
        }
        row("Manage cameras", "Rename, hide, reorder, stream quality, remove") {
            startActivity(Intent(this, CamerasActivity::class.java))
        }
        row("Add a camera by IP address") {
            ask("Camera IP address", ipPrefix(), Kind.IP, "e.g. 192.168.1.243") { ip ->
                if (!Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(ip)) { toast("That isn't an IP address"); return@ask }
                val c = Store.addCam(ip); Store.save(); testCamera(c) { build() }
            }
        }

        section("Picture")
        row("Grid quality", if (Store.gridStream == SUB) "Substream — lighter, recommended for grids" else "Main stream (HD) — heavy with many cameras") {
            choose("Grid quality", listOf("Substream (lighter, recommended)", "Main stream (HD)"), if (Store.gridStream == SUB) 0 else 1) {
                Store.gridStream = if (it == 0) SUB else MAIN; Store.save()
            }
        }
        row("Full-screen quality", if (Store.fullStream == MAIN) "Main stream (HD)" else "Substream") {
            choose("Full-screen quality", listOf("Main stream (HD)", "Substream (lighter)"), if (Store.fullStream == MAIN) 0 else 1) {
                Store.fullStream = if (it == 0) MAIN else SUB; Store.save()
            }
        }
        row("Grid video size", if (Store.gridFill) "Zoom to fill — no black bars, edges cropped" else "Fit — whole picture, may show black bars") {
            choose("Grid video size", listOf("Fit (whole picture, may show black bars)", "Zoom to fill (no black bars, edges cropped)"), if (Store.gridFill) 1 else 0) {
                Store.gridFill = it == 1; Store.save()
            }
        }
        row("Camera names on tiles", if (Store.showNames) "On" else "Off") {
            Store.showNames = !Store.showNames; Store.save()
        }
        row("Sound in full screen", if (Store.fullAudio) "On" else "Off") {
            Store.fullAudio = !Store.fullAudio; Store.save()
        }
        val resync = listOf(0, 5, 10, 15, 30, 60)
        row("Reconnect to stay live", if (Store.resyncMin == 0) "Off" else "Every ${Store.resyncMin} minutes (staggered)") {
            choose("Reconnect each camera every…", resync.map { if (it == 0) "Off" else "$it minutes" }, resync.indexOf(Store.resyncMin)) {
                Store.resyncMin = resync[it]; Store.save()
            }
        }

        section("Advanced")
        row("RTSP port", "${Store.port}" + if (Store.port == DEFAULT_PORT) " (Wyze default)" else "") {
            ask("RTSP port", "${Store.port}", Kind.NUMBER) { v ->
                v.toIntOrNull()?.takeIf { it in 1..65535 }?.let { p ->
                    Store.port = p; Store.cams.forEach { it.port = p }; Store.save()
                } ?: toast("Not a valid port")
            }
        }
        val auto = Scanner.hosts(this, "")?.first ?: "unknown"
        row("Subnet to scan", Store.subnet.ifEmpty { "Automatic ($auto)" }) {
            choose("Subnet to scan", listOf("Automatic ($auto)", "Enter a subnet…"), if (Store.subnet.isEmpty()) 0 else 1) {
                if (it == 0) { Store.subnet = ""; Store.save() }
                else ask("Subnet (e.g. 192.168.1.0/24)", Store.subnet.ifEmpty { auto }, Kind.IP) { s ->
                    if (Regex("""\d{1,3}(\.\d{1,3}){3}(/\d{1,2})?""").matches(s)) { Store.subnet = s; Store.save() } else toast("Not a valid subnet")
                }
            }
        }
        row("About", "WyzeCam RTSP for Google TV ${packageManager.getPackageInfo(packageName, 0).versionName} · based on github.com/crash101g/wyzecam-rtsp") {
            message("About", "Shows your Wyze cameras' built-in RTSP streams in a live grid. Video goes straight from " +
                "each camera to this TV over your home network — no cloud, no Wyze account.\n\n" +
                "Only works with cameras that have RTSP turned on (Wyze app → camera → Settings → Advanced Settings → RTSP). " +
                "Tested by the original project with Cam v3, Cam Pan v3 and Cam v4 (RTSP firmware).")
        }

        // Keep the focus on the row the user was on.
        (0 until list.childCount).map { list.getChildAt(it) }.filterIsInstance<Row>()
            .let { rows -> (rows.firstOrNull { it.title == focusedTitle } ?: rows.firstOrNull())?.requestFocus() }
    }

    private fun editUser(thenPassword: Boolean) {
        ask("RTSP username (from the Wyze app)", Store.user) { u ->
            Store.user = u; Store.save()
            if (thenPassword) editPassword()
        }
    }

    private fun editPassword() {
        ask("RTSP password", Store.pass, Kind.PASSWORD) { p ->
            Store.pass = p; Store.save()
            if (Store.cams.isEmpty() && Store.hasLogin) confirm("Find your cameras?", "Scan the network for cameras now?", "Scan") { runScan { build() } }
        }
    }
}
