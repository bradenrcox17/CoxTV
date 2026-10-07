package com.coxtv.data

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps favorites (and their order), categories, hidden channels and favorite teams the same
 * on all of a person's linked devices and tv.thecoxhome.com, through the stream server.
 *
 * Each field carries the time it last changed; the newest change wins. The first time a
 * device syncs, favorites, hidden channels and teams from both sides are merged (categories
 * take the server's), so nothing set up on either side is lost.
 */
class PrefsSync(
    private val settings: SettingsStore,
    private val repo: TvRepository,
    private val link: DeviceLink,
) {
    private val mutex = Mutex()

    private data class Field(val read: suspend () -> Any, val write: suspend (Any) -> Unit, val merge: (Any, Any) -> Any)

    private val fields: Map<String, Field> = mapOf(
        "favorites" to Field(
            read = { repo.sharedFavorites() },
            write = { repo.applySharedFavorites(strings(it)) },
            merge = { server, local -> (strings(server) + strings(local)).distinct() },
        ),
        "categories" to Field(
            read = { settings.categoryOrder.first().map(Categories::toShared) },
            write = { v -> settings.setCategoryOrder(strings(v).map(Categories::fromShared)) },
            merge = { server, _ -> server },
        ),
        "hidden" to Field(
            read = { repo.sharedHidden() },
            write = { repo.applySharedHidden(strings(it)) },
            merge = { server, local -> (strings(server) + strings(local)).distinct() },
        ),
        "teams" to Field(
            read = { JSONObject(settings.teams.first()) },
            write = { v -> settings.setTeams(v.toString()) },
            merge = { server, local -> mergeTeams(server as JSONObject, local as JSONObject) },
        ),
    )

    /** Runs while the app is open: pull now and every few minutes, push local changes. */
    suspend fun run() {
        if (link.token() == null) return
        runCatching { syncNow() }.onFailure { Log.w(TAG, "settings sync failed", it) }
        localChanges().collect {
            runCatching { syncNow() }.onFailure { Log.w(TAG, "settings sync failed", it) }
        }
    }

    /** Re-checks the server every 5 minutes, and pushes whenever something changes here. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun localChanges(): Flow<Unit> = combine(
        listOf(settings.categoryOrder, settings.hidden, settings.teams, repo.favoritesVersion, repo.groups,
            ticker(5 * 60_000L)),
    ) { }.debounce(1_500)

    private fun ticker(ms: Long): Flow<Long> = kotlinx.coroutines.flow.flow {
        var n = 0L
        while (true) {
            emit(n++)
            delay(ms)
        }
    }

    suspend fun syncNow() = mutex.withLock {
        if (link.token() == null) return@withLock
        if (!repo.hasChannels()) return@withLock  // favorites map through the channel list
        val server = link.prefs() ?: return@withLock
        val state = JSONObject(settings.syncState())
        val push = JSONObject()
        val now = System.currentTimeMillis()
        for ((name, field) in fields) {
            val local = field.read()
            val localHash = hash(local)
            val srv = server.optJSONObject(name)
            val known = state.optJSONObject(name)
            val srvValue = srv?.opt("v")
            val srvT = srv?.optLong("t") ?: -1
            when {
                known == null -> {                               // first sync from this device
                    val hasLocal = !isEmpty(local) && !(name == "categories" && !hasSavedCategories())
                    when {
                        srvValue != null && hasLocal -> {
                            val merged = field.merge(fromJson(srvValue), local)
                            field.write(merged)
                            push.put(name, JSONObject().put("v", toJson(merged)).put("t", now))
                            state.put(name, JSONObject().put("t", now).put("h", hash(merged)))
                        }
                        srvValue != null -> {
                            field.write(fromJson(srvValue))
                            state.put(name, JSONObject().put("t", srvT).put("h", hash(field.read())))
                        }
                        hasLocal -> {
                            push.put(name, JSONObject().put("v", toJson(local)).put("t", now))
                            state.put(name, JSONObject().put("t", now).put("h", localHash))
                        }
                    }
                }
                localHash != known.optString("h") -> {           // changed here since the last sync
                    push.put(name, JSONObject().put("v", toJson(local)).put("t", now))
                    state.put(name, JSONObject().put("t", now).put("h", localHash))
                }
                srvValue != null && srvT > known.optLong("t") -> { // changed on another device
                    field.write(fromJson(srvValue))
                    state.put(name, JSONObject().put("t", srvT).put("h", hash(field.read())))
                }
            }
        }
        if (push.length() > 0) link.prefs(push)
        settings.setSyncState(state.toString())
    }

    private suspend fun hasSavedCategories(): Boolean = settings.hasSavedCategories()

    companion object {
        private const val TAG = "CoxTV"

        @Suppress("UNCHECKED_CAST")
        private fun strings(v: Any): List<String> = when (v) {
            is JSONArray -> List(v.length()) { v.optString(it) }
            is List<*> -> v.map { it.toString() }
            else -> emptyList()
        }

        private fun fromJson(v: Any): Any = when (v) {
            is JSONArray -> strings(v)
            else -> v
        }

        private fun toJson(v: Any): Any = when (v) {
            is List<*> -> JSONArray(v)
            else -> v
        }

        private fun isEmpty(v: Any) = when (v) {
            is List<*> -> v.isEmpty()
            is JSONObject -> v.length() == 0 || v.keys().asSequence().all { (v.optJSONArray(it)?.length() ?: 0) == 0 }
            else -> false
        }

        private fun hash(v: Any): String = toJson(v).toString().hashCode().toString()

        private fun mergeTeams(a: JSONObject, b: JSONObject): JSONObject {
            val out = JSONObject()
            for (src in listOf(a, b)) {
                for (sport in src.keys()) {
                    val have = out.optJSONArray(sport) ?: JSONArray().also { out.put(sport, it) }
                    val keys = List(have.length()) { have.getJSONObject(it).optString("key") }.toMutableSet()
                    val items = src.optJSONArray(sport) ?: continue
                    for (i in 0 until items.length()) {
                        val t = items.getJSONObject(i)
                        if (keys.add(t.optString("key"))) have.put(t)
                    }
                }
            }
            return out
        }
    }
}
