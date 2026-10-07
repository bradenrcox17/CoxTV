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

    /** Built-in categories in their default order. */
    val BUILT_INS = listOf(FAVORITES, RECENT, SPORTS, ALL)

    fun label(key: String) = when (key) {
        FAVORITES -> "Favorites"
        ALL -> "All Channels"
        RECENT -> "Recent"
        SPORTS -> "Sports on now"
        else -> key
    }

    fun filter(channels: List<Channel>, key: String, extras: CategoryExtras = CategoryExtras.EMPTY): List<Channel> = when (key) {
        FAVORITES -> channels.filter { it.favorite }.sortedWith(compareBy({ it.favoritePosition }, { it.sortOrder }))
        RECENT -> extras.recent
        SPORTS -> extras.sports.map { it.channel }
        ALL, "" -> channels
        else -> channels.filter { it.groupName == key }
    }

    fun isBuiltIn(key: String) = key in BUILT_INS
}

/** A live game for "Sports on now". */
data class SportsGame(val channel: Channel, val title: String, val league: String, val endMs: Long)

/** Lists for the built-in categories that don't come straight from the channel table. */
data class CategoryExtras(val recent: List<Channel>, val sports: List<SportsGame>) {
    /** Row text for the Sports category: "NHL · Senators vs. Bruins". */
    val sportsLabels: Map<String, String> = sports.associate { it.channel.id to "${it.league} · ${it.title}" }

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

    /** Games on right now, one entry per game, by league then title; refreshed every minute. */
    val sports: Flow<List<SportsGame>> by lazy {
        combine(grouping, nowPlaying) { g, now -> findGames(g.channels, now) }.flowOn(Dispatchers.Default)
    }

    val categoryExtras: Flow<CategoryExtras> by lazy {
        combine(recentChannels, sports) { recent, games -> CategoryExtras(recent, games) }
    }

    private fun findGames(channels: List<Channel>, now: Map<String, ProgramEntity>): List<SportsGame> {
        val nowMs = System.currentTimeMillis()
        val seen = HashSet<String>()
        val games = ArrayList<SportsGame>()
        for (ch in channels) {
            val p = ch.epgId?.let { now[it] }
            val chText = ch.name + " " + ch.groupName
            val game = when {
                p != null && p.endMs > nowMs && Sports.isGame(p.title, chText) ->
                    SportsGame(ch, p.title, Sports.league(p.title, chText), p.endMs)
                p == null -> Sports.eventChannelTitle(ch.name, nowMs)?.let { SportsGame(ch, it, Sports.league(it, chText), 0L) }
                else -> null
            } ?: continue
            if (seen.add(Sports.gameKey(game.title))) games += game
        }
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
            (Categories.BUILT_INS + groups)
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
        val g = currentGrouping()
        val visible = g.visibleIds
        val events = ArrayList<SearchResult>()
        val eventSeen = HashSet<String>()
        for (i in airing.keys.indices) {
            if (airing.keys[i].contains(key)) {
                val h = airing.hits[i]
                val id = g.shownId[h.channelId] ?: h.channelId
                if (h.endMs > now && id in visible && eventSeen.add(id)) events += SearchResult(id, h.title, h.channelName, h.groupName, h.endMs)
                if (events.size >= 100) break
            }
        }
        events.sortBy { it.title.lowercase() }
        val seen = events.mapTo(HashSet()) { it.channelId }
        val channels = ArrayList<SearchResult>()
        for (i in names.keys.indices) {
            if (names.keys[i].contains(key)) {
                val r = names.rows[i]
                val id = g.shownId[r.channelId] ?: r.channelId
                if (id in seen || id !in visible) continue
                seen += id
                val p = airing.byChannel[r.channelId]?.takeIf { it.endMs > now }
                channels += if (p != null) SearchResult(id, p.title, r.channelName, r.groupName, p.endMs)
                else SearchResult(id, r.channelName, r.groupName, r.groupName, 0L)
                if (channels.size >= 100) break
            }
        }
        (events + channels).also {
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
    suspend fun connectWithSetupCode(code: String): Int {
        val links = SetupCodes.redeem(http, code)
        return connect(SourceConfig(m3uUrl = links.m3u, epgUrl = links.epg))
    }

    /** Validates the sources by loading their channel lists, then saves the config. */
    suspend fun connect(config: SourceConfig): Int {
        val count = refreshChannels(config)
        settings.saveConfig(config)
        return count
    }

    suspend fun refreshChannels(config: SourceConfig? = null): Int = withContext(Dispatchers.IO) {
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
        if (cfg.hasM3u) {
            val result = http.getStream(cfg.m3uUrl.trim()) { M3uParser.parse(it.bufferedReader()) }
            detectedEpg = result.epgUrl
            val offset = fresh.size
            result.entries.forEachIndexed { i, e ->
                fresh += ChannelEntity(
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
        }
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
        merged.size
    }

    private val epgMutex = Mutex()

    /** Downloads every configured XMLTV source and atomically replaces the guide. Returns program count. */
    suspend fun refreshEpg(): Int = epgMutex.withLock {
        withContext(Dispatchers.IO) { doRefreshEpg() }
    }

    private suspend fun doRefreshEpg(): Int {
        val cfg = settings.config()
        if (!cfg.isConfigured) return 0
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
