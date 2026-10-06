package com.coxtv.mobile

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.coxtv.mobile.ui.MobileRoot
import com.coxtv.mobile.ui.theme.CoxMobileTheme

class MainActivity : ComponentActivity() {
    /** True while the activity is shown as a picture-in-picture window. */
    val inPip = mutableStateOf(false)

    /** Set by the player screen; picture-in-picture is only offered while a channel plays. */
    var playerActive = false
        set(value) {
            field = value
            updatePipParams()
        }

    val pipSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature("android.software.picture_in_picture")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always-dark app: light status/navigation bar icons regardless of the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val container = (application as CoxMobileApp).container
        setContent {
            CoxMobileTheme {
                MobileRoot(container, this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Carries on an update install that was waiting for the "install unknown apps" permission.
        (application as CoxMobileApp).container.updates.onResume(this)
    }

    fun enterPip() {
        if (!pipSupported) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    // Android 8-11: go to picture-in-picture when the user presses Home during playback.
    // Android 12+ does this itself via setAutoEnterEnabled (smoother animation).
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (playerActive && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    private fun updatePipParams() {
        if (pipSupported) runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(playerActive)
        return builder.build()
    }
}
