package com.coxtv.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.coxtv.AppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A GitHub release that carries this app's APK. */
data class Release(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState // progress < 0: unknown
    data class NeedsPermission(val release: Release) : UpdateState
    data class ReadyToInstall(val release: Release) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Checks GitHub Releases for a newer build of this app, downloads the APK and hands it to the
 * system installer. The UI shows [state] and calls [download] / [install] / [dismiss].
 */
class UpdateManager(
    private val context: Context,
    private val http: OkHttpClient,
    private val app: AppInfo,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var launchCheckDone = false
    private var job: Job? = null
    private val apkFile get() = File(File(context.cacheDir, "updates"), app.apkAssetName)

    val currentVersion: String get() = app.versionName

    /** Called when the app starts: only speaks up if there is something newer. */
    fun checkOnLaunch() {
        if (launchCheckDone) return
        launchCheckDone = true
        check(userInitiated = false)
    }

    /** "Check for updates": also reports "up to date" and errors. */
    fun check(userInitiated: Boolean = true) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (userInitiated) _state.value = UpdateState.Checking
            _state.value = try {
                val release = fetchLatest()
                when {
                    release != null && isNewer(release.version, app.versionName) -> UpdateState.Available(release)
                    userInitiated -> UpdateState.UpToDate
                    else -> UpdateState.Idle
                }
            } catch (e: Exception) {
                Log.w("CoxTV", "Update check failed", e)
                if (userInitiated) UpdateState.Failed("Couldn't check for updates: ${e.message ?: e.javaClass.simpleName}")
                else UpdateState.Idle
            }
        }
    }

    fun dismiss() {
        job?.cancel()
        _state.value = UpdateState.Idle
    }

    fun download() {
        val release = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.Failed -> return
            else -> return
        }
        job?.cancel()
        job = scope.launch {
            _state.value = UpdateState.Downloading(release, -1f)
            _state.value = try {
                downloadApk(release)
                UpdateState.ReadyToInstall(release)
            } catch (e: Exception) {
                Log.w("CoxTV", "Update download failed", e)
                UpdateState.Failed("Download failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Opens the system installer. If this app isn't yet allowed to install apps, opens that
     * setting instead; [onResume] carries on once the user comes back.
     */
    fun install(activity: Activity) {
        val release = when (val s = _state.value) {
            is UpdateState.ReadyToInstall -> s.release
            is UpdateState.NeedsPermission -> s.release
            else -> return
        }
        if (!canInstall()) {
            _state.value = UpdateState.NeedsPermission(release)
            openInstallPermissionSettings(activity)
            return
        }
        _state.value = UpdateState.ReadyToInstall(release)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apkFile)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            _state.value = UpdateState.Failed("This device has no app installer available.")
        }
    }

    /** Call from Activity.onResume: finishes an install that was waiting for permission. */
    fun onResume(activity: Activity) {
        if (_state.value is UpdateState.NeedsPermission && canInstall()) install(activity)
    }

    private fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            // Fire OS 6 / Android 7: one global "Apps from Unknown Sources" switch.
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
        }

    private fun openInstallPermissionSettings(activity: Activity) {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            }
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    private suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${app.githubRepo}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { resp ->
            if (resp.code == 404) return@withContext null // no releases yet
            if (!resp.isSuccessful) throw IOException("GitHub returned HTTP ${resp.code}")
            val json = JSONObject(resp.body.string())
            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name") == app.apkAssetName) {
                    return@withContext Release(
                        version = json.optString("tag_name").removePrefix("v"),
                        // The release page's "which file do I download" guide follows this marker.
                        notes = json.optString("body").substringBefore("<!-- downloads -->").trim(),
                        apkUrl = asset.getString("browser_download_url"),
                        sizeBytes = asset.optLong("size", -1),
                    )
                }
            }
            null
        }
    }

    private suspend fun downloadApk(release: Release) = withContext(Dispatchers.IO) {
        val target = apkFile
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val total = resp.body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            resp.body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (isActive) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (done - lastReport > 256 * 1024) {
                            lastReport = done
                            _state.value = UpdateState.Downloading(release, if (total > 0) done.toFloat() / total else -1f)
                        }
                    }
                }
            }
        }
        // Make sure it really is an update for this app before offering to install it.
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(partial.path, 0)
        if (info?.packageName != context.packageName) {
            partial.delete()
            throw IOException("downloaded file isn't a CoxTV update")
        }
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) throw IOException("couldn't save the update")
    }

    companion object {
        /** "1.10.0" > "1.9.3"; ignores a leading "v" and any "-suffix". */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-')
                .split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
