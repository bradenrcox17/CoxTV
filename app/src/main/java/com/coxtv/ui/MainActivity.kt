package com.coxtv.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.coxtv.CoxTvApp
import com.coxtv.ui.components.FitToScreen
import com.coxtv.ui.theme.CoxTvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full screen like a TV: on phones, hide the status and navigation bars (swipe to peek).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val container = (application as CoxTvApp).container
        setContent {
            CoxTvTheme {
                FitToScreen {
                    AppRoot(container)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Carries on an update install that was waiting for the "install unknown apps" permission.
        (application as CoxTvApp).container.updates.onResume(this)
    }
}
