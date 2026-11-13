package app.wyzecamrtsp

import java.util.Base64

/** H.264 SPS + PPS (without start codes), e.g. caught from the live stream. */
class H264Params(val sps: ByteArray, val pps: ByteArray) {
    val sprop: String get() = b64(sps) + "," + b64(pps)
    val profileLevelId: String
        get() = if (sps.size >= 4) "%02x%02x%02x".format(sps[1].toInt() and 0xFF, sps[2].toInt() and 0xFF, sps[3].toInt() and 0xFF)
                else "42e01f"

    companion object {
        private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

        /** Parses a sprop-parameter-sets value; null unless it holds a usable SPS and PPS. */
        fun fromSprop(value: String?): H264Params? {
            if (value.isNullOrBlank()) return null
            val nals = value.split(',').mapNotNull { runCatching { Base64.getDecoder().decode(it.trim()) }.getOrNull() }
                .filter { it.isNotEmpty() }
            val sps = nals.firstOrNull { (it[0].toInt() and 0x1F) == 7 } ?: return null
            val pps = nals.firstOrNull { (it[0].toInt() and 0x1F) == 8 } ?: return null
            return H264Params(sps, pps)
        }
    }
}

/**
 * Makes a camera's SDP acceptable to Media3's RTSP player, which is much stricter than
 * ffmpeg/VLC. Any of these makes Media3 give up with the generic "io unspecified" error:
 *  - an H.264 track without `sprop-parameter-sets` (SPS/PPS) in its fmtp line,
 *  - more than one payload type on an m= line, malformed lines, fmtp parameters without '=',
 *  - an audio track it can't configure (e.g. AAC without its config).
 * [h264] supplies SPS/PPS caught from the stream when the camera leaves them out.
 */
object Sdp {
    private val LINE = Regex("""^([a-z])=\s?(.+)$""")
    private val ATTR = Regex("""^[\x21\x23-\x27\x2a\x2b\x2d\x2e\x30-\x39\x41-\x5a\x5e-\x7e]+(?::.*)?$""")
    private val BANDWIDTH = Regex("""^[\w-]+:\s?\d+$""")
    private val STATIC_AUDIO = mapOf(0 to "PCMU", 8 to "PCMA")
    private val SIMPLE_AUDIO = setOf("PCMA", "PCMU", "L8", "L16")
    private val FMTP_AUDIO = setOf("MPEG4-GENERIC", "MP4A-LATM", "OPUS", "AMR", "AMR-WB", "AC3")

    class Track(val type: String, val payloadType: Int, val encoding: String, val control: String?, val fmtp: Map<String, String>)

    /** The audio/video tracks of an SDP (first payload type of each m= line). */
    fun tracks(sdp: String): List<Track> = sections(sdp).second.mapNotNull { sec ->
        val parts = sec[0].substring(2).trim().split(Regex("\\s+"))
        val pt = parts.getOrNull(3)?.toIntOrNull() ?: return@mapNotNull null
        val attrs = sec.drop(1)
        Track(parts[0].lowercase(), pt, encoding(attrs, pt, parts[0].lowercase()),
            attrs.firstOrNull { it.startsWith("a=control:") }?.substringAfter("a=control:")?.trim(),
            fmtpParams(attrFor(attrs, "fmtp", pt)))
    }

    fun h264Track(sdp: String) = tracks(sdp).firstOrNull { it.type == "video" && it.encoding == "H264" }

    /** True if Media3 can configure the H.264 track as-is (it needs SPS/PPS in the SDP). */
    fun hasUsableSprop(t: Track) = H264Params.fromSprop(t.fmtp["sprop-parameter-sets"]) != null

