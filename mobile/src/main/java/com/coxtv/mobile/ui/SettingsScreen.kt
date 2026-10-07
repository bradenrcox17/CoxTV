package com.coxtv.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
fun SettingsScreen(container: AppContainer, onEditSources: () -> Unit, onOrganize: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fmt = rememberTimeFormatter()
    val lastEpg by container.settings.lastEpgRefresh.collectAsStateWithLifecycle(0L)
    val epgUpdating by remember { EpgRefreshWorker.isRunning(context) }.collectAsStateWithLifecycle(false)
    var channelStatus by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val linked by container.settings.isLinked.collectAsStateWithLifecycle(false)
    val deviceName by container.settings.deviceName.collectAsStateWithLifecycle("")
    val useServer by container.settings.useServer.collectAsStateWithLifecycle(true)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp))

        Item(Icons.Filled.Edit, "Edit sources", "Change your playlist (M3U), guide or Xtream login", onEditSources)
        if (linked) {
            ListItem(
                modifier = Modifier.clickable { scope.launch { container.settings.setUseServer(!useServer) } },
                colors = ListItemDefaults.colors(containerColor = CoxColors.Bg),
                leadingContent = { Icon(Icons.Filled.Share, contentDescription = null) },
                headlineContent = { Text("Play through stream server") },
                supportingContent = {
                    Text(
                        "Linked as \"$deviceName\". " + if (useServer) "Shares one provider connection per channel with your TVs and tv.thecoxhome.com."
                        else "Off: channels play straight from the provider.",
                        color = CoxColors.TextDim,
                    )
                },
                trailingContent = { Switch(checked = useServer, onCheckedChange = { on -> scope.launch { container.settings.setUseServer(on) } }) },
            )
        }
        Item(
            Icons.AutoMirrored.Filled.List,
            "Categories & favorites",
            "Choose which categories show, their order, and the order of your favorites",
            onOrganize,
        )
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
