package app.wyzecamrtsp

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Network helpers + the camera scan (a port of the desktop script's `scan`). */
object Scanner {

    /** This device's IPv4 address and prefix length on the active network, e.g. ("192.168.1.20", 24). */
    fun localAddress(ctx: Context): Pair<String, Int>? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val nets = listOfNotNull(cm.activeNetwork) + cm.allNetworks.toList()
        for (n in nets) {
            val lp = cm.getLinkProperties(n) ?: continue
            for (la in lp.linkAddresses) {
                val a = la.address
                if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress)
                    return a.hostAddress!! to la.prefixLength
            }
        }
        return null
    }

    /** The hosts to scan: the configured subnet, or this device's own subnet (never wider than /22). */
    fun hosts(ctx: Context, subnet: String): Pair<String, List<String>>? {
        val (base, prefixIn) = if (subnet.isNotBlank()) {
            val p = subnet.trim().split('/')
            p[0] to (p.getOrNull(1)?.toIntOrNull() ?: 24)
        } else localAddress(ctx) ?: return null
        val prefix = if (prefixIn < 22 || prefixIn > 30) 24 else prefixIn
        val parts = base.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return null
        val ip = parts.fold(0L) { acc, v -> (acc shl 8) or (v.toLong() and 0xFF) }
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val net = ip and mask
        val count = 1L shl (32 - prefix)
        fun str(v: Long) = "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"
        val list = (1 until count - 1).map { str(net + it) }
        return "${str(net)}/$prefix" to list
    }

    fun portOpen(ip: String, port: Int, timeoutMs: Int = 600) = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs); true }
    } catch (e: Exception) { false }

    /**
     * Scans the subnet for RTSPS cameras, checks each with the saved login, and merges the result
     * into the saved list: names, order, hidden state and stream choices are kept, and cameras that
     * didn't answer stay in the list (marked offline) instead of vanishing.
     * Runs on the calling (background) thread; [progress] is called from that thread.
     */
    fun scan(ctx: Context, cancel: AtomicBoolean, addNew: Boolean = true, progress: (String) -> Unit): String {
        val port = Store.port
        val (net, hosts) = hosts(ctx, Store.subnet)
            ?: return "Couldn't detect your network. Set the subnet under Settings > Advanced."
        progress("Scanning $net (${hosts.size} addresses) for cameras on port $port…")

        val pool = Executors.newFixedThreadPool(64)
        val open = try {
            hosts.map { h -> pool.submit<String?> { if (!cancel.get() && portOpen(h, port)) h else null } }
                .mapNotNull { it.get() }
        } finally { pool.shutdown() }
        if (cancel.get()) return "Scan cancelled."
        progress("${open.size} device(s) with port $port open. Checking which are cameras…")

        val pool2 = Executors.newFixedThreadPool(16)
        val rtsp = try {
            open.map { h -> pool2.submit<Pair<String, String>?> { RtspProbe.options(h, port)?.let { h to it } } }
                .mapNotNull { it.get() }.toMap()
        } finally { pool2.shutdown() }
        if (cancel.get()) return "Scan cancelled."
        if (rtsp.isEmpty()) return "No RTSP cameras found on $net.\n\nIs RTSP turned on in the Wyze app " +
            "(camera → Settings → Advanced Settings → RTSP)? Is this TV on the same network as the cameras?"

        progress("Found ${rtsp.size} camera(s). Checking each with your login…")
        val pool3 = Executors.newFixedThreadPool(8)
        val probed = try {
            rtsp.keys.map { h -> h to pool3.submit<ProbeResult> { RtspProbe.describe(h, port, MAIN, Store.user, Store.pass) } }
                .associate { (h, f) -> h to f.get(30, TimeUnit.SECONDS) }.toMutableMap()
        } finally { pool3.shutdown() }
        val failed = probed.filterValues { !it.ok }.keys
        if (failed.isNotEmpty() && !cancel.get()) {
            progress("Retrying ${failed.size} camera(s) one at a time…")
            for (h in failed) probed[h] = RtspProbe.describe(h, port, MAIN, Store.user, Store.pass, 15000)
        }

        // Merge into the saved list (on whatever thread; Store.save() notifies listeners on main).
        val arp = arpTable()
        val found = rtsp.keys.sortedBy { it.substringAfterLast('.').toInt() }.map { ip ->
            val r = probed[ip]!!
            Found(ip, r.ok, r.error, r.codec, r.key, arp[ip] ?: "")
        }
        val moves = synchronized(Store) {
            merge(Store.cams, found, port, addNew).also { Store.save() }
        }
        lastMoved = moves.size
        val good = probed.values.count { it.ok }
        val bad = probed.filterValues { !it.ok }
        val sb = StringBuilder("Found ${rtsp.size} camera(s); $good working.")
        if (moves.isNotEmpty()) {
            sb.append("\n\nNew IP address (name and settings kept):")
            moves.forEach { sb.append("\n • $it") }
        }
        if (bad.isNotEmpty()) {
            sb.append("\n\nProblems:")
            bad.forEach { (ip, r) -> sb.append("\n • $ip: ${r.error}") }
            if (bad.values.any { it.error.startsWith("login") })
                sb.append("\n\nThe username/password must match the RTSP login set in the Wyze app.")
        }
        return sb.toString()
    }

    /** How many saved cameras the last scan found at a new IP address. */
    @Volatile var lastMoved = 0
        private set

    /** One camera that answered a scan. [key]/[mac] are empty when unknown. */
    data class Found(val ip: String, val ok: Boolean, val error: String = "", val codec: String = "",
                     val key: String = "", val mac: String = "")

    /**
     * Merges scan results into [cams] (in place) and returns "Name: old IP → new IP" for each
     * camera that moved. A camera is recognised by its MAC or TLS key first, so a new IP keeps its
     * name, order, hidden state and stream choice, even when two cameras swap addresses. Cameras
     * not recognised that way fall back to matching by IP (e.g. ones saved before keys were
     * recorded); unmatched ones are added (if [addNew]), and saved ones not found are marked offline.
     */
    fun merge(cams: MutableList<Cam>, found: List<Found>, port: Int, addNew: Boolean = true): List<String> {
        val match = IdentityHashMap<Cam, Found>()
        val taken = mutableSetOf<String>()      // found IPs already given to a saved camera
        // 1. Same camera by identity, wherever it is now.
        for (f in found) {
            val c = cams.firstOrNull { it !in match && sameDevice(it, f) } ?: continue
            match[c] = f; taken += f.ip
        }
        // 2. Same IP, unless the MACs prove it's a different device. (A different TLS key alone
        //    isn't proof: the camera may have made a new certificate, e.g. after a firmware reset.)
        for (f in found) {
            if (f.ip in taken) continue
            val c = cams.firstOrNull { it !in match && it.ip == f.ip &&
                !(it.mac.isNotEmpty() && f.mac.isNotEmpty() && !it.mac.equals(f.mac, true)) } ?: continue
            match[c] = f; taken += f.ip
        }
        val moves = mutableListOf<String>()
        for ((c, f) in match) {
            if (c.ip != f.ip) moves += "${c.name}: ${c.ip} → ${f.ip}"
            c.ip = f.ip; c.port = port; c.ok = f.ok; c.error = f.error
            if (f.codec.isNotEmpty()) c.codec = f.codec
            if (f.key.isNotEmpty()) c.key = f.key
            if (f.mac.isNotEmpty()) c.mac = f.mac
        }
        for (c in cams) if (c !in match) { c.ok = false; c.error = "not found in the last scan" }
        // 3. Cameras seen for the first time.
        if (addNew) for (f in found) if (f.ip !in taken) cams += Cam(name = "Camera ${f.ip.substringAfterLast('.')}", ip = f.ip,
            port = port, ok = f.ok, error = f.error, codec = f.codec, key = f.key, mac = f.mac)
        return moves
    }

    private fun sameDevice(c: Cam, f: Found) = when {
        c.mac.isNotEmpty() && f.mac.isNotEmpty() -> c.mac.equals(f.mac, true)
        else -> c.key.isNotEmpty() && c.key == f.key
    }

    /**
     * IP → MAC from the kernel's ARP table. Android 10+ blocks this for apps (it's empty there),
     * so the TLS key is what usually identifies a camera; older Android TVs also get the MAC.
     */
    fun arpTable(): Map<String, String> = runCatching {
        File("/proc/net/arp").readLines().drop(1).mapNotNull { line ->
            val p = line.trim().split(Regex("""\s+"""))
            if (p.size >= 4 && p[3].count { it == ':' } == 5 && p[3] != "00:00:00:00:00:00") p[0] to p[3].lowercase() else null
        }.toMap()
    }.getOrDefault(emptyMap())
}

