package com.coxtv.data.remote

import android.util.Xml
import com.coxtv.data.db.ProgramEntity
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** Streaming XMLTV parser; never holds the whole document in memory. */
object XmltvParser {

    /**
     * @param onChannels called once with every `<channel id>` and its display names, before the first
     *   programme (XMLTV puts channels first). Returns the set of channel ids worth keeping, or null for all.
     */
    fun parse(
        input: InputStream,
        onChannels: (Map<String, List<String>>) -> Set<String>?,
        onProgramme: (ProgramEntity) -> Unit,
    ) {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        runCatching { p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        p.setInput(input, null)

        val channels = LinkedHashMap<String, List<String>>()
        var wanted: Set<String>? = null
        var channelsDone = false

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> readChannel(p, channels)
                    "programme" -> {
                        if (!channelsDone) {
                            wanted = onChannels(channels)
                            channelsDone = true
                        }
                        readProgramme(p, wanted)?.let(onProgramme)
                    }
                }
            }
            event = p.next()
        }
        if (!channelsDone) onChannels(channels)
    }

    private fun readChannel(p: XmlPullParser, out: MutableMap<String, List<String>>) {
        val id = p.getAttributeValue(null, "id")
        val depth = p.depth
        val names = ArrayList<String>(2)
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && p.depth == depth)) break
            if (e == XmlPullParser.START_TAG && p.name == "display-name") text(p)?.let { names += it }
        }
        if (!id.isNullOrBlank()) out[id] = names
    }

    private fun readProgramme(p: XmlPullParser, wanted: Set<String>?): ProgramEntity? {
        val channel = p.getAttributeValue(null, "channel")
        val start = p.getAttributeValue(null, "start")?.let(::parseXmltvTime) ?: -1L
        val stop = p.getAttributeValue(null, "stop")?.let(::parseXmltvTime) ?: -1L
        val keep = channel != null && (wanted == null || channel in wanted) && start > 0 && stop > start
        val depth = p.depth
        var title: String? = null
        var desc: String? = null
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && p.depth == depth)) break
            if (keep && e == XmlPullParser.START_TAG) {
                when (p.name) {
                    "title" -> if (title == null) title = text(p)
                    "desc" -> if (desc == null) desc = text(p)
                }
            }
        }
        if (!keep) return null
        return ProgramEntity(
            epgId = channel,
            startMs = start,
            endMs = stop,
            title = cleanTitle(title ?: "Untitled"),
            description = desc?.take(300),
        )
    }

    /** Providers append Unicode superscript tags ("ᴸᶦᵛᵉ", "ᴺᵉʷ") that TV fonts may not draw. */
    private fun cleanTitle(t: String): String =
        t.replace("ᴸᶦᵛᵉ", "LIVE").replace("ᴺᵉʷ", "NEW").trim()

    private fun text(p: XmlPullParser): String? =
        runCatching { p.nextText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/** Parses XMLTV timestamps like `20261005193000 +0100` to epoch millis, or -1. */
internal fun parseXmltvTime(raw: String): Long {
    val s = raw.trim()
    var i = 0
    while (i < s.length && s[i].isDigit()) i++
    if (i < 12) return -1
    fun num(a: Int, b: Int) = s.substring(a, b).toInt()
    val year = num(0, 4)
    val month = num(4, 6)
    val day = num(6, 8)
    val hour = num(8, 10)
    val minute = num(10, 12)
    val second = if (i >= 14) num(12, 14) else 0
    var offsetMin = 0
    val rest = s.substring(i).trim()
    if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
        val hh = rest.substring(1, 3).toIntOrNull() ?: 0
        val mm = rest.substring(3, 5).toIntOrNull() ?: 0
        offsetMin = (hh * 60 + mm) * (if (rest[0] == '-') -1 else 1)
    }
    val days = daysFromCivil(year, month, day)
    return (days * 86_400L + hour * 3_600L + minute * 60L + second - offsetMin * 60L) * 1000L
}

/** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
    val y = if (m <= 2) y0 - 1 else y0
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468L
}
