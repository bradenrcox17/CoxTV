package com.coxtv.data

import android.content.Context
import android.util.Log
import com.coxtv.data.db.AppDatabase
import com.coxtv.data.db.Channel
import com.coxtv.data.db.ChannelEntity
import com.coxtv.data.db.ChannelHit
import com.coxtv.data.db.NowHit
import com.coxtv.data.db.FavoriteEntity
import com.coxtv.data.db.ProgramEntity
import com.coxtv.data.remote.M3uParser
import com.coxtv.data.remote.XmltvParser
import com.coxtv.data.remote.XtreamClient
import com.coxtv.data.remote.download
import com.coxtv.data.remote.getStream
import com.coxtv.data.remote.openMaybeGzip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

private const val TAG = "CoxTV"
private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

data class SearchResult(
    val channelId: String,
    val title: String,
    val channelName: String,
    val groupName: String,
    /** End of the show airing now, or 0 when there's no guide info. */
    val endMs: Long,
)

/** Category keys. Anything else is a channel group name. */
object Categories {
    const val FAVORITES = "__favorites__"
    const val ALL = "__all__"
    const val RECENT = "__recent__"
    const val SPORTS = "__sports__"
    const val CFB = "__cfb__"
    /** A league's live games: "__league__:NFL" (from the stream server's Sports on now). */
    const val LEAGUE = "__league__:"

    /** Same list and order as the stream server (coxstream.py LEAGUES) and the web player. */
    val LEAGUES = listOf(
        "NFL", "College Football", "NBA", "WNBA", "College Basketball", "MLB", "NHL", "Soccer", "Fighting",
        "Racing", "Golf", "Tennis", "Cricket", "Rugby", "Handball", "Volleyball", "Cycling", "Snooker & Darts",
        "College Sports", "Football", "Basketball", "Baseball", "Hockey", "Other",
    )
    val LEAGUE_KEYS = LEAGUES.map { LEAGUE + it }

    /** Built-in categories in their default order. */
    val BUILT_INS = listOf(FAVORITES, RECENT, SPORTS, CFB, ALL)

    fun leagueOf(key: String): String? = if (key.startsWith(LEAGUE)) key.removePrefix(LEAGUE) else null

    fun label(key: String) = when (key) {
        FAVORITES -> "Favorites"
        ALL -> "All Channels"
        RECENT -> "Recent"
        SPORTS -> "Sports on now"
        CFB -> "College Football guide"
        else -> leagueOf(key) ?: key
    }

    fun filter(channels: List<Channel>, key: String, extras: CategoryExtras = CategoryExtras.EMPTY): List<Channel> = when {
        key == FAVORITES -> channels.filter { it.favorite }.sortedWith(compareBy({ it.favoritePosition }, { it.sortOrder }))
        key == RECENT -> extras.recent
        key == SPORTS -> extras.sports.map { it.channel }
        key == CFB -> emptyList()
        leagueOf(key) != null -> extras.sports.filter { it.league == leagueOf(key) }.map { it.channel }
        key == ALL || key.isEmpty() -> channels
        else -> channels.filter { it.groupName == key }
    }

    fun isBuiltIn(key: String) = key in BUILT_INS || key in LEAGUE_KEYS

    /** Keys as the other CoxTV apps and the web player name them (for settings sync). */
    fun toShared(key: String) = if (key == FAVORITES) "__fav__" else key
    fun fromShared(key: String) = if (key == "__fav__") FAVORITES else key
}

/** A live game for "Sports on now". */
data class SportsGame(val channel: Channel, val title: String, val league: String, val endMs: Long, val mine: Boolean = false)

/** Lists for the built-in categories that don't come straight from the channel table. */
data class CategoryExtras(val recent: List<Channel>, val sports: List<SportsGame>) {
    /** Row text for the Sports category: "NHL · Senators vs. Bruins". */
    val sportsLabels: Map<String, String> = sports.associate {
        it.channel.id to (if (it.mine) "★ " else "") + "${it.league} · ${it.title}"
    }

    companion object {
        val EMPTY = CategoryExtras(emptyList(), emptyList())
    }
}

/** A category on the Categories & favorites screen. */
data class CategoryOption(val key: String, val enabled: Boolean)

