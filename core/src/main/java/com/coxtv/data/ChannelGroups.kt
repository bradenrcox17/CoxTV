package com.coxtv.data

import com.coxtv.data.db.Channel

/**
 * Folds copies of the same channel ("FR|M6 (SD)", "FR|M6 (HD)", "Fr: M6 FHD"...) into one
 * entry. The copies become fallback sources: if one won't play, the player tries the next.
 *
 * Same rules as the Roku app (DataService.brs) and the web player (coxstream.py):
 * names match after dropping the country prefix, quality/backup tags and punctuation;
 * channels whose (non-empty) guide ids disagree are kept apart (e.g. East/West feeds).
 */
object ChannelGroups {
    private val TAGS = setOf(
        "hd", "fhd", "uhd", "4k", "sd", "hevc", "h265", "h264", "50fps", "60fps",
        "1080p", "1080", "720p", "720", "2160p", "backup", "bkp", "alt", "b", "raw", "vip",
    )
    private val PREFIX = Regex("^([a-z]{2,4})\\s*[|:]\\s*")
    private val BRACKETS = Regex("[\\[(]([^\\])]*)[\\])]")
    private const val PUNCT = " -_.,:;!?/|'`\"~#@*"

    /** Grouping key for a channel name, or null when nothing is left to compare. */
    fun key(name: String): String? {
        var n = name.lowercase().trim()
        var prefix = ""
        PREFIX.find(n)?.let { m ->
            prefix = m.groupValues[1]
            n = n.substring(m.range.last + 1)
        }
        // "(HD)", "[FHD]", "(Backup)": brackets holding nothing but quality / backup tags.
        n = BRACKETS.replace(n) { m ->
            val words = m.groupValues[1].split(' ', '/', ',').filter { it.isNotBlank() }
            if (words.isNotEmpty() && words.all { it.replace(".", "") in TAGS }) " " else m.value
        }
        n = n.replace(":)", " ")
        val words = buildString { for (c in n) append(if (c in PUNCT) ' ' else c) }
            .split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (words.size > 1 && words.last().replace(".", "") in TAGS) words.removeAt(words.lastIndex)
        if (words.isEmpty()) return null
        return prefix + "|" + words.joinToString(" ")
    }

    /** Lower plays first: FHD, HD, untagged, UHD/4K (heavy), SD. */
    fun qualityRank(name: String): Int {
        val words = buildString { for (c in name.lowercase()) append(if (c.isLetterOrDigit()) c else ' ') }.split(' ')
        return when {
            "fhd" in words || "1080" in words || "1080p" in words -> 0
            "hd" in words || "720p" in words -> 1
            "uhd" in words || "4k" in words || "2160p" in words -> 3
            "sd" in words -> 4
            else -> 2
        }
    }

    class Result(
        /** One channel per group (hidden groups left out), in playlist order. */
        val channels: List<Channel>,
        /** Shown channel id -> all its sources, best first (the shown one first). */
        val sources: Map<String, List<Channel>>,
        /** Any channel id (including folded copies) -> the id of the channel shown for it. */
        val shownId: Map<String, String>,
        /** Shown channels whose group is hidden. */
        val hidden: List<Channel>,
    ) {
        val visibleIds: Set<String> by lazy { channels.mapTo(HashSet(channels.size * 2)) { it.id } }
    }

    private val keyCache = HashMap<String, String>()

    fun group(all: List<Channel>, hidden: Set<String>, combine: Boolean = true): Result {
        val groups = ArrayList<MutableList<Channel>>(all.size)
        if (combine) {
            // key -> sub-groups, split where guide ids disagree
            val byKey = HashMap<String, MutableList<Pair<String?, MutableList<Channel>>>>(all.size)
            synchronized(keyCache) {
                for (ch in all) {
                    val k = keyCache.getOrPut(ch.name) { key(ch.name) ?: "#${ch.name}" }
                    val subs = byKey.getOrPut(k) { ArrayList(1) }
                    val tvg = ch.epgIdRaw?.takeIf { it.isNotBlank() }
                    var index = subs.indexOfFirst { tvg == null || it.first == null || it.first == tvg }
                    if (index < 0) {
                        subs += tvg to ArrayList<Channel>(1).also { groups += it }
                        index = subs.lastIndex
                    } else if (subs[index].first == null && tvg != null) {
                        subs[index] = tvg to subs[index].second
                    }
                    subs[index].second += ch
                }
                if (keyCache.size > 200_000) keyCache.clear()
            }
        } else {
            all.mapTo(groups) { mutableListOf(it) }
        }

        val shown = ArrayList<Channel>(groups.size)
        val hiddenShown = ArrayList<Channel>()
        val sources = HashMap<String, List<Channel>>(groups.size * 2)
        val shownId = HashMap<String, String>(all.size * 2)
        for (members in groups) {
            val ordered = if (members.size == 1) members else members.sortedWith(
                compareBy<Channel>({ if (it.favorite) 0 else 1 }, { qualityRank(it.name) }, { it.sortOrder }),
            )
            val primary = ordered.first()
            val display = if (members.size == 1) primary else primary.copy(
                // Keep the playlist position of the group's first copy, and borrow a guide id
                // or favorite flag from any copy.
                sortOrder = members.minOf { it.sortOrder },
                epgId = primary.epgId ?: members.firstNotNullOfOrNull { it.epgId },
                favorite = members.any { it.favorite },
                favoritePosition = members.mapNotNull { it.favoritePosition }.minOrNull(),
            )
            sources[display.id] = if (members.size == 1) members else listOf(display) + ordered.drop(1)
            for (m in members) shownId[m.id] = display.id
            if (members.any { it.id in hidden }) hiddenShown += display else shown += display
        }
        shown.sortBy { it.sortOrder }
        hiddenShown.sortBy { it.name.lowercase() }
        return Result(shown, sources, shownId, hiddenShown)
    }
}