/**
 * Finds cameras that changed IP address on its own: when a saved camera stops answering at its
 * IP (or a different camera answers there), a quiet background scan moves it to its new address,
 * recognised by its TLS key or MAC. The tile's next retry then connects there. It only updates
 * saved cameras and never adds new ones. Rate-limited: at most every 3 minutes, backing off to
 * hourly while a camera stays missing (e.g. it's unplugged).
 */
object Relocator {
    private const val MIN_GAP = 3 * 60_000L
    private const val MAX_GAP = 60 * 60_000L
    private var running = false
    private var gap = MIN_GAP
    private var lastRun = 0L

    @Synchronized
    fun request(ctx: Context, why: String) {
        val now = SystemClock.elapsedRealtime()
        if (running || !Store.hasLogin || (lastRun != 0L && now - lastRun < gap)) return
        running = true; lastRun = now
        val app = ctx.applicationContext
        thread(name = "relocate", isDaemon = true) {
            val msg = runCatching { Scanner.scan(app, AtomicBoolean(false), addNew = false) {} }
                .getOrElse { "failed: ${it.message}" }
            Log.i("Relocator", "background scan ($why): $msg")
            synchronized(this) {
                gap = if (Scanner.lastMoved > 0) MIN_GAP else (gap * 2).coerceAtMost(MAX_GAP)
                running = false
            }
        }
    }
}
