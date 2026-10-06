package com.coxtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.coxtv.AppContainer
import com.coxtv.ui.components.CoxWordmark
import com.coxtv.ui.guide.GuideScreen
import com.coxtv.ui.home.HomeScreen
import com.coxtv.ui.login.LoginScreen
import com.coxtv.ui.player.PlayerScreen
import com.coxtv.ui.search.SearchScreen
import com.coxtv.ui.theme.CoxColors
import com.coxtv.ui.update.UpdateDialog
import com.coxtv.work.EpgRefreshWorker

sealed interface Screen {
    val key: String

    data object Loading : Screen { override val key = "loading" }
    data class Login(val canGoBack: Boolean) : Screen { override val key = "login" }
    data object Home : Screen { override val key = "home" }
    data class Guide(val category: String) : Screen { override val key = "guide" }
    data object Search : Screen { override val key = "search" }
    data class Player(val channelId: String, val category: String) : Screen { override val key = "player" }
}

@Composable
fun AppRoot(container: AppContainer) {
    val context = LocalContext.current
    var stack by remember { mutableStateOf<List<Screen>>(listOf(Screen.Loading)) }
    val stateHolder = rememberSaveableStateHolder()

    fun push(screen: Screen) {
        stack = stack + screen
    }

    fun pop() {
        if (stack.size <= 1) return
        val popped = stack.last()
        stack = stack.dropLast(1)
        if (stack.none { it.key == popped.key }) stateHolder.removeState(popped.key)
    }

    LaunchedEffect(Unit) {
        val config = container.settings.config()
        stack = if (!config.isConfigured) {
            listOf(Screen.Login(canGoBack = false))
        } else {
            container.startupSync()
            container.updates.checkOnLaunch()
            val last = container.settings.lastWatched()
            if (last != null && container.repository.channel(last.channelId) != null) {
                listOf(Screen.Home, Screen.Player(last.channelId, last.category))
            } else {
                listOf(Screen.Home)
            }
        }
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    Box(Modifier.fillMaxSize().background(CoxColors.Bg)) {
        val screen = stack.last()
        stateHolder.SaveableStateProvider(screen.key) {
            when (screen) {
                Screen.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CoxWordmark(48.sp)
                }

                is Screen.Login -> LoginScreen(
                    container = container,
                    onConnected = {
                        EpgRefreshWorker.refreshNow(context)
                        stateHolder.removeState(Screen.Home.key)
                        stack = listOf(Screen.Home)
                    },
                )

                Screen.Home -> HomeScreen(
                    container = container,
                    onPlay = { id, category -> push(Screen.Player(id, category)) },
                    onOpenGuide = { category -> push(Screen.Guide(category)) },
                    onOpenSearch = { push(Screen.Search) },
                    onEditSources = { push(Screen.Login(canGoBack = true)) },
                    onCheckUpdates = { container.updates.check() },
                )

                Screen.Search -> SearchScreen(
                    container = container,
                    onPlay = { id, group -> push(Screen.Player(id, group)) },
                )

                is Screen.Guide -> GuideScreen(
                    container = container,
                    category = screen.category,
                    onPlay = { id -> push(Screen.Player(id, screen.category)) },
                )

                is Screen.Player -> PlayerScreen(
                    container = container,
                    channelId = screen.channelId,
                    category = screen.category,
                    onOpenGuide = { category ->
                        // Replace the player with the guide so Back from the guide doesn't loop.
                        val below = stack.getOrNull(stack.size - 2)
                        stack = if (below is Screen.Guide) stack.dropLast(1)
                        else stack.dropLast(1) + Screen.Guide(category)
                    },
                )
            }
        }
        UpdateDialog(container.updates)
    }
}
