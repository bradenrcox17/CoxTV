@file:androidx.annotation.OptIn(markerClass = [UnstableApi::class])

package com.coxtv.ui.player

import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.coxtv.AppContainer
import com.coxtv.data.Categories
import com.coxtv.data.CategoryExtras
import com.coxtv.data.DeviceLink
import com.coxtv.data.db.Channel
import com.coxtv.data.db.ProgramEntity
import com.coxtv.player.buildLivePlayer
import com.coxtv.player.playChannel
import com.coxtv.ui.components.ChannelLogo
import com.coxtv.ui.components.Clock
import com.coxtv.ui.components.CoxButton
import com.coxtv.ui.components.FocusTile
import com.coxtv.ui.components.LocalIsTouch
import com.coxtv.ui.components.ProgressLine
import com.coxtv.ui.components.collectAsStateCompat
import com.coxtv.ui.components.rememberNow
import com.coxtv.ui.components.rememberTimeFormatter
import com.coxtv.ui.theme.CoxColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun PlayerScreen(
    container: AppContainer,
    channelId: String,
    category: String,
    onOpenGuide: (category: String) -> Unit,
) {
    val repo = container.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fmt = rememberTimeFormatter()
    val now by rememberNow(15_000)

    val allChannels by repo.channels.collectAsStateCompat(null)
    val nowPlaying by repo.nowPlaying.collectAsStateCompat(emptyMap())
    val extras by repo.categoryExtras.collectAsStateCompat(CategoryExtras.EMPTY)
    // Recent reorders itself as you watch; zap through it in the order it had when you came in.
    var recentAtEntry by remember { mutableStateOf<List<Channel>?>(null) }
    if (recentAtEntry == null && extras.recent.isNotEmpty()) recentAtEntry = extras.recent
    val zapList = remember(allChannels, category, extras, recentAtEntry) {
        val all = allChannels.orEmpty()
        val list = if (category == Categories.RECENT) recentAtEntry.orEmpty() else Categories.filter(all, category, extras)
        list.ifEmpty { all }
    }

    var currentId by remember { mutableStateOf(channelId) }
    val current = remember(allChannels, currentId) { allChannels?.firstOrNull { it.id == currentId } }

    var showInfo by remember { mutableStateOf(true) }
    var infoNonce by remember { mutableIntStateOf(0) }
    var showMiniGuide by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var awaitingFirstFrame by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryNonce by remember { mutableIntStateOf(0) }
    var retries by remember { mutableIntStateOf(0) }
    var retrying by remember { mutableStateOf(false) }
    var handledRetry by remember { mutableIntStateOf(0) }
    var digits by remember { mutableStateOf("") }
    var firstTune by remember { mutableStateOf(true) }
    var previousId by remember { mutableStateOf<String?>(null) }
    // Ways to play this channel, tried in turn until one starts: through the stream server
    // (when linked), then each copy of the channel (HD/FHD/backup feeds) directly.
    var sources by remember { mutableStateOf<List<Pair<Channel, String>>>(emptyList()) }
    var sourceIndex by remember { mutableIntStateOf(0) }
    var sourcePlayed by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }

    val player = remember { buildLivePlayer(context) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                awaitingFirstFrame = false
            }

            override fun onPlaybackStateChanged(state: Int) {
                android.util.Log.d("CoxTV", "state=$state item=${player.currentMediaItem?.mediaId}")
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_READY) {
                    error = null
                    retries = 0
                    sourcePlayed = true
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                android.util.Log.w("CoxTV", "player error ${e.errorCodeName}", e)
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                if (!sourcePlayed && sourceIndex + 1 < sources.size) {
                    android.util.Log.i("CoxTV", "source ${sourceIndex + 1} of ${sources.size} failed; trying the next copy")
                    sourceIndex++
                    return
                }
                error = e.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()
                    .replaceFirstChar { it.uppercase() }
                retrying = retries < 3
                if (retries < 3) {
                    retries++
                    scope.launch {
                        delay(2_000L * retries)
                        retryNonce++
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            container.appScope.launch { container.link.leave() } // frees the channel on the server sooner
        }
    }

    fun flashInfo() {
        showInfo = true
        infoNonce++
    }

    fun tune(channel: Channel) {
        if (channel.id != currentId) {
            retries = 0
            error = null
            previousId = currentId
        }
        currentId = channel.id
        flashInfo()
    }

    fun zap(step: Int) {
        if (zapList.isEmpty()) return
        val index = zapList.indexOfFirst { it.id == currentId }
        val next = if (index < 0) 0 else (index + step).mod(zapList.size)
        tune(zapList[next])
    }

    // The id may be a folded copy (e.g. last watched before copies were combined). A new id
    // means a channel was sent from the remote.
    var requestedId by remember { mutableStateOf(channelId) }
    LaunchedEffect(channelId) {
        val shown = repo.shownId(channelId)
        if (channelId != requestedId) {
            requestedId = channelId
            allChannels?.firstOrNull { it.id == shown }?.let(::tune) ?: run { currentId = shown }
        } else if (shown != currentId && currentId == channelId) {
            currentId = shown
        }
    }

    LaunchedEffect(current?.id) {
        val ch = current
        sources = if (ch == null) emptyList() else {
            listOfNotNull(container.link.streamUrl(ch)?.let { ch to it }) + repo.sourcesFor(ch).map { it to it.streamUrl }
        }
        sourceIndex = 0
    }

    // Counts as recently watched once it has actually played for a few seconds.
    LaunchedEffect(current?.id, awaitingFirstFrame) {
        val ch = current ?: return@LaunchedEffect
        if (awaitingFirstFrame) return@LaunchedEffect
        delay(5_000)
        repo.addRecent(ch)
    }

    // Tune (debounced so holding up/down skims channels without starting every stream).
    LaunchedEffect(current?.streamUrl, retryNonce, sourceIndex, sources) {
        val channel = current ?: return@LaunchedEffect
        if (sources.firstOrNull()?.first?.id != channel.id) return@LaunchedEffect // still looking them up
        val (ch, url) = sources.getOrNull(sourceIndex) ?: sources.first()
        val isRetry = retryNonce != handledRetry
        handledRetry = retryNonce
        if (!firstTune && !isRetry) delay(400)
        retrying = false
        firstTune = false
        buffering = true
        android.util.Log.d("CoxTV", "tune ${ch.number} ${ch.name} source ${sourceIndex + 1}/${sources.size} retry=$isRetry")
        sourcePlayed = false
        awaitingFirstFrame = true
        container.link.playing = channel.name to (DeviceLink.serverId(ch.streamUrl) ?: "")
        player.playChannel(ch, url)
        container.settings.setLastWatched(channel.id, category)
    }

    LaunchedEffect(showInfo, infoNonce) {
        if (showInfo) {
            delay(5_000)
            showInfo = false
        }
    }

    // A tapped info-bar button takes focus; give it back to the player when the bar hides.
    LaunchedEffect(showInfo) {
        if (!showInfo && !showMiniGuide) runCatching { rootFocus.requestFocus() }
    }

    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_500)
        val n = digits.toIntOrNull()
        digits = ""
        allChannels?.firstOrNull { it.number == n }?.let(::tune)
    }

    LaunchedEffect(showMiniGuide) {
        if (!showMiniGuide) {
            withFrameNanos { }
            runCatching { rootFocus.requestFocus() }
        }
    }

    BackHandler(enabled = showMiniGuide || showInfo || digits.isNotEmpty()) {
        showMiniGuide = false
        showInfo = false
        digits = ""
    }

    // Gesture handlers outlive recompositions, so route them through the latest lambdas.
    val zapRef by rememberUpdatedState<(Int) -> Unit> { zap(it) }
    val flashRef by rememberUpdatedState<() -> Unit> { flashInfo() }
    val prevRef by rememberUpdatedState<() -> Unit> { previousId?.let { id -> allChannels?.firstOrNull { it.id == id } }?.let(::tune) }

    // Channel up/down and previous from the phone app or tv.thecoxhome.com (Remote).
    LaunchedEffect(Unit) {
        container.link.commands.collect { c ->
            when (c.cmd) {
                "up" -> zapRef(+1)
                "down" -> zapRef(-1)
                "prev" -> prevRef()
            }
        }
    }
    val isTouch = LocalIsTouch.current

    val nowNext by produceState(emptyList<ProgramEntity>(), current?.epgId, now / 60_000) {
        value = current?.epgId?.let { repo.upcoming(it, 2) }.orEmpty()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            // Touch: tap shows/hides the info bar (or closes the channel list).
            .pointerInput(Unit) {
                detectTapGestures {
                    when {
                        showMiniGuide -> showMiniGuide = false
                        showInfo -> showInfo = false
                        else -> flashRef()
                    }
                }
            }
            // Touch: swipe up/down changes channel, swipe right opens the channel list.
            .pointerInput(Unit) {
                var total = Offset.Zero
                detectDragGestures(
                    onDragStart = { total = Offset.Zero },
                    onDrag = { change, amount -> total += amount; change.consume() },
                    onDragEnd = {
                        val min = 60.dp.toPx()
                        if (!showMiniGuide) when {
                            abs(total.y) > abs(total.x) && abs(total.y) > min -> zapRef(if (total.y < 0) +1 else -1)
                            total.x > min -> {
                                showInfo = false
                                showMiniGuide = true
                            }
                        }
                    },
                )
            }
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || showMiniGuide) return@onKeyEvent false
                when (val code = ev.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { zap(+1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { zap(-1); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (showInfo) showInfo = false else flashInfo()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> { flashInfo(); true }
                    // Back to the channel watched before this one.
                    KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_LAST_CHANNEL, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        previousId?.let { id -> allChannels?.firstOrNull { it.id == id } }?.let(::tune)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> {
                        showInfo = false
                        showMiniGuide = true
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        if (player.isPlaying) player.pause() else { player.seekToDefaultPosition(); player.play() }
                        true
                    }
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        if (digits.length < 5) digits += (code - KeyEvent.KEYCODE_0).toString()
                        true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Hide the previous channel's frozen frame until the new stream renders.
        if (awaitingFirstFrame) Box(Modifier.fillMaxSize().background(Color.Black))

        if ((buffering || awaitingFirstFrame) && error == null) {
            Text(
                "Loading…",
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        error?.let {
            Column(
                Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Can't play ${current?.name ?: "channel"}", style = MaterialTheme.typography.titleLarge)
                Text(it, style = MaterialTheme.typography.bodyMedium, color = CoxColors.TextDim)
                Text(
                    when {
                        retrying -> "Retrying…"
                        isTouch -> "Swipe up or down to change channel"
                        else -> "Press ▲ ▼ to change channel"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = CoxColors.TextDim,
                )
            }
        }

        if (digits.isNotEmpty()) {
            Text(
                digits,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).padding(horizontal = 20.dp, vertical = 8.dp),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        AnimatedVisibility(
            visible = showInfo && current != null && !showMiniGuide,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            current?.let { ch ->
                InfoOverlay(
                    channel = ch,
                    programs = nowNext,
                    now = now,
                    fmt = fmt,
                    touchActions = if (!isTouch) null else TouchActions(
                        onChannelUp = { zap(+1) },
                        onChannelDown = { zap(-1) },
                        onChannelList = {
                            showInfo = false
                            showMiniGuide = true
                        },
                        onFullGuide = { onOpenGuide(category) },
                        onToggleFavorite = {
                            flashInfo()
                            scope.launch { repo.toggleFavorite(ch) }
                        },
                    ),
                )
            }
        }

        AnimatedVisibility(
            visible = showMiniGuide,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
        ) {
            MiniGuide(
                channels = zapList,
                currentId = currentId,
                nowPlaying = nowPlaying,
                title = Categories.label(category),
                now = now,
                onSelect = {
                    showMiniGuide = false
                    tune(it)
                },
                onToggleFavorite = { ch -> scope.launch { repo.toggleFavorite(ch) } },
                onFullGuide = {
                    showMiniGuide = false
                    onOpenGuide(category)
                },
            )
        }
    }
}

/** On-screen controls shown in the info bar on touchscreens (phones), which have no remote. */
private class TouchActions(
    val onChannelUp: () -> Unit,
    val onChannelDown: () -> Unit,
    val onChannelList: () -> Unit,
    val onFullGuide: () -> Unit,
    val onToggleFavorite: () -> Unit,
)

@Composable
private fun InfoOverlay(
    channel: Channel,
    programs: List<ProgramEntity>,
    now: Long,
    fmt: com.coxtv.ui.components.TimeFormatter,
    touchActions: TouchActions?,
) {
    val current = programs.firstOrNull { now in it.startMs until it.endMs }
    val next = programs.firstOrNull { it.startMs >= (current?.endMs ?: now) }
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))))
            .padding(start = 48.dp, end = 48.dp, top = 60.dp, bottom = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ChannelLogo(channel, Modifier.size(width = 130.dp, height = 78.dp))
                Spacer(Modifier.height(6.dp))
                Text(channel.number.toString(), fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(channel.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (channel.favorite) Text("  ★", color = CoxColors.Fav, fontSize = 22.sp)
                    Spacer(Modifier.weight(1f))
                    Clock()
                }
                Spacer(Modifier.height(6.dp))
                if (current != null) {
                    Text(current.title, style = MaterialTheme.typography.titleLarge, color = CoxColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt.range(current.startMs, current.endMs), style = MaterialTheme.typography.bodyMedium, color = CoxColors.TextDim)
                        Spacer(Modifier.width(12.dp))
                        ProgressLine(
                            (now - current.startMs).toFloat() / (current.endMs - current.startMs).coerceAtLeast(1),
                            Modifier.width(260.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("${((current.endMs - now) / 60_000).coerceAtLeast(0)} min left", style = MaterialTheme.typography.bodySmall, color = CoxColors.TextDim)
                    }
                    current.description?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = CoxColors.Text.copy(alpha = 0.85f))
                    }
                } else {
                    Text("No program information", style = MaterialTheme.typography.titleMedium, color = CoxColors.TextDim)
                }
                next?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("Next  ${fmt.time(it.startMs)}  ${it.title}", style = MaterialTheme.typography.bodyMedium, color = CoxColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                if (touchActions == null) {
                    Text("▲▼ Channel    ◀ Mini guide    ⏪ Previous channel    OK Hide", style = MaterialTheme.typography.labelSmall, color = CoxColors.TextDim.copy(alpha = 0.7f))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoxButton("▲  Up", onClick = touchActions.onChannelUp)
                        CoxButton("▼  Down", onClick = touchActions.onChannelDown)
                        CoxButton("Channel list", onClick = touchActions.onChannelList)
                        CoxButton("Guide", onClick = touchActions.onFullGuide)
                        CoxButton(if (channel.favorite) "★  Favorite" else "☆  Favorite", onClick = touchActions.onToggleFavorite)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Swipe ↑↓ to change channel   •   Swipe → for the channel list   •   Tap to hide", style = MaterialTheme.typography.labelSmall, color = CoxColors.TextDim.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Composable
private fun MiniGuide(
    channels: List<Channel>,
    currentId: String,
    nowPlaying: Map<String, ProgramEntity>,
    title: String,
    now: Long,
    onSelect: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    onFullGuide: () -> Unit,
) {
    val listState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        val index = channels.indexOfFirst { it.id == currentId }
        if (index >= 0) listState.scrollToItem((index - 3).coerceAtLeast(0))
        withFrameNanos { }
        withFrameNanos { }
        if (index < 0 || runCatching { currentFocus.requestFocus() }.isFailure) runCatching { listFocus.requestFocus() }
    }

    Column(
        Modifier
            .width(420.dp)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.95f), Color.Black.copy(alpha = 0.85f))))
            .pointerInput(Unit) { detectTapGestures { } } // taps on the panel itself don't close it
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            CoxButton("Full guide", onClick = onFullGuide)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().focusRequester(listFocus),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(channels, key = { it.id }) { ch ->
                val program = ch.epgId?.let { nowPlaying[it] }
                FocusTile(
                    onClick = { onSelect(ch) },
                    onLongClick = { onToggleFavorite(ch) },
                    selected = ch.id == currentId,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .then(if (ch.id == currentId) Modifier.focusRequester(currentFocus) else Modifier)
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU) {
                                onToggleFavorite(ch); true
                            } else false
                        },
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(ch.number.toString(), modifier = Modifier.width(44.dp), style = MaterialTheme.typography.titleSmall, color = LocalContentColor.current.copy(alpha = 0.7f))
                        Column(Modifier.weight(1f)) {
                            Text(ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (program != null) {
                                Text(program.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = LocalContentColor.current.copy(alpha = 0.7f))
                                ProgressLine(
                                    (now - program.startMs).toFloat() / (program.endMs - program.startMs).coerceAtLeast(1),
                                    Modifier.fillMaxWidth(0.8f).padding(top = 2.dp),
                                )
                            }
                        }
                        if (ch.favorite) Text("★", color = CoxColors.Fav, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
