package app.wyzecamrtsp

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    fun scan(ctx: Context, cancel: AtomicBoolean, progress: (String) -> Unit): String {
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
        synchronized(Store) {
            val seen = mutableSetOf<String>()
            for (ip in rtsp.keys.sortedBy { it.substringAfterLast('.').toInt() }) {
                val r = probed[ip]!!
                val c = Store.addCam(ip)
                c.port = port; c.ok = r.ok; c.error = r.error; if (r.codec.isNotEmpty()) c.codec = r.codec
                seen += ip
            }
            for (c in Store.cams) if (c.ip !in seen) { c.ok = false; c.error = "not found in the last scan" }
            Store.save()
        }
        val good = probed.values.count { it.ok }
        val bad = probed.filterValues { !it.ok }
        val sb = StringBuilder("Found ${rtsp.size} camera(s); $good working.")
        if (bad.isNotEmpty()) {
            sb.append("\n\nProblems:")
            bad.forEach { (ip, r) -> sb.append("\n • $ip: ${r.error}") }
            if (bad.values.any { it.error.startsWith("login") })
                sb.append("\n\nThe username/password must match the RTSP login set in the Wyze app.")
        }
        return sb.toString()
    }
}
