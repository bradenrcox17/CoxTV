package com.coxtv.mobile

import android.app.Application
import com.coxtv.AppContainer
import com.coxtv.AppInfo
import com.coxtv.CoxTvApplication
import com.coxtv.work.EpgRefreshWorker

class CoxMobileApp : Application(), CoxTvApplication {
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
