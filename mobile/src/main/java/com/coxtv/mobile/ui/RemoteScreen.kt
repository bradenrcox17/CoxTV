package com.coxtv.mobile.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.coxtv.AppContainer
import com.coxtv.data.DeviceLink
import com.coxtv.data.db.Channel
import com.coxtv.mobile.ui.theme.CoxColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Remote control for the TVs linked to the stream server (Roku and Fire TV with CoxTV open). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteScreen(container: AppContainer, onSetUp: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val linked by container.settings.isLinked.collectAsStateWithLifecycle(false)
    var tvs by remember { mutableStateOf<List<DeviceLink.Tv>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current

    // Refresh the list (and what each TV is watching) every few seconds while on screen.
    LaunchedEffect(linked, lifecycle) {
        if (!linked) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try {
                    tvs = container.link.tvs()
                    problem = null
                } catch (e: Exception) {
                    problem = e.message ?: "Couldn't reach the stream server."
                }
                delay(4_000)
            }
        }
    }

    val list = tvs.orEmpty()
    val tv = list.firstOrNull { it.id == selectedId } ?: list.firstOrNull { it.online } ?: list.firstOrNull()

    fun send(cmd: String) {
        val target = tv ?: return
        scope.launch {
            runCatching { container.link.send(target, cmd) }
                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("Remote", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        if (!linked) {
            Text(
                "Control a Roku or Fire TV from here, and send channels to it. First link this phone: " +
                    "on tv.thecoxhome.com > Watch, choose Set up a TV, then enter the code here.",
                color = CoxColors.TextDim,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onSetUp) { Text("Enter setup code") }
            return@Column
        }
        when {
            tvs == null && problem == null -> Text("Looking for TVs…", color = CoxColors.TextDim)
            list.isEmpty() && problem == null -> Text(
                "No TVs linked yet. Set up the Roku or Fire TV with a code from tv.thecoxhome.com > Watch > Set up a TV " +
                    "(CoxTV 1.0.8 or newer).",
                color = CoxColors.TextDim,
            )
            else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                list.forEach { d ->
                    FilterChip(
                        selected = tv?.id == d.id,
                        onClick = { selectedId = d.id },
                        label = { Text(if (d.online) d.name else "${d.name} (off)") },
                    )
                }
            }
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        if (tv != null) {
            Text(
                when {
                    !tv.online -> "${tv.name} isn't available. Open CoxTV on it."
                    tv.playing.isNotBlank() -> "Watching ${tv.playing}"
                    else -> "On, not watching anything"
                },
                color = CoxColors.TextDim,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            val enabled = tv.online
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PadButton(Icons.Filled.KeyboardArrowUp, "Channel up", enabled, Modifier.weight(1f)) { send("up") }
                    PadButton(Icons.Filled.Refresh, "Previous", enabled, Modifier.weight(1f)) { send("prev") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PadButton(Icons.Filled.KeyboardArrowDown, "Channel down", enabled, Modifier.weight(1f)) { send("down") }
                    PadButton(Icons.Filled.Close, "Stop", enabled, Modifier.weight(1f)) { send("stop") }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "To send a channel to the TV, press and hold it in Channels or Search, then pick the TV.",
                style = MaterialTheme.typography.bodySmall,
                color = CoxColors.TextDim,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PadButton(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.height(76.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** TVs with CoxTV open, for "Play on <TV>" (refreshed while the screen is on). */
@Composable
fun rememberOnlineTvs(container: AppContainer): List<DeviceLink.Tv> {
    val linked by container.settings.isLinked.collectAsStateWithLifecycle(false)
    var tvs by remember { mutableStateOf<List<DeviceLink.Tv>>(emptyList()) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(linked, lifecycle) {
        if (!linked) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                runCatching { container.link.tvs() }.onSuccess { list -> tvs = list.filter { it.online } }
                delay(30_000)
            }
        }
    }
    return if (linked) tvs else emptyList()
}

/** Sends a channel to a TV, with a short confirmation. */
fun castChannel(container: AppContainer, context: Context, tv: DeviceLink.Tv, channel: Channel) {
    container.appScope.launch {
        val result = runCatching { container.link.send(tv, "play", channel) }
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                result.exceptionOrNull()?.message ?: "Playing ${channel.name} on ${tv.name}",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}
