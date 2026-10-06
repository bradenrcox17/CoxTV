package com.coxtv

import android.app.Application
import com.coxtv.work.EpgRefreshWorker

class CoxTvApp : Application(), CoxTvApplication {
    override lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(
            this,
            AppInfo(
                versionName = BuildConfig.VERSION_NAME,
                githubRepo = BuildConfig.GITHUB_REPO,
                apkAssetName = BuildConfig.APK_ASSET,
            ),
        )
        EpgRefreshWorker.schedulePeriodic(this)
    }
}
