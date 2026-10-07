package com.coxtv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray

data class SourceConfig(
    val xtreamServer: String = "",
    val xtreamUser: String = "",
    val xtreamPass: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = "",
) {
    val hasXtream get() = xtreamServer.isNotBlank() && xtreamUser.isNotBlank()
    val hasM3u get() = m3uUrl.isNotBlank()
    val isConfigured get() = hasXtream || hasM3u
}

data class LastWatched(val channelId: String, val category: String)

private val Context.dataStore by preferencesDataStore(name = "settings")

private val DEFAULT_CATEGORIES = listOf(Categories.FAVORITES, Categories.RECENT, Categories.SPORTS, Categories.ALL)
private const val RECENT_MAX = 20

private fun readList(raw: String): List<String> = JSONArray(raw).let { a -> List(a.length()) { a.getString(it) } }

/** Lists saved before Recent and Sports existed get them right after Favorites (once). */
private fun withNewBuiltIns(saved: List<String>): List<String> {
    val add = listOf(Categories.RECENT, Categories.SPORTS).filter { it !in saved }
    if (add.isEmpty()) return saved
    val at = saved.indexOf(Categories.FAVORITES).let { if (it < 0) 0 else it + 1 }
    return saved.take(at) + add + saved.drop(at)
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val server = stringPreferencesKey("xt_server")
        val user = stringPreferencesKey("xt_user")
        val pass = stringPreferencesKey("xt_pass")
        val m3u = stringPreferencesKey("m3u_url")
        val epg = stringPreferencesKey("epg_url")
        val detectedEpg = stringPreferencesKey("m3u_detected_epg")
        val lastChannel = stringPreferencesKey("last_channel")
        val lastCategory = stringPreferencesKey("last_category")
        val lastEpgRefresh = longPreferencesKey("last_epg_refresh")
        val lastChannelRefresh = longPreferencesKey("last_channel_refresh")
        val categories = stringPreferencesKey("categories")
        val categoriesV2 = booleanPreferencesKey("categories_v2") // Recent / Sports added
        val recent = stringPreferencesKey("recent")
        val hidden = stringPreferencesKey("hidden")
    }

    private val data get() = context.dataStore.data

    val config: Flow<SourceConfig> = data.map {
        SourceConfig(
            xtreamServer = it[Keys.server].orEmpty(),
            xtreamUser = it[Keys.user].orEmpty(),
            xtreamPass = it[Keys.pass].orEmpty(),
            m3uUrl = it[Keys.m3u].orEmpty(),
            epgUrl = it[Keys.epg].orEmpty(),
        )
    }

    val lastEpgRefresh: Flow<Long> = data.map { it[Keys.lastEpgRefresh] ?: 0L }

    /**
     * Categories the user turned on, in their order (see [Categories]). Out of the box only
     * Favorites and All Channels; playlist groups are added from Categories & favorites.
     */
    val categoryOrder: Flow<List<String>> = data.map { prefs ->
        val raw = prefs[Keys.categories] ?: return@map DEFAULT_CATEGORIES
        val saved = runCatching { readList(raw) }.getOrDefault(DEFAULT_CATEGORIES)
        if (prefs[Keys.categoriesV2] == true) saved else withNewBuiltIns(saved)
    }

    suspend fun setCategoryOrder(keys: List<String>) {
        context.dataStore.edit {
            it[Keys.categories] = JSONArray(keys.distinct()).toString()
            it[Keys.categoriesV2] = true
        }
    }

    /** Channels watched most recently, newest first (raw channel ids). */
    val recent: Flow<List<String>> = data.map { prefs -> prefs[Keys.recent]?.let { runCatching { readList(it) }.getOrNull() }.orEmpty() }

    suspend fun addRecent(channelId: String) {
        context.dataStore.edit { prefs ->
            val list = prefs[Keys.recent]?.let { runCatching { readList(it) }.getOrNull() }.orEmpty()
            prefs[Keys.recent] = JSONArray((listOf(channelId) + (list - channelId)).take(RECENT_MAX)).toString()
        }
    }

    /** Channels the user hid (raw ids; hiding one copy of a channel hides all its copies). */
    val hidden: Flow<Set<String>> = data.map { prefs -> prefs[Keys.hidden]?.let { runCatching { readList(it) }.getOrNull() }.orEmpty().toSet() }

    suspend fun setHidden(ids: Collection<String>, hide: Boolean) {
        context.dataStore.edit { prefs ->
            val set = prefs[Keys.hidden]?.let { runCatching { readList(it) }.getOrNull() }.orEmpty().toMutableSet()
            if (hide) set += ids else set -= ids.toSet()
            prefs[Keys.hidden] = JSONArray(set.toList()).toString()
        }
    }

    suspend fun config(): SourceConfig = config.first()

    suspend fun saveConfig(c: SourceConfig) {
        context.dataStore.edit {
            it[Keys.server] = c.xtreamServer.trim()
            it[Keys.user] = c.xtreamUser.trim()
            it[Keys.pass] = c.xtreamPass
            it[Keys.m3u] = c.m3uUrl.trim()
            it[Keys.epg] = c.epgUrl.trim()
            it.remove(Keys.lastEpgRefresh)
        }
    }

    suspend fun detectedEpg(): String? = data.first()[Keys.detectedEpg]

    suspend fun setDetectedEpg(url: String?) {
        context.dataStore.edit { if (url.isNullOrBlank()) it.remove(Keys.detectedEpg) else it[Keys.detectedEpg] = url }
    }

    suspend fun lastWatched(): LastWatched? {
        val p = data.first()
        val id = p[Keys.lastChannel] ?: return null
        return LastWatched(id, p[Keys.lastCategory].orEmpty())
    }

    suspend fun setLastWatched(channelId: String, category: String) {
        context.dataStore.edit {
            it[Keys.lastChannel] = channelId
            it[Keys.lastCategory] = category
        }
    }

    suspend fun lastEpgRefresh(): Long = lastEpgRefresh.first()

    suspend fun lastChannelRefresh(): Long = data.first()[Keys.lastChannelRefresh] ?: 0L

    suspend fun setLastChannelRefresh(time: Long) {
        context.dataStore.edit { it[Keys.lastChannelRefresh] = time }
    }

    suspend fun setLastEpgRefresh(time: Long) {
        context.dataStore.edit { it[Keys.lastEpgRefresh] = time }
    }
}