    fun fix(sdp: String, h264: H264Params?, keepAudio: Boolean): String {
        val (session, medias) = sections(sdp)
        val out = mutableListOf("v=0")
        if (session.none { it.startsWith("o=") }) out += "o=- 0 0 IN IP4 127.0.0.1"
        if (session.none { it.startsWith("s=") }) out += "s=Camera"
        out += session.filterNot { it.startsWith("v=") }
        if (session.none { it.startsWith("t=") }) out += "t=0 0"
        for (m in medias) fixMedia(m, h264, keepAudio)?.let { out += it }
        return out.joinToString("\r\n", postfix = "\r\n")
    }

    private fun fixMedia(sec: List<String>, h264: H264Params?, keepAudio: Boolean): List<String>? {
        val parts = sec[0].substring(2).trim().split(Regex("\\s+"))
        if (parts.size < 4) return null
        val type = parts[0].lowercase()
        if (type != "video" && type != "audio") return null
        val pt = parts[3].toIntOrNull() ?: return null
        val attrs = sec.drop(1)
        val rtpmap = attrFor(attrs, "rtpmap", pt)
        if (rtpmap == null && pt >= 96) return null
        val enc = encoding(attrs, pt, type)
        var fmtp = fmtpParams(attrFor(attrs, "fmtp", pt))

        if (type == "audio") {
            val ok = keepAudio && (enc in SIMPLE_AUDIO || (enc in FMTP_AUDIO && fmtp.isNotEmpty()))
            if (!ok) return null
        }
        if (type == "video" && enc == "H264") {
            val have = H264Params.fromSprop(fmtp["sprop-parameter-sets"])
            val use = have ?: h264
            fmtp = LinkedHashMap(fmtp).apply {
                remove("sprop-parameter-sets")
                if (use != null) {
                    put("sprop-parameter-sets", use.sprop)
                    if (!containsKey("profile-level-id")) put("profile-level-id", use.profileLevelId)
                }
                if (!containsKey("packetization-mode")) put("packetization-mode", "1")
            }
        }

        val out = mutableListOf("m=${parts[0]} ${parts[1]} ${parts[2]} $pt")
        out += attrs.filterNot { it.startsWith("a=rtpmap:") || it.startsWith("a=fmtp:") }
        rtpmap?.let { out += it }
        if (fmtp.isNotEmpty()) out += "a=fmtp:$pt " + fmtp.entries.joinToString(";") { "${it.key}=${it.value}" }
        if (out.none { it.startsWith("a=control:") }) out += "a=control:*"
        return out
    }

    /** Splits into session lines and media sections, keeping only lines Media3 can parse. */
    private fun sections(sdp: String): Pair<List<String>, List<List<String>>> {
        val session = mutableListOf<String>()
        val medias = mutableListOf<MutableList<String>>()
        for (raw in sdp.split('\n')) {
            val m = LINE.matchEntire(raw.trim()) ?: continue
            val t = m.groupValues[1]; val v = m.groupValues[2].trim()
            if (v.isEmpty()) continue
            if (t == "a" && !ATTR.matches(v)) continue
            if (t == "b" && !BANDWIDTH.matches(v)) continue
            if (t == "m") { medias += mutableListOf("m=$v"); continue }
            (medias.lastOrNull() ?: session) += "$t=$v"
        }
        return session to medias
    }

    private fun attrFor(attrs: List<String>, name: String, pt: Int) =
        attrs.firstOrNull { it.startsWith("a=$name:$pt ") }

    private fun encoding(attrs: List<String>, pt: Int, type: String) =
        attrFor(attrs, "rtpmap", pt)?.substringAfter(' ')?.substringBefore('/')?.trim()?.uppercase()
            ?: (if (type == "audio") STATIC_AUDIO[pt] else null) ?: ""

    /** fmtp parameters as a map; drops anything Media3 would choke on (no '=', duplicates). */
    private fun fmtpParams(line: String?): Map<String, String> {
        if (line == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        line.substringAfter(' ', "").split(';').forEach {
            val p = it.trim(); val i = p.indexOf('=')
            if (i > 0) out.putIfAbsent(p.substring(0, i).trim(), p.substring(i + 1).trim())
        }
        return out
    }
}
