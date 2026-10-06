package com.coxtv

import android.content.Context
import android.util.Log
import com.coxtv.data.SettingsStore
import com.coxtv.data.TvRepository
import com.coxtv.data.db.AppDatabase
import com.coxtv.data.remote.Http
import com.coxtv.update.UpdateManager
import com.coxtv.work.EpgRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Implemented by each app's Application class so shared code (workers) can reach the container. */
interface CoxTvApplication {
    val container: AppContainer
}

/** Which app this is, for the update checker: the APK asset to fetch from GitHub Releases. */
data class AppInfo(
    val versionName: String,
    val githubRepo: String,
    val apkAssetName: String,
)

class AppContainer(private val context: Context, appInfo: AppInfo) {
    val settings = SettingsStore(context)
    val database = AppDatabase.create(context)
    val repository = TvRepository(context, database, settings, Http.client)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val updates = UpdateManager(context, Http.client, appInfo, appScope)

    private var startupDone = false

    /** On cold start: quietly re-sync the channel list and refresh the guide if it is stale. */
    fun startupSync() {
        if (startupDone) return
        startupDone = true
        appScope.launch {
            if (!settings.config().isConfigured) return@launch
            val stale = System.currentTimeMillis() - settings.lastEpgRefresh() > 6 * 3_600_000L
            if (stale || repository.programCount() == 0) EpgRefreshWorker.refreshNow(context)
            // The channel list lives in Room, so launches are instant; re-sync it in the
            // background only when it's over 12 hours old (big playlists take a while).
            if (System.currentTimeMillis() - settings.lastChannelRefresh() < 12 * 3_600_000L) return@launch
            delay(8_000) // let playback start first
            runCatching { repository.refreshChannels() }
                .onFailure { Log.w("CoxTV", "Background channel refresh failed", it) }
        }
    }
}
