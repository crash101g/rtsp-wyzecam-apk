package app.wyzecamrtsp

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

const val MAIN = "stream0"
const val SUB = "stream1"
const val DEFAULT_PORT = 322

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

/** One camera. [stream] null = use the grid / full-screen default. */
data class Cam(
    var name: String,
    var ip: String,
    var port: Int = DEFAULT_PORT,
    var enabled: Boolean = true,
    var stream: String? = null,
    var ok: Boolean = true,
    var error: String = "",
    var codec: String = "",
) {
    val lastOctet: String get() = ip.substringAfterLast('.')

    fun toJson() = JSONObject().apply {
        put("name", name); put("ip", ip); put("port", port); put("enabled", enabled)
        put("stream", stream ?: ""); put("ok", ok); put("error", error); put("codec", codec)
    }

    companion object {
        fun fromJson(o: JSONObject) = Cam(
            name = o.optString("name").ifBlank { "Camera " + o.optString("ip").substringAfterLast('.') },
            ip = o.optString("ip"),
            port = o.optInt("port", DEFAULT_PORT),
            enabled = o.optBoolean("enabled", true),
            stream = o.optString("stream").takeIf { it == MAIN || it == SUB },
            ok = o.optBoolean("ok", true),
            error = o.optString("error"),
            codec = o.optString("codec"),
        )
    }
}

/**
 * All settings and the camera list, kept in app-private SharedPreferences.
 * The RTSP password is stored too (unlike the desktop script) because re-typing it with a TV
 * remote every launch would be miserable; it never leaves the device except to the cameras.
 */
object Store {
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    var user = ""
    var pass = ""
    var port = DEFAULT_PORT
    var subnet = ""                 // "" = auto-detect
    var gridStream = SUB            // lighter substream is plenty for a grid
    var fullStream = MAIN
    var showNames = true
    var resyncMin = 10              // 0 = off
    var fullAudio = false
    var hintsShown = 0
    val cams = mutableListOf<Cam>()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("wyzecam", Context.MODE_PRIVATE)
        load()
    }

    val hasLogin get() = user.isNotBlank() && pass.isNotEmpty()
    val visible get() = cams.filter { it.enabled }

    fun load() {
        user = prefs.getString("user", "")!!
        pass = prefs.getString("pass", "")!!
        port = prefs.getInt("port", DEFAULT_PORT)
        subnet = prefs.getString("subnet", "")!!
        gridStream = prefs.getString("gridStream", SUB)!!
        fullStream = prefs.getString("fullStream", MAIN)!!
        showNames = prefs.getBoolean("showNames", true)
        resyncMin = prefs.getInt("resyncMin", 10)
        fullAudio = prefs.getBoolean("fullAudio", false)
        hintsShown = prefs.getInt("hintsShown", 0)
        cams.clear()
        runCatching {
            val a = JSONArray(prefs.getString("cams", "[]"))
            for (i in 0 until a.length()) cams += Cam.fromJson(a.getJSONObject(i))
        }
    }

    @Synchronized
    fun save() {
        val a = JSONArray().apply { cams.forEach { put(it.toJson()) } }
        prefs.edit()
            .putString("user", user).putString("pass", pass).putInt("port", port)
            .putString("subnet", subnet).putString("gridStream", gridStream)
            .putString("fullStream", fullStream).putBoolean("showNames", showNames)
            .putInt("resyncMin", resyncMin).putBoolean("fullAudio", fullAudio)
            .putInt("hintsShown", hintsShown)
            .putString("cams", a.toString())
            .apply()
        main.post { listeners.toList().forEach { it() } }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** Find a camera by IP, or create and append it. */
    fun addCam(ip: String): Cam {
        cams.firstOrNull { it.ip == ip }?.let { return it }
        return Cam(name = "Camera ${ip.substringAfterLast('.')}", ip = ip, port = port).also { cams += it }
    }

    fun streamFor(cam: Cam, full: Boolean) = cam.stream ?: if (full) fullStream else gridStream
}
