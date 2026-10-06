package com.coxtv.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coxtv.AppContainer
import com.coxtv.mobile.ui.theme.CoxColors
import com.coxtv.work.EpgRefreshWorker
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, onEditSources: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fmt = rememberTimeFormatter()
    val lastEpg by container.settings.lastEpgRefresh.collectAsStateWithLifecycle(0L)
    val epgUpdating by remember { EpgRefreshWorker.isRunning(context) }.collectAsStateWithLifecycle(false)
    var channelStatus by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp))

        Item(Icons.Filled.Edit, "Edit sources", "Change your playlist (M3U), guide or Xtream login", onEditSources)
        Item(
            Icons.Filled.Refresh,
            if (refreshing) "Refreshing channels…" else "Refresh channels",
            channelStatus ?: "Reload the channel list from your provider",
        ) {
            if (refreshing) return@Item
            refreshing = true
            channelStatus = "Loading… large playlists can take a minute."
            scope.launch {
                channelStatus = try {
                    "Loaded ${container.repository.refreshChannels()} channels"
                } catch (e: Exception) {
                    "Refresh failed: ${e.message}"
                }
                refreshing = false
                EpgRefreshWorker.refreshNow(context)
            }
        }
        Item(
            Icons.Filled.Refresh,
            "Refresh TV guide",
            when {
                epgUpdating -> "Updating guide…"
                lastEpg > 0 -> "Last updated ${fmt.day(lastEpg)} ${fmt.time(lastEpg)}"
                else -> "Guide not loaded yet"
            },
        ) { EpgRefreshWorker.refreshNow(context) }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Item(Icons.Filled.Info, "Check for updates", "Installed version ${container.updates.currentVersion}") {
            container.updates.check()
        }
    }
}

@Composable
private fun Item(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = CoxColors.Bg),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, color = CoxColors.TextDim) },
    )
}
