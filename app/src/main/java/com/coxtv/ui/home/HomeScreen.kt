@file:OptIn(ExperimentalComposeUiApi::class)

package com.coxtv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.coxtv.AppContainer
import com.coxtv.data.Categories
import com.coxtv.data.db.Channel
import com.coxtv.data.db.ProgramEntity
import com.coxtv.ui.components.Clock
import com.coxtv.ui.components.collectAsStateCompat
import com.coxtv.ui.components.FocusTile
import com.coxtv.ui.components.LocalIsTouch
import com.coxtv.ui.components.ProgressLine
import com.coxtv.ui.components.rememberNow
import com.coxtv.ui.components.rememberTimeFormatter
import com.coxtv.ui.theme.CoxColors
import com.coxtv.work.EpgRefreshWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    container: AppContainer,
    onPlay: (channelId: String, category: String) -> Unit,
    onOpenGuide: (category: String) -> Unit,
    onOpenSearch: () -> Unit,
    onEditSources: () -> Unit,
    onCheckUpdates: () -> Unit,
) {
    val repo = container.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fmt = rememberTimeFormatter()
    val now by rememberNow(30_000)

    val channels by repo.channels.collectAsStateCompat(null)
    val groups by repo.groups.collectAsStateCompat(emptyList())
    val nowPlaying by repo.nowPlaying.collectAsStateCompat(emptyMap())
    val epgUpdating by remember { EpgRefreshWorker.isRunning(context) }.collectAsStateCompat(false)
    val lastEpg by container.settings.lastEpgRefresh.collectAsStateCompat(0L)

    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCategory by remember { mutableStateOf<String?>(null) }
    // The category you actually opened (clicked, or moved into its list), as opposed to one
    // merely passed over in the sidebar.
    var anchorCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var scrolledFor by rememberSaveable { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    val allChannels = channels.orEmpty()
    val category = selected ?: Categories.ALL
    val categories = remember(groups) { listOf(Categories.FAVORITES, Categories.ALL) + groups }
    val visible = remember(allChannels, category) { Categories.filter(allChannels, category) }
    val counts = remember(allChannels) {
        allChannels.groupingBy { it.groupName }.eachCount() +
            mapOf(Categories.ALL to allChannels.size, Categories.FAVORITES to allChannels.count { it.favorite })
    }

    val listState = rememberLazyListState()
    val sideState = rememberLazyListState()
    val sideFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val lastChannelFocus = remember { FocusRequester() }
    var focusTargetId by remember { mutableStateOf<String?>(null) }
    var initialFocusDone by remember { mutableStateOf(false) }

    // Default category on first load: last watched category, else Favorites if any, else All.
    LaunchedEffect(channels != null) {
        if (selected != null || channels == null) return@LaunchedEffect
        val last = container.settings.lastWatched()?.category
        selected = when {
            last != null && (last == Categories.ALL || last in groups ||
                (last == Categories.FAVORITES && allChannels.any { it.favorite })) -> last
            allChannels.any { it.favorite } -> Categories.FAVORITES
            else -> Categories.ALL
        }
        anchorCategory = selected
    }

    // Debounced "select on focus" for categories so scrolling the sidebar stays smooth.
    LaunchedEffect(pendingCategory) {
        val p = pendingCategory ?: return@LaunchedEffect
        delay(250)
        selected = p
    }

    LaunchedEffect(category) {
        if (scrolledFor != null && scrolledFor != category) listState.scrollToItem(0)
        scrolledFor = category
    }

    // On entering: focus the last-watched channel if it's in this list, else the list / sidebar.
    LaunchedEffect(channels != null, selected) {
        if (initialFocusDone || channels == null || selected == null) return@LaunchedEffect
        initialFocusDone = true
        val lastId = container.settings.lastWatched()?.channelId
        val index = visible.indexOfFirst { it.id == lastId }
        withFrameNanos { }
        if (index >= 0) {
            focusTargetId = lastId
            val info = listState.layoutInfo.visibleItemsInfo
            if (info.none { it.index == index }) listState.scrollToItem((index - 3).coerceAtLeast(0))
            withFrameNanos { }
            withFrameNanos { }
            if (runCatching { lastChannelFocus.requestFocus() }.isFailure) runCatching { listFocus.requestFocus() }
        } else if (visible.isNotEmpty()) {
            runCatching { listFocus.requestFocus() }
        } else {
            runCatching { sideFocus.requestFocus() }
        }
    }

    fun refreshChannels() {
        if (refreshing) return
        refreshing = true
        status = "Refreshing channels…"
        scope.launch {
            status = try {
                val n = repo.refreshChannels()
                EpgRefreshWorker.refreshNow(context)
                "Loaded $n channels"
            } catch (e: Exception) {
                "Refresh failed: ${e.message}"
            }
            refreshing = false
        }
    }

    Row(Modifier.fillMaxSize().background(CoxColors.Bg).padding(horizontal = 24.dp, vertical = 20.dp)) {
        // ---- Sidebar ----
        Column(Modifier.width(232.dp).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("CoxTV", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = CoxColors.Accent)
                Spacer(Modifier.weight(1f))
                Clock()
            }
            LazyColumn(
                state = sideState,
                modifier = Modifier.weight(1f).focusRestorer().focusRequester(sideFocus),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Focusing an action restores the opened category, so passing over categories on
                // the way up to "TV Guide" doesn't change which category the guide opens.
                val cancelPending: () -> Unit = {
                    pendingCategory = null
                    anchorCategory?.let { selected = it }
                }
                item(key = "search") { SideItem("Search what's on", onFocused = cancelPending, onClick = onOpenSearch) }
                item(key = "guide") { SideItem("TV Guide", onFocused = cancelPending, onClick = { onOpenGuide(category) }) }
                item(key = "refresh") { SideItem(if (refreshing) "Refreshing…" else "Refresh channels", onFocused = cancelPending, onClick = ::refreshChannels) }
                item(key = "sources") { SideItem("Edit sources", onFocused = cancelPending, onClick = onEditSources) }
                item(key = "updates") { SideItem("Check for updates", onFocused = cancelPending, onClick = onCheckUpdates) }
                item(key = "hdr") {
                    Text(
                        "CATEGORIES",
                        style = MaterialTheme.typography.labelSmall,
                        color = CoxColors.TextDim,
                        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp),
                    )
                }
                items(categories, key = { "cat:$it" }) { key ->
                    SideItem(
                        label = Categories.label(key),
                        count = counts[key],
                        selected = key == category,
                        onFocused = { pendingCategory = key },
                        onClick = {
                            pendingCategory = null
                            selected = key
                            anchorCategory = key
                            runCatching { listFocus.requestFocus() }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.width(20.dp))

        // ---- Channel list ----
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp, start = 4.dp), verticalAlignment = Alignment.Bottom) {
                Text(Categories.label(category), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(12.dp))
                Text("${visible.size} channels", style = MaterialTheme.typography.bodyMedium, color = CoxColors.TextDim)
                Spacer(Modifier.weight(1f))
                Text(
                    status ?: when {
                        epgUpdating -> "Updating guide…"
                        lastEpg > 0 -> "Guide updated ${fmt.time(lastEpg)}"
                        else -> "Guide not loaded yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = CoxColors.TextDim,
                )
            }

            when {
                channels == null -> Unit
                visible.isEmpty() -> EmptyMessage(
                    if (category == Categories.FAVORITES) {
                        if (LocalIsTouch.current) "No favorites yet.\nPress and hold a channel to add it."
                        else "No favorites yet.\nPress ☰ Menu (or hold OK) on a channel to add it."
                    }
                    else "No channels in this category.",
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .onFocusChanged { if (it.hasFocus) anchorCategory = category }
                        .focusRestorer()
                        .focusRequester(listFocus),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(visible, key = { it.id }) { ch ->
                        val program = ch.epgId?.let { nowPlaying[it] }
                        ChannelRow(
                            channel = ch,
                            program = program,
                            now = now,
                            modifier = if (ch.id == focusTargetId) Modifier.focusRequester(lastChannelFocus) else Modifier,
                            onClick = { onPlay(ch.id, category) },
                            onToggleFavorite = { scope.launch { repo.toggleFavorite(ch) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SideItem(
    label: String,
    onClick: () -> Unit,
    count: Int? = null,
    selected: Boolean = false,
    onFocused: (() -> Unit)? = null,
) {
    FocusTile(
        onClick = onClick,
        selected = selected,
        modifier = Modifier.fillMaxWidth().height(40.dp)
            .onFocusChanged { if (it.isFocused) onFocused?.invoke() },
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (count != null) {
                Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    program: ProgramEntity?,
    now: Long,
    modifier: Modifier,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    FocusTile(
        onClick = onClick,
        onLongClick = onToggleFavorite,
        focusedScale = 1.01f,
        modifier = modifier.fillMaxWidth().height(62.dp).onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Menu) {
                onToggleFavorite(); true
            } else false
        },
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                channel.number.toString(),
                modifier = Modifier.width(48.dp),
                style = MaterialTheme.typography.titleMedium,
                color = LocalContentColor.current.copy(alpha = 0.7f),
            )
            Column(Modifier.weight(1f)) {
                Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (program != null) {
                    Text(
                        program.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    val fraction = (now - program.startMs).toFloat() / (program.endMs - program.startMs).coerceAtLeast(1)
                    ProgressLine(fraction, Modifier.width(220.dp))
                }
            }
            if (channel.favorite) {
                Text("★", color = CoxColors.Fav, fontSize = 20.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = CoxColors.TextDim, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
