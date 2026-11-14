package app.wyzecamrtsp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.concurrent.thread

/**
 * One live camera: a player, its name label, a status overlay and the focus/selection border.
 * Each tile has its own connection and reconnect loop, so one bad camera never freezes the others.
 * A dark grey tile = no live video right now; it keeps retrying on its own.
 */
@OptIn(UnstableApi::class)
@SuppressLint("ViewConstructor")
class CameraTile(ctx: Context, var cam: Cam, private val full: Boolean) : FrameLayout(ctx) {
    private val ui = Handler(Looper.getMainLooper())
    private val playerView = PlayerView(ctx).apply {
        useController = false
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShutterBackgroundColor(Color.BLACK)
        setKeepContentOnPlayerReset(true)
    }
    private val status = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (full) 22f else 15f)
        gravity = Gravity.CENTER
        setBackgroundColor(ctx.getColor(R.color.tile_offline))
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    private val label = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (full) 22f else 15f)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = dp(6).toFloat() }
        maxLines = 1
    }
    private val number = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(7), dp(1), dp(7), dp(1))
        background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = dp(6).toFloat() }
    }
    private val border = View(ctx).apply { setBackgroundResource(R.drawable.tile_border); isDuplicateParentStateEnabled = true }

    private var player: ExoPlayer? = null
    private var proxy: RtspsProxy? = null
    private var running = false
    private var gotFrame = false
    @Volatile private var lastFrameAt = 0L
    private var startedAt = 0L
    private var failures = 0
    private var retryDelay = 2000L
    private var resyncAt = 0L
    private var connectGen = 0

    /** Stream actually in use (may fall back to the substream if the main one keeps failing). */
    var activeStream: String = MAIN
        private set
    private var fellBack = false
    var onStatus: ((String) -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = !full
        isFocusableInTouchMode = false
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(10), dp(10), dp(10), dp(10))
        })
        addView(number, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        addView(border, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        refreshLabel()
        setStatus("Connecting…")
    }

    fun setIndex(i: Int) {
        number.text = "${i + 1}"
        updateChrome()
    }

    /**
     * True while the user is moving around the grid with the remote: only then are the
     * selection border and the 1–9 numbers shown, so they don't sit on top of the video.
     */
    var navigating = false
        set(v) { field = v; updateChrome() }

    private fun updateChrome() {
        border.visibility = if (navigating || isActivated) VISIBLE else INVISIBLE
        number.visibility = if (!full && Store.showNames && navigating) VISIBLE else GONE
    }

    fun refreshLabel() {
        val sub = if (fellBack) "  (substream)" else ""
        label.text = cam.name + sub
        label.visibility = if (Store.showNames || full) VISIBLE else GONE
        updateChrome()
    }

    /** Yellow "moving" border used by Rearrange. */
    fun setMoving(on: Boolean) { isActivated = on; refreshDrawableState(); updateChrome() }

    private fun setStatus(text: String?) {
        if (text == null) status.visibility = GONE
        else { status.text = text; status.visibility = VISIBLE }
        text?.let { onStatus?.invoke(it) }
    }

    // ---------------------------------------------------------------- playback

    fun start(delayMs: Long = 0) {
        running = true
        ui.removeCallbacksAndMessages(null)
        ui.postDelayed({ connect() }, delayMs)
        ui.postDelayed(watchdog, 2000)
    }

    fun stop() {
        running = false
        ui.removeCallbacksAndMessages(null)
        release()
    }

    /** Drop and re-open the stream (resync to live). */
    fun reconnect(msg: String? = "Reconnecting…") {
        if (!running) return
        release()
        if (msg != null) { gotFrame = false; setStatus(msg) }
        ui.removeCallbacksAndMessages(null)
        ui.postDelayed({ connect() }, 300)
        ui.postDelayed(watchdog, 2000)
    }

    private fun connect() {
        if (!running) return
        release()
        if (!Store.hasLogin) { setStatus("${cam.name}\nNo RTSP login set — open Settings"); return }
        val wanted = Store.streamFor(cam, full)
        activeStream = if (fellBack) SUB else wanted
        if (!gotFrame) setStatus("${cam.name}\nConnecting…")
        lastFrameAt = 0L
        val ip = cam.ip; val port = cam.port; val user = Store.user; val pass = Store.pass
        val stream = activeStream
        val audio = full && Store.fullAudio
        val key = cam.key
        val gen = ++connectGen
        // Network work (local socket, checking the stream with the camera) stays off the UI thread.
        thread(isDaemon = true) {
            val px = runCatching { RtspsProxy(ip, port, user, pass, keepAudio = audio, expectKey = key) }.getOrNull()
            val err = px?.prepare(stream)
            ui.post {
                if (!running || gen != connectGen || px == null) {
                    px?.close(); if (running && gen == connectGen) scheduleRetry("proxy failed"); return@post
                }
                if (err != null) { px.close(); scheduleRetry(err); return@post }
                learnKey(px.seenKey)
                proxy = px
                startPlayer(px.url(stream))
            }
        }
    }

    private fun startPlayer(url: String) {
        val ctx = context
        startedAt = SystemClock.elapsedRealtime()
        val renderers = DefaultRenderersFactory(ctx)
            .setEnableDecoderFallback(true)          // if the hardware decoders are all busy, try the next one
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 2000, 250, 250) // stay close to live
            .build()
        val p = ExoPlayer.Builder(ctx, renderers).setLoadControl(load).build()
        val audio = full && Store.fullAudio
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audio).build()
        p.volume = if (audio) 1f else 0f
        p.setVideoFrameMetadataListener { _, _, _, _ -> lastFrameAt = SystemClock.elapsedRealtime() }
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                gotFrame = true; failures = 0; retryDelay = 2000L
                setStatus(null)
                if (!cam.ok) { cam.ok = true; cam.error = "" }
                proxy?.let { learnKey(it.seenKey) }
            }
            override fun onPlayerError(error: PlaybackException) {
                val px = proxy
                val why = when {
                    px?.loginRejected == true -> "login rejected — check username/password"
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "camera not responding"
                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ->
                        "TV ran out of video decoders — try fewer cameras or the substream"
                    px?.lastError?.isNotEmpty() == true -> px.lastError
                    else -> rootCause(error)?.let { "stream error: $it" }
                        ?: error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
                }
                android.util.Log.w("CameraTile", "${cam.ip}: $why", error)
                scheduleRetry(why)
            }
        })
        playerView.player = p
        val src = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)                  // everything over the one TLS connection
            .setTimeoutMs(10_000)
            .createMediaSource(MediaItem.fromUri(url))
        p.setMediaSource(src)
        p.playWhenReady = true
        p.prepare()
        player = p
        scheduleResync()
    }

    /** The innermost readable message, e.g. "missing sprop parameter" instead of "io unspecified". */
    private fun rootCause(e: Throwable): String? {
        var c: Throwable? = e.cause
        var msg: String? = null
        while (c != null && c !== c.cause) {
            c.message?.takeIf { it.isNotBlank() }?.let { msg = it.substringAfterLast("Exception: ") }
            c = c.cause
        }
        return msg?.take(90)
    }

    /** Remember the camera's TLS key the first time it's seen (cameras saved before keys existed). */
    private fun learnKey(seen: String) {
        if (seen.isNotEmpty() && cam.key.isEmpty() && Store.cams.none { it !== cam && it.key == seen }) {
            cam.key = seen; Store.save()
        }
    }

    private fun scheduleRetry(why: String) {
        if (!running) return
        release()
        gotFrame = false
        failures++
        // Not at its address any more? It may have a new IP from DHCP: look for it in the background.
        if (why == WRONG_CAMERA || failures >= 2 && LOST.any { why.contains(it, ignoreCase = true) })
            Relocator.request(context, "${cam.name} at ${cam.ip}: $why")
        // Like the desktop script: a main stream that keeps dropping falls back to the substream.
        if (full && !fellBack && activeStream == MAIN && failures >= 3 && !why.startsWith("login")) {
            fellBack = true
            refreshLabel()
        }
        val secs = retryDelay / 1000
        setStatus("${cam.name}\n$why\nRetrying in ${secs}s…")
        cam.error = why
        ui.removeCallbacksAndMessages(null)
        ui.postDelayed({ connect() }, retryDelay)
        ui.postDelayed(watchdog, retryDelay + 2000)
        retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
    }

    private fun scheduleResync() {
        val min = Store.resyncMin
        resyncAt = if (min > 0) SystemClock.elapsedRealtime() + min * 60_000L + resyncOffset else 0L
    }

    /** Set by the grid so the periodic resyncs of different cameras are staggered. */
    var resyncOffset = 0L

    /** Every 2 s: reconnect a camera whose video has frozen, and do the periodic resync. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val p = player
            if (p != null) {
                val last = lastFrameAt
                when {
                    last == 0L && now - startedAt > FIRST_FRAME_MS -> { scheduleRetry("no video from camera"); return }
                    last != 0L && now - last > STALL_MS -> { gotFrame = false; reconnect("Video stalled — reconnecting…"); return }
                    resyncAt in 1..now -> { reconnect(null); return }   // keep the last picture up
                }
            }
            ui.postDelayed(this, 2000)
        }
    }

    private fun release() {
        player?.let { p -> playerView.player = null; p.release() }
        player = null
        proxy?.close()
        proxy = null
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val STALL_MS = 8_000L
        const val FIRST_FRAME_MS = 25_000L
        /** Errors meaning nothing usable answered at the camera's IP. */
        private val LOST = listOf("not responding", "refused the connection", "timed out", "unreachable", "no route")
    }
}
