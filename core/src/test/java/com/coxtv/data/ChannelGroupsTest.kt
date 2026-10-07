package com.coxtv.data

import com.coxtv.data.db.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelGroupsTest {
    private fun ch(id: String, name: String, tvg: String? = null, order: Int = id.hashCode()) =
        Channel(id, order, 0, name, null, "G", "http://x/$id", tvg, tvg, false, null)

    @Test fun copiesShareAKey() {
        val k = ChannelGroups.key("FR|M6 (SD)")
        assertEquals(k, ChannelGroups.key("FR|M6 (HD)"))
        assertEquals(k, ChannelGroups.key("Fr: M6 FHD"))
        assertEquals(k, ChannelGroups.key("FR : M6"))
        assertEquals(ChannelGroups.key("It: LA 7 HD"), ChannelGroups.key("IT: LA 7 SD"))
        assertEquals(ChannelGroups.key("24/7 Rick and Morty"), ChannelGroups.key("24/7: Rick and Morty"))
    }

    @Test fun differentChannelsStayApart() {
        assertNotEquals(ChannelGroups.key("IRE | Ireland Parliament (Committee Room 1)"), ChannelGroups.key("IRE | Ireland Parliament (Committee Room 2)"))
        assertNotEquals(ChannelGroups.key("24/7: The Twilight Zone (1985)"), ChannelGroups.key("24/7: The Twilight Zone (2002)"))
        assertNotEquals(ChannelGroups.key("USA | ESPN"), ChannelGroups.key("UK | ESPN"))
        assertNotEquals(ChannelGroups.key("Quran TV: أذكار الصباح"), ChannelGroups.key("Quran TV: ياسر الدوسري"))
        assertNotEquals(ChannelGroups.key("NY | New York ABC 7 WABC"), ChannelGroups.key("IL | Chicago ABC WLS"))
    }

    @Test fun groupsPickBestQualityAndKeepAllSources() {
        val r = ChannelGroups.group(listOf(ch("1", "FR|M6 (SD)", order = 1), ch("2", "FR|M6 (HD)", order = 2), ch("3", "Fr: M6 FHD", order = 3)), emptySet())
        assertEquals(1, r.channels.size)
        assertEquals("3", r.channels[0].id)                      // FHD first
        assertEquals(1, r.channels[0].sortOrder)                 // keeps the first copy's position
        assertEquals(listOf("3", "2", "1"), r.sources["3"]!!.map { it.id })
        assertEquals("3", r.shownId["1"])
    }

    @Test fun conflictingGuideIdsAreDifferentFeeds() {
        val r = ChannelGroups.group(listOf(ch("1", "USA | HBO", "hbo.east"), ch("2", "USA | HBO HD", "hbo.west"), ch("3", "USA | HBO FHD")), emptySet())
        assertEquals(2, r.channels.size)
    }

    @Test fun hidingAnyCopyHidesTheChannel() {
        val r = ChannelGroups.group(listOf(ch("1", "PL: TVP Sport"), ch("2", "PL: TVP Sport HD"), ch("3", "Other")), setOf("1"))
        assertEquals(listOf("3"), r.channels.map { it.id })
        assertEquals(1, r.hidden.size)
    }
}

class SportsTest {
    @Test fun gamesAndStudioShows() {
        assertTrue(Sports.isGame("Ottawa Senators vs. Boston Bruins", "NHL Network"))
        assertTrue(Sports.isGame("Yankees at Red Sox", "USA | MLB Network"))
        assertTrue(Sports.isGame("Liverpool v Arsenal", "UK: Sky Sports Main Event"))
        assertFalse(Sports.isGame("Live at the Apollo", "Comedy Central"))
        assertFalse(Sports.isGame("NFL Fantasy Live", "NFL Network"))
        assertFalse(Sports.isGame("SportsCenter", "ESPN"))
        assertFalse(Sports.isGame("Bills vs. Jets Highlights", "ESPN"))
    }

    @Test fun leagues() {
        assertEquals("NHL", Sports.league("Ottawa Senators vs. Boston Bruins", "Sports"))
        assertEquals("MLB", Sports.league("Yankees at Red Sox", "Sports"))
        assertEquals("NFL", Sports.league("Bills vs. Jets", "CBS"))
        assertEquals("College Sports", Sports.league("Alabama vs. Georgia", "SEC Network"))
        assertEquals("College Football", Sports.league("Alabama vs. Georgia (College Football)", "SEC Network"))
        assertEquals("Soccer", Sports.league("Liverpool v Arsenal", "Premier League TV"))
        assertEquals("Fighting", Sports.league("UFC 310: Main Card", "PPV"))
    }

    @Test fun eventChannels() {
        val name = "(58) Ottawa Senators vs. Boston Bruins Oct 05 7:30PM ET (2026-10-05 19:30:00)"
        // 2026-10-05 19:30 EDT = 23:30 UTC
        val start = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 9, 5, 23, 30, 0) }.timeInMillis
        assertEquals("Ottawa Senators vs. Boston Bruins Oct 05 7:30PM ET", Sports.eventChannelTitle(name, start + 3_600_000))
        assertNull(Sports.eventChannelTitle(name, start + 6 * 3_600_000))
        assertNull(Sports.eventChannelTitle("No Event", start))
    }
}

class SportsRulesV2Test {
    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { clear(); set(y, mo - 1, d, h, mi, 0) }.timeInMillis

    @Test fun sportWordBeatsNickname() {
        assertEquals("Hockey", Sports.league("Flo Sports 05: flohockey: 2026 Mississauga Chargers vs Aurora", "Flo Sports"))
        assertEquals("NFL", Sports.league("Monday Night Football: Falcons vs. Saints", "ESPN"))
    }

    @Test fun eventChannelsNeedATimeAroundNow() {
        val now = utc(2026, 10, 7, 2, 30) // 10:30 PM EDT on Oct 6
        assertNull(Sports.eventChannelTitle("Cowboys vs. Packers (2026-10-18 19:00:00 ET)", now))
        assertNull(Sports.eventChannelTitle("Flo Sports 05: Mississauga Chargers vs Aurora", now))      // no time
        assertNull(Sports.eventChannelTitle("NFL 1: Falcons vs Saints 8:15 PM", now))   // time but no date
        assertFalse(Sports.isGame("No Game Today", "NFL: Cincinnati Bengals"))
        assertFalse(Sports.isGame("Game of the Week : Week 4", "USA: NFL Channel"))
    }

    @Test fun nonSportsVersusIsNotAGame() {
        assertFalse(Sports.isGame("Man vs. Wild", "IN: Discovery Channel"))
        assertFalse(Sports.isGame("NBC10 News @ 10PM  LIVE", "USA: NBC Philadelphia News"))
        assertFalse(Sports.isGame("Ordinace v růžové zahradě 2", "Cz: Nova HD"))
        assertFalse(Sports.isGame("VSiN PrimeTime  LIVE", "USA: VSIN"))
        assertFalse(Sports.isGame("Inside College Football", "CBS Sports Network"))
        assertTrue(Sports.isGame("Fútbol masculino amistosos internacionales : México vs. Chile LIVE", "USA Univision East"))
        assertTrue(Sports.isGame("Houston Rockets vs. Oklahoma City Thunder", "Arg: TNT Sports HD"))
    }

    @Test fun gameKeyIgnoresTags() {
        assertEquals(Sports.gameKey("NFL Football"), Sports.gameKey("NFL Football  NEW"))
    }
}
