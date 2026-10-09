package com.coxtv.data

import java.util.Calendar
import java.util.TimeZone

/**
 * Finds live games in the guide for "Sports on now". Same rules as the Roku app
 * (DataService.brs) and the web player (coxstream.py).
 */
object Sports {
    /** League display order. */
    val LEAGUES = listOf(
        "NFL", "College Football", "NBA", "WNBA", "College Basketball", "MLB", "NHL", "Soccer",
        "Fighting", "Racing", "Golf", "Tennis", "Cricket", "Rugby", "College Sports", "Football", "Basketball", "Baseball", "Hockey", "Other",
    )

    private val EVENT = Regex("\\s(vs\\.?|v\\.?|versus|@)\\s")
    private val AT = Regex("\\sat\\s")
    private val STUDIO = listOf(
        "highlight", "recap", "preview", "classic", "replay", "rewind", "postgame", "post-game", "post game",
        "pregame", "pre-game", "pre game", "countdown", "sportscenter", "fantasy", "the insiders", "best of",
        "top 10", "top ten", "magazine", "encore", "re-air", "no event", "no game", "off air", "offair",
        "standby", "stand by", "in 60", "condensed", "a confirmar", "to be announced", "inside",
        "weekly", "tonight", "news", " show ", "primetime",
    )
    private val SPORTS_CHANNEL = listOf(
        "sport", "espn", "fox sports", "fs1", "fs2", "tnt", "tsn", "nfl", "nba", "mlb", "nhl", "ncaa",
        "sec network", "acc network", "big ten", "pac-12", "bein", "dazn", "eurosport", "golf", "tennis",
        "ufc", "wwe", "nascar", "bally", "fanduel", "msg", "nesn", "yes network", "marquee", "root sports",
        "ppv", "event", "f1",
    )
    private val GAME_WORDS = listOf(
        "football", "basketball", "baseball", "hockey", "soccer", "grand prix", "race", "ufc", "boxing",
        "wrestling", "golf", "tennis", "cricket", "rugby", "fight",
    )
    private val NFL = words("falcons ravens bills bears bengals browns cowboys broncos lions packers texans colts jaguars chiefs raiders chargers rams dolphins vikings patriots saints eagles steelers 49ers seahawks buccaneers titans commanders")
    private val NBA = words("hawks celtics nets hornets bulls cavaliers mavericks nuggets pistons warriors rockets pacers clippers lakers grizzlies heat bucks timberwolves pelicans knicks thunder magic 76ers sixers suns blazers spurs raptors jazz wizards")
    private val MLB = words("diamondbacks braves orioles cubs reds guardians rockies tigers astros royals angels dodgers marlins brewers twins mets yankees athletics phillies pirates padres mariners rays nationals") + listOf("red sox", "white sox", "blue jays")
    private val NHL = words("ducks bruins sabres flames hurricanes blackhawks avalanche oilers canadiens predators devils islanders senators flyers penguins sharks kraken blues lightning canucks capitals mammoth") + listOf("blue jackets", "red wings", "maple leafs", "golden knights")
    private val SOCCER = listOf("soccer", "futbol", "fútbol", "premier league", "epl", "la liga", "laliga", "serie a", "bundesliga", "ligue 1", "champions league", "europa", "mls", "liga mx", "copa", "fa cup", "world cup", "eredivisie", "uefa", "concacaf", "fifa")
    private val FIGHTING = listOf("ufc", "boxing", "mma", "bellator", "pfl", "wwe", "aew", "wrestling", "fight night")
    private val RACING = listOf("formula 1", "f1", "nascar", "indycar", "motogp", "grand prix", "racing", "supercross")
    private val COLLEGE = listOf("college", "ncaa", "sec network", "acc network", "big ten", "big 12", "pac-12")

    private fun words(s: String) = s.split(' ')

    private fun has(text: String, word: String): Boolean {
        var i = text.indexOf(word)
        while (i >= 0) {
            val before = i == 0 || !text[i - 1].isLetterOrDigit()
            val end = i + word.length
            val after = end >= text.length || !text[end].isLetterOrDigit()
            if (before && after) return true
            i = text.indexOf(word, i + 1)
        }
        return false
    }

    private fun hasAny(text: String, list: List<String>) = list.any { has(text, it) }

    private val SPORT_WORDS = GAME_WORDS + listOf("futbol", "fútbol", "calcio", "partido", "nfl", "nba", "wnba", "mlb", "nhl", "ncaa", "mls")

    /** True if a programme title (on this channel) looks like a game rather than a studio show. */
    fun isGame(title: String, channelText: String): Boolean {
        val t = " " + title.lowercase() + " "
        if (STUDIO.any { t.contains(it) }) return false
        val onSportsChannel = channelText.lowercase().let { c -> SPORTS_CHANNEL.any { c.contains(it) } }
        // "X vs Y" also appears in "Man vs. Wild" or Czech titles ("v" = "in"): off sports channels
        // it counts only when the title names a sport, a league or a team.
        if (EVENT.containsMatchIn(t)) return onSportsChannel || hasAny(t, SPORT_WORDS) || namesTeams(title.lowercase())
        if (!onSportsChannel) return false
        return AT.containsMatchIn(t) || hasAny(t, GAME_WORDS)
    }

