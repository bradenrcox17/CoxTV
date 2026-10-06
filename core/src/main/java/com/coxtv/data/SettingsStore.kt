package com.coxtv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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
