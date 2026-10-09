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

    private val US_GROUP = Regex("""^\s*USA?\b""", RegexOption.IGNORE_CASE)
    private val US_NAME = Regex("""^\s*(USA?\s*[:|\-]|\(USA?\))""", RegexOption.IGNORE_CASE)
    private val US_SERVICE = Regex("""espn\s*\+|espn\s*play|flo\s*sports""", RegexOption.IGNORE_CASE)
    private val COUNTRY_PREFIX = Regex("""^\s*\(?([A-Za-z]{2,4})\)?\s*[:|]""")

    /**
     * A US channel ("Sports on now" lists only these; search puts them first). The provider
     * files them under "USA | ..." groups, ESPN+ and Flo Sports included though their names
     * don't say USA. A group starting with another country's code ("CA | ...") is never US,
     * whatever the name says; otherwise names starting "USA:" / "US:" / "(US)" are, and so are
     * ESPN+, ESPN Play and Flo Sports unless the name starts with another country's code
     * ("Arg: ESPN"). Same rule as the stream server (is_us_channel) and Roku (isUsChannel).
     */
    fun isUs(name: String, group: String): Boolean {
        if (US_GROUP.containsMatchIn(group)) return true
        COUNTRY_PREFIX.find(group)?.let { if (it.groupValues[1].uppercase() !in US_CODES) return false }
        if (US_NAME.containsMatchIn(name)) return true
        if (US_SERVICE.containsMatchIn("$name $group")) {
            COUNTRY_PREFIX.find(name)?.let { if (it.groupValues[1].uppercase() !in US_CODES) return false }
            return true
        }
        return false
    }

    private val US_CODES = setOf("US", "USA")

    // Search order (same as the stream server's search_tier / name_match_rank and Roku search()).
    private val SPANISH = Regex("""deportes|espa[nñ]ol|spanish|\blatin|univision|telemundo|tudn|unim[aá]s|galavisi[oó]n""", RegexOption.IGNORE_CASE)
    private val SEARCH_PREFIX = Regex("""^\s*(?:\([^)]*\)\s*|[a-z0-9+]{1,6}\s*[:|]\s*)+""", RegexOption.IGNORE_CASE)
    private val SEARCH_TAGS = Regex("""\[[^\]]*\]|\b(?:hd|fhd|uhd|4k|sd|hevc|backup|bkp|raw|vip|\d{3,4}p)\b""", RegexOption.IGNORE_CASE)
    private val EVENT_NAME = Regex(""":|\s(?:vs\.?|v|at|@)\s""", RegexOption.IGNORE_CASE)
    private val NOT_ALNUM = Regex("[^a-z0-9]")
    private val WORD = Regex("[a-z0-9]+")

    /** Search tier: US channels, then US Spanish-language ones (ESPN Deportes...), then other countries. */
    fun searchTier(name: String, group: String): Int =
        (if (isUs(name, group)) 0 else 2) + (if (SPANISH.containsMatchIn("$name $group")) 1 else 0)

    /**
     * How well a channel name matches a search, lower first: 0 the name (without its "USA:" /
     * "(US)" / "Tubi:" prefix and quality tags) is the search, 1 it starts with it, 2 a word
     * starts with it ("ESPN SEC Network"), 3 anywhere; event listings ("SEC Network +: LSU vs.
     * Kentucky") +4. Second value: name length (shorter first).
     */
    fun nameMatchRank(name: String, query: String): Pair<Int, Int> {
        val key = query.lowercase().replace(NOT_ALNUM, "")
        val base = name.replace(SEARCH_PREFIX, "").replace(SEARCH_TAGS, " ")
        val k = base.lowercase().replace(NOT_ALNUM, "")
        val words = WORD.findAll(base.lowercase()).map { it.value }.toList()
        var rank = when {
            k == key -> 0
            k.startsWith(key) -> 1
            (1 until words.size).any { words.subList(it, words.size).joinToString("").startsWith(key) } -> 2
            else -> 3
        }
        if (EVENT_NAME.containsMatchIn(base)) rank += 4
        return rank to k.length
    }

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
