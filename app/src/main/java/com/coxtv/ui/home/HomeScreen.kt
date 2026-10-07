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
import androidx.compose.runtime.rememberUpdatedState
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
import com.coxtv.data.CategoryExtras
import com.coxtv.data.Teams
import com.coxtv.data.db.Channel
import com.coxtv.data.db.ProgramEntity
import com.coxtv.ui.components.ChannelMenu
import com.coxtv.ui.components.Clock
import com.coxtv.ui.components.CoxWordmark
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
    onOrganize: () -> Unit,
    onEditSources: () -> Unit,
    onFavoriteTeams: () -> Unit,
    onCheckUpdates: () -> Unit,
) {
    val repo = container.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fmt = rememberTimeFormatter()
    val now by rememberNow(30_000)

    val channels by repo.channels.collectAsStateCompat(null)
    val shownCategories by repo.categories.collectAsStateCompat(null)
    val nowPlaying by repo.nowPlaying.collectAsStateCompat(emptyMap())
    val extras by repo.categoryExtras.collectAsStateCompat(CategoryExtras.EMPTY)
    var menuFor by remember { mutableStateOf<Channel?>(null) }
    // After hiding a channel, focus moves to the next one in the list (not the sidebar).
    var focusAfterHide by remember { mutableStateOf<String?>(null) }
    // After starring a team its games move to the top: show them (and keep focus in the list).
    var teamToggled by remember { mutableStateOf(false) }
    val cfbGuide by repo.cfbGuide.collectAsStateCompat(null)
    val allTeams by repo.allTeams.collectAsStateCompat(emptyMap())
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
    val categories = shownCategories.orEmpty()
    val visible = remember(allChannels, category, extras) { Categories.filter(allChannels, category, extras) }
    val visibleNow by rememberUpdatedState(visible)  // for coroutines that outlive a recomposition
    val counts = remember(allChannels, extras, cfbGuide) {
        allChannels.groupingBy { it.groupName }.eachCount() +
            extras.sports.groupingBy { Categories.LEAGUE + it.league }.eachCount() +
            Categories.LEAGUE_KEYS.associateWith { 0 }.filterKeys { k -> extras.sports.none { Categories.LEAGUE + it.league == k } } +
            mapOf(
                Categories.ALL to allChannels.size,
                Categories.FAVORITES to allChannels.count { it.favorite },
                Categories.RECENT to extras.recent.size,
                Categories.SPORTS to extras.sports.size,
            ) + (cfbGuide?.let { mapOf(Categories.CFB to it.rows.size) } ?: emptyMap())
    }

    val listState = rememberLazyListState()
    val sideState = rememberLazyListState()
    val sideFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val lastChannelFocus = remember { FocusRequester() }
    var focusTargetId by remember { mutableStateOf<String?>(null) }
    var initialFocusDone by remember { mutableStateOf(false) }

    // Default category on first load: last watched category, else Favorites if any, else All.
    LaunchedEffect(channels != null, shownCategories != null) {
        if (selected != null || channels == null || shownCategories == null) return@LaunchedEffect
        val last = container.settings.lastWatched()?.category
        val hasFavorites = allChannels.any { it.favorite }
        selected = when {
            last != null && last in categories && (last != Categories.FAVORITES || hasFavorites) -> last
            hasFavorites && Categories.FAVORITES in categories -> Categories.FAVORITES
            // Not an empty Recent / Sports list: All Channels, else the first playlist group.
            Categories.ALL in categories -> Categories.ALL
            else -> categories.firstOrNull { !Categories.isBuiltIn(it) } ?: categories.first()
        }
        anchorCategory = selected
    }

    // A category turned off in Categories & favorites: fall back to the first one still shown.
    LaunchedEffect(categories) {
        val current = selected ?: return@LaunchedEffect
        if (categories.isNotEmpty() && current !in categories) {
            selected = categories.first()
            anchorCategory = selected
        }
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

    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize().background(CoxColors.Bg).padding(horizontal = 24.dp, vertical = 20.dp)) {
        // ---- Sidebar ----
        Column(Modifier.width(232.dp).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CoxWordmark(28.sp)
                Spacer(Modifier.weight(1f))
                Clock()
            }
            LazyColumn(
                state = sideState,
                modifier = Modifier.weight(1f).focusRestorer().focusRequester(sideFocus)
                    // Right always goes into the list (the College Football guide may not reach this far down).
                    .onPreviewKeyEvent {
                        it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight &&
                            runCatching { listFocus.requestFocus() }.isSuccess
                    },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Focusing an action restores the opened category, so passing over categories on
                // the way up to "TV Guide" doesn't change which category the guide opens.
                val cancelPending: () -> Unit = {
                    pendingCategory = null
                    anchorCategory?.let { selected = it }
                }
                item(key = "search") { SideItem("Search what's on", onFocused = cancelPending, onClick = onOpenSearch) }
                item(key = "guide") { SideItem("TV Guide", onFocused = cancelPending, onClick = { onOpenGuide(if (category == Categories.CFB) Categories.ALL else category) }) }
                item(key = "organize") { SideItem("Categories & favorites", onFocused = cancelPending, onClick = onOrganize) }
                item(key = "teams") { SideItem("Favorite teams", onFocused = cancelPending, onClick = onFavoriteTeams) }
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
                            scope.launch { withFrameNanos { }; withFrameNanos { }; runCatching { listFocus.requestFocus() } }
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
                Text(
                    when {
                        category == Categories.CFB -> cfbGuide?.let { g -> "${g.rows.size} games" + (g.week?.let { " · week $it" } ?: "") }.orEmpty()
                        category == Categories.SPORTS || Categories.leagueOf(category) != null -> "${visible.size} live games"
                        else -> "${visible.size} channels"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = CoxColors.TextDim,
                )
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
                category == Categories.CFB -> CfbPane(
                    container = container,
                    listState = listState,
                    listFocus = listFocus,
                    onPlay = { id -> onPlay(id, Categories.ALL) },
                    onStatus = { status = it },
                )
                visible.isEmpty() -> EmptyMessage(
                    when {
                        category == Categories.FAVORITES ->
                            if (LocalIsTouch.current) "No favorites yet.\nPress and hold a channel, then Add to favorites."
                            else "No favorites yet.\nPress ☰ Menu (or hold OK) on a channel to add it."
                        category == Categories.RECENT -> "Channels you watch will show up here."
                        category == Categories.SPORTS -> if (lastEpg > 0) "No live games right now." else "Games show up here once the TV guide has loaded."
                        Categories.leagueOf(category) != null -> "No live ${Categories.leagueOf(category)} games right now."
                        else -> "No channels in this category."
                    },
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
                            label = when {
                                category == Categories.SPORTS -> extras.sportsLabels[ch.id]
                                Categories.leagueOf(category) != null -> extras.sports.firstOrNull { it.channel.id == ch.id }
                                    ?.let { (if (it.mine) "★ " else "") + it.title }
                                else -> null
                            },
                            now = now,
                            modifier = if (ch.id == focusTargetId) Modifier.focusRequester(lastChannelFocus) else Modifier,
                            onClick = { onPlay(ch.id, category) },
                            onOptions = { menuFor = ch },
                        )
                    }
                }
            }
        }
    }
    // On top of everything: channel options (☰ Menu / hold OK).
    menuFor?.let { ch ->
        // A game (Sports on now or a league): its teams can be starred.
        val game = if (category == Categories.SPORTS || Categories.leagueOf(category) != null)
            extras.sports.firstOrNull { it.channel.id == ch.id } else null
        ChannelMenu(
            channel = ch,
            teams = game?.let { Teams.inGame(it.title, it.league, allTeams) }.orEmpty(),
            teamSport = game?.let { Teams.sportFor(it.league) }.orEmpty(),
            onToggleTeam = { team ->
                teamToggled = true
                game?.let { g -> scope.launch { repo.toggleTeam(team, Teams.sportFor(g.league)) } }
            },
            onFavorite = { scope.launch { repo.toggleFavorite(ch) } },
            onHide = {
                val i = visible.indexOfFirst { it.id == ch.id }
                focusAfterHide = (visible.getOrNull(i + 1) ?: visible.getOrNull(i - 1))?.id
                scope.launch { repo.setHidden(ch, true) }
            },
            onDismiss = {
                menuFor = null
                val next = focusAfterHide
                focusAfterHide = null
                val resorted = teamToggled
                teamToggled = false
                scope.launch {
                    if (resorted) {
                        delay(400) // the list re-sorts once the change is saved
                        listState.scrollToItem(0)
                        focusTargetId = visibleNow.firstOrNull()?.id
                        withFrameNanos { }
                        withFrameNanos { }
                        if (runCatching { lastChannelFocus.requestFocus() }.isSuccess) return@launch
                    }
                    if (next != null) {
                        // Wait for the hidden row to leave the list, then focus its neighbour.
                        for (attempt in 0 until 30) {
                            withFrameNanos { }
                            if (visibleNow.none { it.id == ch.id }) break
                        }
                        focusTargetId = next
                        val index = visibleNow.indexOfFirst { it.id == next }
                        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                            listState.scrollToItem((index - 3).coerceAtLeast(0))
                        }
                        withFrameNanos { }
                        withFrameNanos { }
                        if (runCatching { lastChannelFocus.requestFocus() }.isSuccess) return@launch
                    }
                    withFrameNanos { }
                    runCatching { listFocus.requestFocus() }
                }
            },
        )
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
    label: String?,
    now: Long,
    modifier: Modifier,
    onClick: () -> Unit,
    onOptions: () -> Unit,
) {
    FocusTile(
        onClick = onClick,
        onLongClick = onOptions,
        focusedScale = 1.01f,
        modifier = modifier.fillMaxWidth().height(62.dp).onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Menu) {
                onOptions(); true
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
                if (program != null || label != null) {
                    Text(
                        label ?: program!!.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (program != null) {
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