    private fun namesTeams(t: String) = hasAny(t, NFL) || hasAny(t, NBA) || hasAny(t, MLB) || hasAny(t, NHL) || hasAny(t, SOCCER)

    fun league(title: String, channelText: String): String {
        val t = (title + " " + channelText).lowercase()
        val title0 = title.lowercase()
        val college = hasAny(t, COLLEGE)
        return when {
            // League names first, then the sport named in the title, then team nicknames
            // (so "flohockey: Mississauga Chargers vs ..." isn't taken for the NFL's Chargers).
            has(t, "nfl") -> "NFL"
            has(t, "wnba") -> "WNBA"
            has(t, "nba") -> "NBA"
            has(t, "mlb") -> "MLB"
            has(t, "nhl") -> "NHL"
            college && t.contains("football") -> "College Football"
            college && t.contains("basketball") -> "College Basketball"
            hasAny(t, SOCCER) -> "Soccer"
            hasAny(t, FIGHTING) -> "Fighting"
            hasAny(t, RACING) -> "Racing"
            hasAny(t, listOf("golf", "pga", "lpga")) -> "Golf"
            hasAny(t, listOf("tennis", "atp", "wta")) -> "Tennis"
            has(t, "cricket") -> "Cricket"
            has(t, "rugby") -> "Rugby"
            t.contains("hockey") -> if (hasAny(title0, NHL)) "NHL" else "Hockey"
            t.contains("baseball") -> if (hasAny(title0, MLB)) "MLB" else "Baseball"
            t.contains("basketball") -> if (hasAny(title0, NBA)) "NBA" else "Basketball"
            !college && hasAny(title0, NFL) -> "NFL"
            !college && hasAny(title0, NBA) -> "NBA"
            hasAny(title0, MLB) -> "MLB"
            hasAny(title0, NHL) -> "NHL"
            t.contains("football") -> "Football"
            college -> "College Sports"
            else -> "Other"
        }
    }

    private val EVENT_DATE = Regex("\\((\\d{4})-(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2})(?::\\d{2})?(?: ?[A-Za-z]{2,4})?\\)")
    private val EASTERN: TimeZone = TimeZone.getTimeZone("America/New_York")

    /**
     * Event channels named after their game, e.g. "(58) Senators vs. Bruins Oct 05 7:30PM ET
     * (2026-10-05 19:30:00)". Returns the game title if it's on around now (30 min before to
     * 4 h after the start), else null. Names without a date are left out: providers reuse
     * event channels and leave old names behind.
     */
    fun eventChannelTitle(name: String, nowMs: Long): String? {
        val lower = " " + name.lowercase() + " "
        if (!EVENT.containsMatchIn(lower) || STUDIO.any { lower.contains(it) }) return null
        // java.util.Calendar: java.time needs Android 8, older Fire TV sticks run 7.1.
        val starts = ArrayList<Long>(2)
        val date = EVENT_DATE.find(name)
        if (date != null) {
            val (y, mo, d, h, mi) = date.destructured
            starts += Calendar.getInstance(EASTERN).apply {
                clear()
                set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), 0)
            }.timeInMillis
        } else {
            // A kickoff time without a date isn't enough: providers keep yesterday's names.
            return null
        }
        if (starts.none { nowMs >= it - 30 * 60_000L && nowMs <= it + 4 * 3_600_000L }) return null
        return name.replace(Regex("^\\(\\d+\\)\\s*"), "").replace(EVENT_DATE, "").trim()
    }

    /**
     * NFL, NBA, MLB and NHL games: every local station carrying one would list it again, so
     * those leagues' games come from their dedicated channels ("NFL: Tennessee Titans" in
     * "USA | NFL Teams", "NFL 1: ..." in "USA | NFL Game Pass") or national ones, never local
     * stations ("USA | LOCAL - CBS"...). Same rule as the stream server and Roku (Groups.brs).
     */
    val PRO_LEAGUES = setOf("NFL", "NBA", "MLB", "NHL")
    private val LOCAL_GROUP = Regex("""\blocal\b""", RegexOption.IGNORE_CASE)
    private val DEDICATED_GROUP = Regex("""^\s*USA?\s*\|\s*(NFL|NBA|MLB|NHL)\s+(Teams|Game\s*Pass)\s*$""", RegexOption.IGNORE_CASE)
    private val DEDICATED_NAME = Regex("""^\s*(NFL|NBA|MLB|NHL)\s*\d*\s*:""", RegexOption.IGNORE_CASE)

    fun isLocal(group: String) = LOCAL_GROUP.containsMatchIn(group)

    /** A league's own channel: team channels and Game Pass feeds. */
    fun isDedicated(name: String, group: String) = DEDICATED_GROUP.containsMatchIn(group) || DEDICATED_NAME.containsMatchIn(name)

    /** "PIT vs. CBJ • ANA vs. WPG" whip-around feeds (the bullet sometimes arrives garbled as "â€¢"). */
    fun isMultiGame(title: String) = title.contains('•') || title.contains("â€¢")

    private val KEY_NOISE = Regex("\\([^)]*\\)|\\b(new|live|hd|fhd)\\b")

    /** Normalized title for folding the same game shown on several channels into one entry. */
    fun gameKey(title: String) = title.lowercase().replace(KEY_NOISE, " ").filter { it.isLetterOrDigit() }
}
