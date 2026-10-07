package com.coxtv.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.coxtv.AppContainer
import com.coxtv.data.Categories
import com.coxtv.mobile.MainActivity
import com.coxtv.work.EpgRefreshWorker

private enum class Tab(val label: String, val icon: ImageVector) {
    Channels("Channels", Icons.AutoMirrored.Filled.List),
    Guide("Guide", Icons.Filled.DateRange),
    Search("Search", Icons.Filled.Search),
    Remote("Remote", Icons.AutoMirrored.Filled.Send),
    Settings("Settings", Icons.Filled.Settings),
}

private sealed interface Screen {
    data object Loading : Screen
    data class Setup(val canGoBack: Boolean) : Screen
    data object Main : Screen
    data class Organize(val tab: Int = 0) : Screen
    data class Player(val channelId: String, val category: String) : Screen
}

@Composable
fun MobileRoot(container: AppContainer, activity: MainActivity) {
    var stack by androidx.compose.runtime.remember { mutableStateOf<List<Screen>>(listOf(Screen.Loading)) }
    var tab by rememberSaveable { mutableStateOf(Tab.Channels) }
    // The category the channel list and guide show; also the zapping list in the player.
    var category by rememberSaveable { mutableStateOf<String?>(null) }

    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun play(channelId: String, cat: String) = push(Screen.Player(channelId, cat))

    LaunchedEffect(Unit) {
        val config = container.settings.config()
        stack = if (!config.isConfigured) {
            listOf(Screen.Setup(canGoBack = false))
        } else {
            container.startupSync()
            listOf(Screen.Main)
        }
        container.updates.checkOnLaunch()
    }

    BackHandler(enabled = stack.size > 1 || (stack.lastOrNull() == Screen.Main && tab != Tab.Channels)) {
        if (stack.size > 1) pop() else tab = Tab.Channels
    }

    Box(Modifier.fillMaxSize()) {
        when (val screen = stack.last()) {
            Screen.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

            is Screen.Setup -> SetupScreen(
                container = container,
                canGoBack = screen.canGoBack,
                onBack = ::pop,
                onConnected = {
                    EpgRefreshWorker.refreshNow(activity)
                    stack = listOf(Screen.Main)
                },
            )

            Screen.Main -> MainTabs(
                tab = tab,
                onTab = { tab = it },
            ) {
                val cat = category ?: Categories.ALL
                when (tab) {
                    Tab.Channels -> ChannelsScreen(
                        container = container,
                        category = category,
                        onCategory = { category = it },
                        onPlay = { id -> play(id, cat) },
                    )
                    Tab.Guide -> GuideScreen(
                        container = container,
                        category = cat,
                        onCategory = { category = it },
                        onPlay = { id -> play(id, cat) },
                    )
                    Tab.Search -> SearchScreen(container = container, onPlay = { id, group -> play(id, group) })
                    Tab.Remote -> RemoteScreen(container = container, onSetUp = { push(Screen.Setup(canGoBack = true)) })
                    Tab.Settings -> SettingsScreen(
                        container = container,
                        onEditSources = { push(Screen.Setup(canGoBack = true)) },
                        onOrganize = { push(Screen.Organize()) },
                        onFavoriteTeams = { push(Screen.Organize(tab = 3)) },
                    )
                }
            }

            is Screen.Organize -> OrganizeScreen(container = container, onBack = ::pop, initialTab = screen.tab)

            is Screen.Player -> PlayerScreen(
                container = container,
                activity = activity,
                channelId = screen.channelId,
                category = screen.category,
                onExit = ::pop,
            )
        }
        UpdateDialog(container.updates, activity)
    }
}

/** Bottom navigation bar on phones in portrait; a side rail on tablets and in landscape. */
@Composable
private fun MainTabs(tab: Tab, onTab: (Tab) -> Unit, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp
        if (useRail) {
            Scaffold { padding ->
                Row(Modifier.fillMaxSize().then(Modifier.padding(padding))) {
                    NavigationRail {
                        Tab.entries.forEach { t ->
                            NavigationRailItem(
                                selected = t == tab,
                                onClick = { onTab(t) },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxSize()) { content() }
                }
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = t == tab,
                                onClick = { onTab(t) },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().then(Modifier.padding(padding))) { content() }
            }
        }
    }
}
