package com.coxtv.data

/**
 * Favorite teams, kept per sport ({"nfl": [...], "mlb": [...], "soccer": [...], "ncaaf": [...]})
 * and synced with the other apps and tv.thecoxhome.com. College teams share one list ("ncaaf",
 * the key the dashboard's College Football tab uses), so a school starred in any NCAA sport
 * counts in all of them.
 *
 * Same rules as the Roku app (Utils.brs) and the web player (watch.js).
 */
object Teams {
    /** The favorite-teams list a league's games use. */
    fun sportFor(league: String): String = when (league) {
        "NFL" -> "nfl"
        "NBA" -> "nba"
        "WNBA" -> "wnba"
        "MLB" -> "mlb"
        "NHL" -> "nhl"
        "Soccer" -> "soccer"
        "College Football", "College Basketball", "College Sports" -> "ncaaf"
        else -> league.lowercase()
    }

    /** Short label for a sport list ("ncaaf" -> "NCAA"). */
    fun sportLabel(sport: String): String = when (sport) {
        "ncaaf" -> "NCAA"
        "soccer" -> "Soccer"
        else -> sport.uppercase()
    }

    private val SEP = Regex("""\s+(?:vs\.?|v\.?|versus|at|@|x|-|–)\s+""", RegexOption.IGNORE_CASE)
    private val TAIL = Regex("""\s+[-–|]\s+.*$""")
    private val RANK = Regex("""^#\d+\s+""")

    /** The two teams in a game title ("NHL Hockey : Pittsburgh Penguins at Washington Capitals"). */
    fun sides(title: String): List<String> {
        var t = title.replace(Regex("""\([^)]*\)"""), " ").trim()
        // League prefixes end in a colon: keep what follows the last one that still has a matchup.
        val colon = t.lastIndexOf(':')
        if (colon >= 0 && SEP.containsMatchIn(t.substring(colon + 1))) t = t.substring(colon + 1)
        val parts = t.split(SEP, limit = 2)
        if (parts.size < 2) return emptyList()
        return parts.map { p -> RANK.replace(TAIL.replace(p.trim(), ""), "").trim() }
            .filter { it.length >= 2 && it.any(Char::isLetter) }
            .takeIf { it.size == 2 }.orEmpty()
    }

    fun key(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    private fun words(s: String) = s.lowercase().split(Regex("""[^\p{L}\p{N}&]+""")).filter { it.isNotBlank() }.toSet()

    /** Words that make a different school ("Florida" isn't "Florida State"). */
    private val OTHER_SCHOOL = setOf("state", "st", "tech", "a&m", "am", "international", "southern", "northern",
        "eastern", "western", "central", "christian", "poly", "university", "college", "atlantic")

    /** Whether a game's team name means this favorite ("Braves" = "Atlanta Braves"). */
    fun matches(team: DeviceLink.Team, side: String): Boolean {
        if (key(team.display) == key(side) || team.key == key(side)) return true
        val t = words(team.display)
        val s = words(side)
        if (t.isEmpty() || s.isEmpty()) return false
        // Title says less ("Braves"), but not a different school ("Florida" isn't "Florida State").
        if (t.containsAll(s)) return (t - s).none { it in OTHER_SCHOOL }
        return s.containsAll(t) && (s - t).none { it in OTHER_SCHOOL }  // title says more: "Atlanta Braves"
    }

    /** Teams in a game, each with whether it is already a favorite. */
    fun inGame(title: String, league: String, favorites: Map<String, List<DeviceLink.Team>>): List<Pair<DeviceLink.Team, Boolean>> {
        val mine = favorites[sportFor(league)].orEmpty()
        return sides(title).map { side ->
            val existing = mine.firstOrNull { matches(it, side) }
            (existing ?: DeviceLink.Team(key(side), side)) to (existing != null)
        }
    }

    fun isMine(title: String, league: String, favorites: Map<String, List<DeviceLink.Team>>): Boolean {
        val mine = favorites[sportFor(league)] ?: return false
        return mine.isNotEmpty() && sides(title).any { side -> mine.any { matches(it, side) } }
    }

    /** Parses the synced JSON ({"sport": [{"key", "display"}]}). */
    fun parse(json: String): Map<String, List<DeviceLink.Team>> = runCatching {
        val o = org.json.JSONObject(json)
        o.keys().asSequence().associateWith { sport ->
            val a = o.optJSONArray(sport) ?: org.json.JSONArray()
            List(a.length()) { DeviceLink.Team(a.getJSONObject(it).optString("key"), a.getJSONObject(it).optString("display")) }
        }
    }.getOrDefault(emptyMap())
}
