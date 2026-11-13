package app.wyzecamrtsp

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

fun Context.text(s: String, sp: Float = 18f, dim: Boolean = false) = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(getColor(if (dim) R.color.text_dim else R.color.text))
}

/** A big, focusable settings row: title + current value. Focus is obvious from across the room. */
class Row(ctx: Context, title: String, value: String = "", private val onClick: (Row) -> Unit) : LinearLayout(ctx) {
    private val titleView = ctx.text(title, 20f)
    private val valueView = ctx.text(value, 16f, dim = true)

    init {
        orientation = VERTICAL
        isFocusable = true
        isClickable = true
        setBackgroundResource(R.drawable.row_bg)
        setPadding(ctx.dp(22), ctx.dp(14), ctx.dp(22), ctx.dp(14))
        addView(titleView); addView(valueView)
        valueView.visibility = if (value.isEmpty()) GONE else VISIBLE
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = ctx.dp(8) }
        setOnClickListener { onClick(this) }
        setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) 1.02f else 1f).scaleY(if (f) 1.02f else 1f).setDuration(120).start() }
    }

    var value: String
        get() = valueView.text.toString()
        set(v) { valueView.text = v; valueView.visibility = if (v.isEmpty()) GONE else VISIBLE }

    var title: String
        get() = titleView.text.toString()
        set(v) { titleView.text = v }
}

/** A focusable pill button for toolbars / empty-state panels. */
fun Context.pill(label: String, onClick: () -> Unit) = Button(this).apply {
    text = label
    isAllCaps = false
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    setTextColor(getColor(R.color.text))
    setBackgroundResource(R.drawable.row_bg)
    setPadding(dp(22), dp(10), dp(22), dp(10))
    minHeight = 0; minimumHeight = 0
    setOnClickListener { onClick() }
    setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(120).start() }
}

fun Activity.dialog() = AlertDialog.Builder(this)

/** Pick one of [items]; the current one is pre-focused so OK keeps it. */
fun Activity.choose(title: String, items: List<String>, selected: Int = -1, onPick: (Int) -> Unit) {
    val d = dialog().setTitle(title)
    if (selected >= 0) d.setSingleChoiceItems(items.toTypedArray(), selected) { dlg, which -> dlg.dismiss(); onPick(which) }
    else d.setItems(items.toTypedArray()) { _, which -> onPick(which) }
    d.setNegativeButton("Cancel", null).show()
}

fun Activity.message(title: String, msg: String, onOk: (() -> Unit)? = null) {
    dialog().setTitle(title).setMessage(msg).setPositiveButton("OK") { _, _ -> onOk?.invoke() }.show()
}

fun Activity.confirm(title: String, msg: String, yes: String = "Yes", onYes: () -> Unit) {
    dialog().setTitle(title).setMessage(msg).setPositiveButton(yes) { _, _ -> onYes() }
        .setNegativeButton("Cancel", null).show()
}

enum class Kind { TEXT, PASSWORD, NUMBER, IP }

/**
 * Text entry tuned for a remote: the on-screen keyboard opens straight away, the remote's
 * number keys type digits, and the keyboard's Done/Enter key saves. For IP addresses the
 * network prefix (e.g. "192.168.1.") is pre-filled so usually only the last number is typed.
 */
fun Activity.ask(title: String, initial: String, kind: Kind = Kind.TEXT, hint: String = "", onDone: (String) -> Unit) {
    val edit = EditText(this).apply {
        setText(initial)
        setSelection(initial.length)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        isSingleLine = true
        this.hint = hint
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        inputType = when (kind) {
            Kind.TEXT -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            Kind.PASSWORD -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            Kind.NUMBER -> InputType.TYPE_CLASS_NUMBER
            Kind.IP -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), dp(0))
        addView(edit)
        if (kind == Kind.PASSWORD) addView(text("Shown while typing so you can check it with the remote.", 14f, dim = true))
    }
    val d = dialog().setTitle(title).setView(box)
        .setPositiveButton("Save") { _, _ -> onDone(edit.text.toString().trim().let { if (kind == Kind.PASSWORD) edit.text.toString() else it }) }
        .setNegativeButton("Cancel", null)
        .create()
    edit.setOnEditorActionListener { _, action, ev ->
        if (action == EditorInfo.IME_ACTION_DONE || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); true
        } else false
    }
    d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    d.show()
    edit.requestFocus()
}

/** Prefix like "192.168.1." for the IP entry box. */
fun Context.ipPrefix(): String = (Store.subnet.ifBlank { Scanner.localAddress(this)?.first ?: "192.168.1.0" })
    .substringBefore('/').substringBeforeLast('.') + "."

/** Runs a network scan with a progress dialog; Back cancels. */
fun Activity.runScan(onDone: () -> Unit = {}) {
    if (!Store.hasLogin) {
        message("Login needed", "Enter the RTSP username and password first (Settings, or set up from your phone).")
        return
    }
    val cancel = AtomicBoolean(false)
    val msg = text("Starting…", 18f).apply { setPadding(dp(24), dp(16), dp(24), dp(16)) }
    val d = dialog().setTitle("Scanning for cameras").setView(msg)
        .setNegativeButton("Cancel") { _, _ -> cancel.set(true) }
        .setOnCancelListener { cancel.set(true) }
        .show()
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    thread(isDaemon = true) {
        val result = runCatching { Scanner.scan(this, cancel) { p -> runOnUiThread { msg.text = p } } }
            .getOrElse { "Scan failed: ${it.message}" }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            runCatching { d.dismiss() }
            if (!cancel.get()) message("Scan finished", result) { onDone() } else onDone()
        }
    }
}

/** Probe one camera with the current login and report the result. */
fun Activity.testCamera(cam: Cam, onDone: () -> Unit = {}) {
    val d = dialog().setTitle("Testing ${cam.name}").setMessage("Connecting to ${cam.ip}…").show()
    thread(isDaemon = true) {
        val r = RtspProbe.describe(cam.ip, cam.port, Store.streamFor(cam, true), Store.user, Store.pass)
        cam.ok = r.ok; cam.error = r.error
        if (r.codec.isNotEmpty()) cam.codec = r.codec
        Store.save()
        runOnUiThread {
            runCatching { d.dismiss() }
            if (!isFinishing) message(cam.name,
                if (r.ok) "Working ✓\n\n${cam.ip}:${cam.port}  ${r.codec}\n${r.server}".trim() else "Not working:\n${r.error}") { onDone() }
        }
    }
}

/** Lays children out in a vertical list inside a padded column (used by Settings / Cameras). */
fun Context.column() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(56), dp(32), dp(56), dp(32))
    gravity = Gravity.TOP
}

fun View.onKey(code: Int, action: () -> Unit) = setOnKeyListener { _, k, e ->
    if (k == code && e.action == KeyEvent.ACTION_DOWN) { action(); true } else false
}