/** Moves the item at [from] by [delta] places (clamped). Returns null if nothing moved. */
fun <T> List<T>.moved(from: Int, delta: Int): List<T>? {
    val to = (from + delta).coerceIn(0, lastIndex)
    if (from !in indices || to == from) return null
    return toMutableList().apply { add(to, removeAt(from)) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TvRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val http: OkHttpClient,
    private val link: DeviceLink,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Duplicates folded, hidden channels split out; computed once and shared by every screen. */
    private val grouping: Flow<ChannelGroups.Result> =
        combine(db.channels().observeAll(), settings.hidden) { all, hidden -> ChannelGroups.group(all, hidden) }
            .onEach { lastGrouping = it }
            .flowOn(Dispatchers.Default)
            .shareIn(scope, SharingStarted.WhileSubscribed(10_000), replay = 1)

    @Volatile private var lastGrouping: ChannelGroups.Result? = null

    private suspend fun currentGrouping(): ChannelGroups.Result = lastGrouping ?: grouping.first()

    /** Channels to show: one entry per channel (copies folded), hidden ones left out. */
    val channels: Flow<List<Channel>> = grouping.map { it.channels }
    val hiddenChannels: Flow<List<Channel>> = grouping.map { it.hidden }
    val groups: Flow<List<String>> = db.channels().observeGroups()

    /** Every source for a shown channel, best first (the channel itself if it has no copies). */
    suspend fun sourcesFor(channel: Channel): List<Channel> = currentGrouping().sources[channel.id] ?: listOf(channel)

    /** The id shown for any channel id (copies map to their group's channel). */
    suspend fun shownId(id: String): String = currentGrouping().shownId[id] ?: id

    /** The shown channel for a stream server channel id (sent from the remote), if this playlist has it. */
    suspend fun channelForServerId(serverId: String): Channel? {
        val g = currentGrouping()
        val match = g.sources.values.asSequence().flatten().firstOrNull { DeviceLink.serverId(it.streamUrl) == serverId }
            ?: return null
        val shown = g.shownId[match.id] ?: match.id
        return g.channels.firstOrNull { it.id == shown }
    }

    /** Recently watched channels, newest first. */
    val recentChannels: Flow<List<Channel>> = combine(grouping, settings.recent) { g, ids ->
        val byId = g.channels.associateBy { it.id }
        ids.mapNotNull { g.shownId[it] }.distinct().mapNotNull { byId[it] }
    }

    suspend fun addRecent(channel: Channel) = settings.addRecent(channel.id)

    suspend fun setHidden(channel: Channel, hide: Boolean) {
        val ids = currentGrouping().sources[channel.id]?.map { it.id } ?: listOf(channel.id)
        if (hide) settings.setHidden(listOf(channel.id), true) else settings.setHidden(ids + channel.id, false)
    }

    /**
     * Games on right now, one entry per game, by league then title; refreshed every minute.
     * Linked apps use the stream server's list (it sees the provider's full guide, including
     * which programmes are live); otherwise the app's own rules.
     */
    val sports: Flow<List<SportsGame>> by lazy {
        combine(grouping, nowPlaying, serverSports, settings.teams) { g, now, server, teamsJson ->
            val games = if (server != null) fromServer(g, server) else findGames(g.channels, now)
            // Favorite teams' games first (and starred), keeping the league order otherwise.
            val teams = Teams.parse(teamsJson)
            games.map { it.copy(mine = Teams.isMine(it.title, it.league, teams)) }.sortedBy { if (it.mine) 0 else 1 }
        }.flowOn(Dispatchers.Default)
    }

    private val serverSports: Flow<DeviceLink.ServerSports?> = flow {
        while (true) {
            emit(runCatching { link.sports() }.getOrNull())
            delay(60_000)
        }
    }

    @Volatile private var serverIndex: Pair<ChannelGroups.Result, Map<String, Channel>>? = null

    /** Stream server channel id -> the channel shown for it (any copy maps to its group). */
    private fun serverIndexFor(g: ChannelGroups.Result): Map<String, Channel> {
        serverIndex?.let { if (it.first === g) return it.second }
        val shown = g.channels.associateBy { it.id }
        val index = HashMap<String, Channel>(g.shownId.size * 2)
        for ((shownId, copies) in g.sources) {
            val display = shown[shownId] ?: continue
            for (c in copies) DeviceLink.serverId(c.streamUrl)?.let { index.putIfAbsent(it, display) }
        }
        serverIndex = g to index
        return index
    }

    private fun fromServer(g: ChannelGroups.Result, server: DeviceLink.ServerSports): List<SportsGame> {
        val index = serverIndexFor(g)
        val seen = HashSet<String>()
        return server.games.mapNotNull { s ->
            val ch = index[s.serverId] ?: return@mapNotNull null
            if (seen.add(ch.id)) SportsGame(ch, s.title, s.league, s.endMs) else null
        }
    }

    // ---- College Football guide --------------------------------------------------------

    data class CfbRow(val game: DeviceLink.CfbGame, val channel: Channel?, val mine: Boolean)
    data class CfbGuide(val week: Int?, val rows: List<CfbRow>)

    private val cfbPoll: Flow<DeviceLink.Cfb?> = flow {
        while (true) {
            emit(runCatching { link.cfb() }.getOrNull())
            delay(60_000)
        }
    }

    /** Favorite teams for a sport ("ncaaf"), shared with tv.thecoxhome.com and the other apps. */
    fun favoriteTeams(sport: String = "ncaaf"): Flow<List<DeviceLink.Team>> = settings.teams.map { teamsOf(it, sport) }

    /** Every favorite team, by sport. */
    val allTeams: Flow<Map<String, List<DeviceLink.Team>>> = settings.teams.map(Teams::parse)

    private fun teamsOf(json: String, sport: String): List<DeviceLink.Team> = runCatching {
        val a = org.json.JSONObject(json).optJSONArray(sport) ?: return@runCatching emptyList()
        List(a.length()) { DeviceLink.Team(a.getJSONObject(it).optString("key"), a.getJSONObject(it).optString("display")) }
    }.getOrDefault(emptyList())

    /** Adds or removes a favorite team (synced like favorites). */
    suspend fun toggleTeam(team: DeviceLink.Team, sport: String = "ncaaf") = setTeam(team, sport, null)

    /** Stars ([on] = true) or un-stars a team; null flips it. */
    suspend fun setTeam(team: DeviceLink.Team, sport: String, on: Boolean?) {
        val all = runCatching { org.json.JSONObject(settings.teams.first()) }.getOrDefault(org.json.JSONObject())
        val current = teamsOf(all.toString(), sport)
        val has = current.any { it.key == team.key }
        val want = on ?: !has
        if (want == has) return
        val next = if (has) current.filterNot { it.key == team.key } else current + team
        all.put(sport, org.json.JSONArray(next.map { org.json.JSONObject().put("key", it.key).put("display", it.display) }))
        settings.setTeams(all.toString())
    }

    private var teamCatalogCache: List<DeviceLink.TeamSport>? = null

    /** Every team the Favorite teams screen offers (from the stream server); null if unavailable. */
    suspend fun teamCatalog(): List<DeviceLink.TeamSport>? =
        teamCatalogCache ?: runCatching { link.teamCatalog() }.getOrNull()?.takeIf { it.isNotEmpty() }?.also { teamCatalogCache = it }

    /** This week's games: live first, then by kickoff; null until loaded (or when not linked). */
    val cfbGuide: Flow<CfbGuide?> by lazy {
        combine(grouping, cfbPoll, favoriteTeams()) { g, cfb, teams ->
            if (cfb == null) return@combine null
            val index = serverIndexFor(g)
            val mine = teams.map { it.key }.toSet()
            CfbGuide(
                cfb.week,
                cfb.games.map { game ->
                    CfbRow(game, game.serverIds.firstNotNullOfOrNull { index[it] }, game.teamKeys.any { it in mine })
                }.sortedWith(compareBy({ if (it.game.live) 0 else 1 }, { it.game.kickoffSort })),
            )
        }.flowOn(Dispatchers.Default)
    }

    // ---- Settings sync helpers (values as the other apps and the web player store them) ---

    /** Changes whenever favorites (or their order) change. */
    val favoritesVersion: Flow<List<String>> = db.channels().observeFavoriteIds()

    /** Favorites in order, as stream server channel ids. */
    suspend fun sharedFavorites(): List<String> {
        val byId = db.channels().allEntities().associateBy { it.id }
        return db.channels().favoriteIds().mapNotNull { id -> byId[id]?.let { DeviceLink.serverId(it.streamUrl) } }.distinct()
    }

    /** Hidden channels, as stream server channel ids. */
    suspend fun sharedHidden(): List<String> {
        val byId = db.channels().allEntities().associateBy { it.id }
        return settings.hidden.first().mapNotNull { id -> byId[id]?.let { DeviceLink.serverId(it.streamUrl) } }.distinct().sorted()
    }

    private suspend fun localIdsFor(serverIds: List<String>): List<String> {
        val byServerId = HashMap<String, String>()
        for (c in db.channels().allEntities()) DeviceLink.serverId(c.streamUrl)?.let { byServerId.putIfAbsent(it, c.id) }
        return serverIds.mapNotNull { byServerId[it] }.distinct()
    }

    /** Stream server ids this device's playlist doesn't have. */
    suspend fun unknownServerIds(serverIds: List<String>): List<String> {
        val known = HashSet<String>()
        for (c in db.channels().allEntities()) DeviceLink.serverId(c.streamUrl)?.let { known += it }
        return serverIds.filter { it !in known }
    }

    suspend fun applySharedFavorites(serverIds: List<String>) = db.channels().replaceFavorites(localIdsFor(serverIds))

    suspend fun applySharedHidden(serverIds: List<String>) = settings.replaceHidden(localIdsFor(serverIds))

    val categoryExtras: Flow<CategoryExtras> by lazy {
        combine(recentChannels, sports) { recent, games -> CategoryExtras(recent, games) }
    }

    private fun findGames(channels: List<Channel>, now: Map<String, ProgramEntity>): List<SportsGame> {
        val nowMs = System.currentTimeMillis()
        val seen = HashSet<String>()
        val found = ArrayList<Pair<Int, SportsGame>>()
        for (ch in channels) {
            if (!ChannelGroups.isUs(ch.name, ch.groupName)) continue // Sports on now: US channels only
            val p = ch.epgId?.let { now[it] }
            val chText = ch.name + " " + ch.groupName
            val game = when {
                p != null && p.endMs > nowMs && Sports.isGame(p.title, chText) ->
                    SportsGame(ch, p.title, Sports.league(p.title, chText), p.endMs)
                p == null -> Sports.eventChannelTitle(ch.name, nowMs)?.let { SportsGame(ch, it, Sports.league(it, chText), 0L) }
                else -> null
            } ?: continue
            if (Sports.isMultiGame(game.title)) continue // whip-around feeds: those games are listed on their own
            var rank = 1
            if (game.league in Sports.PRO_LEAGUES) {
                if (Sports.isLocal(ch.groupName)) continue // NFL/NBA/MLB/NHL: never a local station's copy
                if (Sports.isDedicated(ch.name, ch.groupName)) rank = 0
            }
            found += rank to game
        }
        // A team / Game Pass channel is the one listed for its game (stable: channel order otherwise).
        val games = ArrayList<SportsGame>()
        for ((_, game) in found.sortedBy { it.first }) if (seen.add(Sports.gameKey(game.title))) games += game
        return games.sortedWith(compareBy({ Sports.LEAGUES.indexOf(it.league) }, { it.title.lowercase() }))
    }

    /** Categories to show (sidebar / chips), in the user's order: only enabled ones that exist. */
    val categories: Flow<List<String>> = combine(settings.categoryOrder, groups) { order, groups ->
        val present = groups.toHashSet()
        order.filter { Categories.isBuiltIn(it) || it in present }.ifEmpty { listOf(Categories.ALL) }
    }

    /** Every category for the settings screen: enabled ones in order, then the rest in playlist order. */
    val categoryOptions: Flow<List<CategoryOption>> = combine(settings.categoryOrder, groups) { order, groups ->
        val present = groups.toHashSet()
        val enabled = order.filter { Categories.isBuiltIn(it) || it in present }
        val enabledSet = enabled.toHashSet()
        enabled.map { CategoryOption(it, true) } +
            (Categories.BUILT_INS + Categories.LEAGUE_KEYS + groups)
                .filter { it !in enabledSet }
                .map { CategoryOption(it, false) }
    }

    private val categoryMutex = Mutex()

    /** Turns a category on (added at the end) or off. The last enabled category can't be turned off. */
    suspend fun setCategoryEnabled(key: String, enabled: Boolean) = categoryMutex.withLock {
        val order = settings.categoryOrder.first()
        val present = groups.first().toHashSet()
        val visible = order.filter { Categories.isBuiltIn(it) || it in present }
        when {
            enabled && key !in order -> settings.setCategoryOrder(order + key)
            !enabled && key in order && (visible - key).isNotEmpty() -> settings.setCategoryOrder(order - key)
        }
    }

    /** Moves an enabled category up (delta < 0) or down among the categories shown. */
    suspend fun moveCategory(key: String, delta: Int) = categoryMutex.withLock {
        val order = settings.categoryOrder.first()
        val present = groups.first().toHashSet()
        val visible = order.filter { Categories.isBuiltIn(it) || it in present }
        val moved = visible.moved(visible.indexOf(key), delta) ?: return@withLock
        // Groups missing from the current playlist keep their place at the end, in case they return.
        settings.setCategoryOrder(moved + order.filter { it !in visible })
    }

    /** Program airing right now, keyed by EPG id; re-evaluated every minute. */
    val nowPlaying: Flow<Map<String, ProgramEntity>> = minuteTicker()
        .flatMapLatest { now -> db.programs().observeInRange(now, now + 1) }
        .map { list -> list.associateBy { it.epgId } }

    /** Programs for the given channels' EPG ids within [from, to), grouped by EPG id. */
    suspend fun programsFor(epgIds: Collection<String>, from: Long, to: Long): Map<String, List<ProgramEntity>> {
        if (epgIds.isEmpty()) return emptyMap()
        val out = HashMap<String, List<ProgramEntity>>()
        epgIds.distinct().chunked(500).forEach { chunk ->
            db.programs().inRangeFor(chunk, from, to).groupBy { it.epgId }.forEach { (k, v) -> out[k] = v }
        }
        return out
    }

    // ---- Search: two small in-memory indexes, scanned with plain string matching ----
    // (SQL LIKE over REPLACE() per row was ~0.5-1 s on the emulator; this is milliseconds.)

    private class NameIndex(val rows: List<ChannelHit>, val keys: Array<String>)
    private class NowIndex(val until: Long, val hits: List<NowHit>, val keys: Array<String>, val byChannel: Map<String, NowHit>)

    @Volatile private var nameIndex: NameIndex? = null
    @Volatile private var nowIndex: NowIndex? = null
    private val searchMutex = Mutex()

    private fun invalidateSearch(channels: Boolean) {
        if (channels) nameIndex = null
        nowIndex = null
    }

    /** Builds the indexes ahead of time (the Search screen calls this when it opens). */
    suspend fun prepareSearch() = withContext(Dispatchers.IO) {
        searchMutex.withLock { ensureIndexes(System.currentTimeMillis()) }
    }

    private suspend fun ensureIndexes(now: Long) {
        if (nameIndex == null) {
            val rows = db.channels().searchRows()
            nameIndex = NameIndex(rows, Array(rows.size) { searchKey(rows[it].channelName) })
        }
        val ni = nowIndex
        if (ni == null || now >= ni.until) {
            val hits = db.programs().airingNow(now)
            val until = hits.minOfOrNull { it.endMs } ?: (now + 15 * 60_000L)
            nowIndex = NowIndex(until, hits, Array(hits.size) { searchKey(hits[it].title) }, hits.associateBy { it.channelId })
        }
    }

    /**
     * Partial, case-insensitive search that ignores spaces and punctuation ("whitesox" finds
     * "White Sox"). Shows airing now whose title matches come first, then channels whose name
     * matches ("espn", "sec network") with whatever they're showing now.
     */
    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val key = searchKey(query)
        if (key.length < 2) return@withContext emptyList()
        val started = System.nanoTime()
        val now = System.currentTimeMillis()
        val (names, airing) = searchMutex.withLock {
            ensureIndexes(now)
            nameIndex!! to nowIndex!!
        }
        // Results point at the channel shown for each copy; one result per channel, none hidden.
        // Every country matches, but US channels (ESPN+ and Flo included) come first, Spanish-
        // language ones lower, and the best name matches first (ChannelGroups.searchTier /
        // nameMatchRank), so look further than the 100 shown before cutting each list.
        val g = currentGrouping()
        val visible = g.visibleIds
        // Shows on now that are NFL/NBA/MLB/NHL games skip local stations and list the league's
        // dedicated channels first (Sports.PRO_LEAGUES). Channel-name matches below are unchanged.
        data class Event(val tier: Int, val dedicated: Int, val result: SearchResult)
        val events = ArrayList<Event>()
        val eventSeen = HashSet<String>()
        for (i in airing.keys.indices) {
            if (airing.keys[i].contains(key)) {
                val h = airing.hits[i]
                val id = g.shownId[h.channelId] ?: h.channelId
                if (h.endMs > now && id in visible && id !in eventSeen) {
                    var dedicated = 1
                    var localGame = false
                    if (Sports.league(h.title, h.channelName + " " + h.groupName) in Sports.PRO_LEAGUES) {
                        localGame = Sports.isLocal(h.groupName)
                        if (Sports.isDedicated(h.channelName, h.groupName)) dedicated = 0
                    }
                    if (!localGame) {
                        eventSeen += id
                        events += Event(ChannelGroups.searchTier(h.channelName, h.groupName), dedicated,
                            SearchResult(id, h.title, h.channelName, h.groupName, h.endMs))
                    }
                }
                if (events.size >= 400) break
            }
        }
        val eventResults = events.sortedWith(compareBy({ it.tier }, { it.dedicated }, { it.result.title.lowercase() }))
            .take(100).map { it.result }
        val seen = eventResults.mapTo(HashSet()) { it.channelId }
        data class Ranked(val tier: Int, val rank: Int, val length: Int, val result: SearchResult)
        val channels = ArrayList<Ranked>()
        for (i in names.keys.indices) {
            if (names.keys[i].contains(key)) {
                val r = names.rows[i]
                val id = g.shownId[r.channelId] ?: r.channelId
                if (id in seen || id !in visible) continue
                seen += id
                val p = airing.byChannel[r.channelId]?.takeIf { it.endMs > now }
                val (rank, length) = ChannelGroups.nameMatchRank(r.channelName, query)
                channels += Ranked(
                    ChannelGroups.searchTier(r.channelName, r.groupName), rank, length,
                    if (p != null) SearchResult(id, p.title, r.channelName, r.groupName, p.endMs)
                    else SearchResult(id, r.channelName, r.groupName, r.groupName, 0L),
                )
                if (channels.size >= 1000) break
            }
        }
        // Stable sort: playlist order among equals.
        val channelResults = channels.sortedWith(compareBy({ it.tier }, { it.rank }, { it.length })).take(100).map { it.result }
        (eventResults + channelResults).also {
            Log.i(TAG, "search '$query': ${it.size} results in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
    }
    suspend fun channel(id: String): Channel? = db.channels().get(id)

    suspend fun upcoming(epgId: String, limit: Int = 2): List<ProgramEntity> =
        db.programs().upcoming(epgId, System.currentTimeMillis(), limit)

    suspend fun programCount(): Int = db.programs().count()

    suspend fun toggleFavorite(channel: Channel) {
        if (channel.favorite) {
            // A folded channel is a favorite if any copy is: clear them all.
            val ids = currentGrouping().sources[channel.id]?.map { it.id } ?: emptyList()
            (ids + channel.id).distinct().forEach { db.channels().removeFavorite(it) }
        } else {
            val dao = db.channels()
            dao.addFavorite(FavoriteEntity(channel.id, System.currentTimeMillis(), dao.nextFavoritePosition()))
        }
    }

    suspend fun removeFavorite(channelId: String) = db.channels().removeFavorite(channelId)

    /**
     * Moves a favorite up (delta < 0) or down past its visible neighbours. Favorites the current
     * playlist doesn't have stay in the list (they come back if the channel does) but are skipped.
     */
    suspend fun moveFavorite(channelId: String, delta: Int) = categoryMutex.withLock {
        val dao = db.channels()
        val all = dao.favoriteIds()
        val present = channels.first().mapNotNullTo(HashSet()) { if (it.favorite) it.id else null }
        val visible = all.filter { it in present }
        val moved = visible.moved(visible.indexOf(channelId), delta) ?: return@withLock
        // Put the reordered visible favorites back into the slots visible favorites occupied.
        val queue = ArrayDeque(moved)
        dao.reorderFavorites(all.map { if (it in present) queue.removeFirst() else it })
    }

    /** Sets up from a code shown on tv.thecoxhome.com: fetches the links, then connects. */
    /**
     * Sets up from a code: links this app with the stream server and loads the playlist. An
     * Xtream login already in use is kept (its favorites are saved by Xtream channel), so the
     * code then only links the app; returns null in that case, else the channel count.
     */
    suspend fun connectWithSetupCode(code: String, device: SetupCodes.Device): Int? {
        val links = SetupCodes.redeem(http, code, device)
        if (links.deviceToken.isNotBlank()) settings.setDeviceLink(links.deviceToken, links.deviceName)
        if (settings.config().hasXtream) return null
        return connect(SourceConfig(m3uUrl = links.m3u, epgUrl = links.epg))
    }

    /** Validates the sources by loading their channel lists, then saves the config. */
    suspend fun connect(config: SourceConfig): Int {
        val count = refreshChannels(config)
        settings.saveConfig(config)
        return count
    }

    private val loadingCount = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** True while the channel list is being (re)loaded, e.g. right after a setup code. */
    val channelsLoading: Flow<Boolean> = loadingCount.map { it > 0 }

    suspend fun refreshChannels(config: SourceConfig? = null): Int {
        loadingCount.update { it + 1 }
        try {
            return doRefreshChannels(config)
        } finally {
            loadingCount.update { it - 1 }
        }
    }

    private suspend fun doRefreshChannels(config: SourceConfig?): Int = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val cfg = config ?: settings.config()
        if (!cfg.isConfigured) throw IOException("No source configured")

        val fresh = ArrayList<ChannelEntity>()
        if (cfg.hasXtream) {
            val xtream = XtreamClient(http, cfg.xtreamServer, cfg.xtreamUser, cfg.xtreamPass)
            xtream.authenticate()
            fresh += xtream.liveChannels()
        }
        var detectedEpg: String? = null
        if (cfg.hasM3u && !cfg.hasXtream && link.token() != null) {
            // Linked: the stream server's ready-made list (a few hundred KB instead of the
            // provider's 20 MB playlist), skipped entirely when it hasn't changed.
            val etag = if (channelCount() > 0) settings.channelsEtag() else null
            val server = runCatching { link.download("/channels.json", etag) { readServerChannels(it, cfg.m3uUrl) } }
                .onFailure { Log.w(TAG, "ready-made channel list unavailable; loading the playlist", it) }
            if (server.isSuccess && server.getOrNull() == null) {
                Log.i(TAG, "channel list unchanged")
                settings.setLastChannelRefresh(System.currentTimeMillis())
                return@withContext channelCount()
            }
            server.getOrNull()?.takeIf { it.entries.isNotEmpty() }?.let { list ->
                val count = saveChannels(m3uEntities(list.entries, 0), list.epgUrl, started)
                settings.setChannelsEtag(list.etag)
                return@withContext count
            }
        }
        // Not on the server's list (any more): its ready-made guide may not fit this playlist.
        settings.setChannelsEtag(null)
        if (cfg.hasM3u) {
            val result = http.getStream(cfg.m3uUrl.trim()) { M3uParser.parse(it.bufferedReader()) }
            detectedEpg = result.epgUrl
            fresh += m3uEntities(result.entries, fresh.size)
        }
        saveChannels(fresh, detectedEpg, started)
    }

    private fun m3uEntities(entries: List<M3uParser.Entry>, offset: Int) = entries.mapIndexed { i, e ->
        ChannelEntity(
            id = "m:${e.url}",
            sortOrder = offset + i,
            number = e.chno?.toDoubleOrNull()?.toInt() ?: (offset + i + 1),
            name = e.name.ifBlank { e.tvgName ?: "Channel ${i + 1}" },
            logo = e.logo,
            groupName = e.group ?: "Uncategorized",
            streamUrl = e.url,
            epgIdRaw = e.tvgId,
            epgId = e.tvgId,
        )
    }

    private suspend fun saveChannels(fresh: List<ChannelEntity>, detectedEpg: String?, started: Long): Int {
        if (fresh.isEmpty()) throw IOException("No live channels found")
        // Keep EPG ids that a previous guide import remapped, as long as the source id is unchanged.
        val old = db.channels().allEntities().associateBy { it.id }
        val merged = fresh.map { n ->
            val o = old[n.id]
            if (o != null && o.epgIdRaw == n.epgIdRaw && o.epgId != null) n.copy(epgId = o.epgId) else n
        }
        db.channels().replaceAll(merged)
        settings.setDetectedEpg(detectedEpg)
        settings.setLastChannelRefresh(System.currentTimeMillis())
        invalidateSearch(channels = true)
        Log.i(TAG, "channels loaded: ${merged.size} in ${(System.nanoTime() - started) / 1_000_000} ms")
        return merged.size
    }

    private suspend fun channelCount(): Int = db.channels().count()

    suspend fun hasChannels(): Boolean = channelCount() > 0

    private class ServerChannels(val entries: List<M3uParser.Entry>, val epgUrl: String?, val etag: String)

    /**
     * The stream server's ready-made list: {"m3u": tag, "epg": url, "channels": [[name, url,
     * tvg-id, logo, group, chno], ...]}. Empty when it was built from a different playlist
     * than this app's (then the app loads its own).
     */
    private fun readServerChannels(d: DeviceLink.Download, m3uUrl: String): ServerChannels {
        val tag = java.security.MessageDigest.getInstance("SHA-256").digest(m3uUrl.trim().toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        val entries = ArrayList<M3uParser.Entry>(25_000)
        var epg: String? = null
        android.util.JsonReader(d.body.bufferedReader()).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "m3u" -> if (r.nextString() != tag) return ServerChannels(emptyList(), null, d.etag)
                    "epg" -> epg = r.nextString().ifBlank { null }
                    "channels" -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            r.beginArray()
                            val v = Array<String?>(6) { null }
                            var i = 0
                            while (r.hasNext()) {
                                v[i.coerceAtMost(5)] = if (r.peek() == android.util.JsonToken.NULL) { r.nextNull(); null } else r.nextString()
                                i++
                            }
                            r.endArray()
                            entries += M3uParser.Entry(name = v[0].orEmpty(), url = v[1].orEmpty(), tvgId = v[2], tvgName = null,
                                logo = v[3], group = v[4], chno = v[5])
                        }
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
        return ServerChannels(entries, epg, d.etag)
    }

    private val epgMutex = Mutex()

    /** Downloads every configured XMLTV source and atomically replaces the guide. Returns program count. */
    suspend fun refreshEpg(): Int = epgMutex.withLock {
        withContext(Dispatchers.IO) { doRefreshEpg() }
    }

    private suspend fun doRefreshEpg(): Int {
        val cfg = settings.config()
        if (!cfg.isConfigured) return 0
        if (link.token() != null && settings.channelsEtag() != null) {
            // Linked and on the server's channel list (so the same playlist): the ready-made 36-hour guide (a few MB, already matched
            // to the playlist) instead of downloading and parsing the provider's ~100 MB XMLTV.
            try {
                return importServerGuide()
            } catch (e: Exception) {
                Log.w(TAG, "ready-made guide unavailable; loading the provider's guide", e)
            }
        }
        val urls = buildList {
            if (cfg.hasXtream) add(XtreamClient(http, cfg.xtreamServer, cfg.xtreamUser, cfg.xtreamPass).epgUrl())
            if (cfg.hasM3u) {
                val epg = cfg.epgUrl.ifBlank { settings.detectedEpg().orEmpty() }
                if (epg.isNotBlank()) add(epg.trim())
            }
        }
        if (urls.isEmpty()) return 0

        // Download first so a network failure never wipes the existing guide.
        val files = ArrayList<File>()
        var lastError: Exception? = null
        urls.forEachIndexed { i, url ->
            try {
                files += http.download(url, File(context.cacheDir, "epg_$i.tmp"))
            } catch (e: Exception) {
                Log.w(TAG, "EPG download failed: $url", e)
                lastError = e
            }
        }
        if (files.isEmpty()) throw lastError ?: IOException("Guide download failed")

        try {
            val count = importEpg(files)
            invalidateSearch(channels = false)
            settings.setLastEpgRefresh(System.currentTimeMillis())
            return count
        } finally {
            files.forEach { it.delete() }
        }
    }

    private suspend fun importServerGuide(): Int {
        val started = System.nanoTime()
        val etag = if (db.programs().count() > 0) settings.guideEtag() else null
        var total = 0
        val newEtag = link.download("/guide.json?hours=36", etag) { d ->
            db.runInTransaction {
                db.programs().clearBlocking()
                val batch = ArrayList<ProgramEntity>(2000)
                android.util.JsonReader(d.body.bufferedReader()).use { r ->
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() != "programs") { r.skipValue(); continue }
                        r.beginObject()
                        while (r.hasNext()) {
                            val key = r.nextName()
                            r.beginArray()
                            while (r.hasNext()) {
                                r.beginArray()
                                val start = r.nextLong() * 1000
                                val end = r.nextLong() * 1000
                                val title = r.nextString()
                                val desc = if (r.hasNext()) r.nextString() else ""
                                while (r.hasNext()) r.skipValue()
                                r.endArray()
                                batch += ProgramEntity(epgId = key, startMs = start, endMs = end, title = title,
                                    description = desc.ifBlank { null })
                                if (batch.size >= 2000) {
                                    db.programs().insertBlocking(batch)
                                    total += batch.size
                                    batch.clear()
                                }
                            }
                            r.endArray()
                        }
                        r.endObject()
                    }
                    r.endObject()
                }
                if (batch.isNotEmpty()) {
                    db.programs().insertBlocking(batch)
                    total += batch.size
                }
                // The server keys programmes by playlist tvg-id, or "#<stream id>" for channels
                // it matched by name; point every channel at its key.
                val dao = db.channels()
                for (ch in dao.allEntitiesBlocking()) {
                    val key = ch.epgIdRaw?.takeIf { it.isNotBlank() } ?: DeviceLink.serverId(ch.streamUrl)?.let { "#$it" }
                    if (key != null && key != ch.epgId) dao.setEpgIdBlocking(ch.id, key)
                }
            }
            d.etag
        }
        if (newEtag == null) {
            Log.i(TAG, "guide unchanged")
        } else {
            settings.setGuideEtag(newEtag)
            invalidateSearch(channels = true)
            Log.i(TAG, "ready-made guide: $total programs in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
        settings.setLastEpgRefresh(System.currentTimeMillis())
        return if (newEtag == null) db.programs().count() else total
    }

    private fun importEpg(files: List<File>): Int {
        val started = System.nanoTime()
        val now = System.currentTimeMillis()
        val minEnd = now - 2 * HOUR
        // Big providers ship ~2 days for thousands of channels; 36h keeps import time and
        // database size reasonable on a Fire Stick (the guide refreshes every 6h).
        val maxStart = now + 36 * HOUR
        var total = 0
        db.runInTransaction {
            db.programs().clearBlocking()
            val matched = HashSet<String>()
            var failures = 0
            for (file in files) {
                try {
                    val batch = ArrayList<ProgramEntity>(1000)
                    openMaybeGzip(file).use { input ->
                        XmltvParser.parse(
                            input,
                            onChannels = { xml -> remapEpgIds(xml, matched) },
                            onProgramme = { p ->
                                if (p.endMs > minEnd && p.startMs < maxStart) {
                                    batch += p
                                    if (batch.size >= 1000) {
                                        db.programs().insertBlocking(batch)
                                        total += batch.size
                                        batch.clear()
                                    }
                                }
                            },
                        )
                    }
                    if (batch.isNotEmpty()) {
                        db.programs().insertBlocking(batch)
                        total += batch.size
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "EPG parse failed for ${file.name}", e)
                    failures++
                }
            }
            if (failures == files.size) throw IOException("Could not read guide data")
        }
        Log.i(TAG, "EPG imported: $total programs in ${(System.nanoTime() - started) / 1_000_000} ms")
        return total
    }

    /**
     * Points channels at XMLTV ids. Channels whose id is missing or unknown are matched by
     * normalized display name. Returns the XMLTV ids that any channel uses.
     */
    private fun remapEpgIds(xml: Map<String, List<String>>, matched: MutableSet<String>): Set<String> {
        val byName = HashMap<String, String>()
        for ((id, names) in xml) {
            names.forEach { byName.putIfAbsent(normalize(it), id) }
            byName.putIfAbsent(normalize(id), id)
        }
        val dao = db.channels()
        val wanted = HashSet<String>()
        for (ch in dao.allEntitiesBlocking()) {
            val current = ch.epgId
            if (current != null && current in xml) {
                wanted += current
                continue
            }
            if (current != null && current in matched) continue // satisfied by an earlier source
            val match = ch.epgIdRaw?.let { byName[normalize(it)] } ?: byName[normalize(ch.name)]
            if (match != null) {
                if (match != current) dao.setEpgIdBlocking(ch.id, match)
                wanted += match
            }
        }
        matched += wanted
        return wanted
    }

    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Must match the REPLACE chain in the search queries (Daos.kt). */
    private fun searchKey(s: String) =
        s.lowercase().replace(" ", "").replace("-", "").replace(".", "").replace("'", "").trim()

    private fun minuteTicker(): Flow<Long> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(now)
            delay(60_000 - now % 60_000 + 50)
        }
    }
}
