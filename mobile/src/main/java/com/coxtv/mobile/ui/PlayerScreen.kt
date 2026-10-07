@file:androidx.annotation.OptIn(markerClass = [UnstableApi::class])

package com.coxtv.mobile.ui

import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.coxtv.AppContainer
import com.coxtv.data.Categories
import com.coxtv.data.CategoryExtras
import com.coxtv.data.db.Channel
import com.coxtv.data.db.ProgramEntity
import com.coxtv.mobile.MainActivity
import com.coxtv.mobile.ui.theme.CoxColors
import com.coxtv.player.buildLivePlayer
import com.coxtv.player.playChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun PlayerScreen(
    container: AppContainer,
    activity: MainActivity,
    channelId: String,
    category: String,
    onExit: () -> Unit,
) {
    val repo = container.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fmt = rememberTimeFormatter()
    val now by rememberNow(15_000)
    val inPip by activity.inPip

    val allChannels by repo.channels.collectAsStateWithLifecycle(null)
    val extras by repo.categoryExtras.collectAsStateWithLifecycle(CategoryExtras.EMPTY)
    // Recent reorders itself as you watch; swipe through it in the order it had when you came in.
    var recentAtEntry by remember { mutableStateOf<List<Channel>?>(null) }
    if (recentAtEntry == null && extras.recent.isNotEmpty()) recentAtEntry = extras.recent
    val zapList = remember(allChannels, category, extras, recentAtEntry) {
        val all = allChannels.orEmpty()
        val list = if (category == Categories.RECENT) recentAtEntry.orEmpty() else Categories.filter(all, category, extras)
        list.ifEmpty { all }
    }
    var currentId by remember { mutableStateOf(channelId) }
    val current = remember(allChannels, currentId) { allChannels?.firstOrNull { it.id == currentId } }

    var showOverlay by remember { mutableStateOf(true) }
    var overlayNonce by remember { mutableIntStateOf(0) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retries by remember { mutableIntStateOf(0) }
    var retryNonce by remember { mutableIntStateOf(0) }
    var firstTune by remember { mutableStateOf(true) }
    // Ways to play this channel, tried in turn until one starts: through the stream server
    // (when linked), then each copy of the channel (HD/FHD/backup feeds) directly.
    var sources by remember { mutableStateOf<List<Pair<Channel, String>>>(emptyList()) }
    var sourceIndex by remember { mutableIntStateOf(0) }
    var sourcePlayed by remember { mutableStateOf(false) }
    var playingSince by remember { mutableStateOf(0L) }

    // Fullscreen landscape while watching; restore when leaving the player.
    DisposableEffect(Unit) {
        val window = activity.window
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        insets.hide(WindowInsetsCompat.Type.systemBars())
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        activity.playerActive = true
        onDispose {
            activity.playerActive = false
            insets.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val player = remember { buildLivePlayer(context) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_READY) {
                    error = null
                    retries = 0
                    sourcePlayed = true
                    if (playingSince == 0L) playingSince = System.currentTimeMillis()
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                if (!sourcePlayed && sourceIndex + 1 < sources.size) {
                    sourceIndex++
                    return
                }
                error = e.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()
                    .replaceFirstChar { it.uppercase() }
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

    // Pause in the background (but keep playing in picture-in-picture); resume live on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> if (!player.isPlaying && player.currentMediaItem != null) {
                    player.seekToDefaultPosition()
                    player.play()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun flashOverlay() {
        showOverlay = true
        overlayNonce++
    }

    fun zap(step: Int) {
        if (zapList.isEmpty()) return
        val index = zapList.indexOfFirst { it.id == currentId }
        val next = zapList[if (index < 0) 0 else (index + step).mod(zapList.size)]
        if (next.id != currentId) {
            retries = 0
            error = null
        }
        currentId = next.id
        flashOverlay()
    }

    // The id may be a folded copy (e.g. last watched before copies were combined).
    LaunchedEffect(channelId) {
        val shown = repo.shownId(channelId)
        if (shown != currentId && currentId == channelId) currentId = shown
    }

    LaunchedEffect(current?.id) {
        val ch = current
        sources = if (ch == null) emptyList() else {
            listOfNotNull(container.link.streamUrl(ch)?.let { ch to it }) + repo.sourcesFor(ch).map { it to it.streamUrl }
        }
        sourceIndex = 0
    }

    // Counts as recently watched once it has actually played for a few seconds.
    LaunchedEffect(current?.id, playingSince) {
        val ch = current ?: return@LaunchedEffect
        if (playingSince == 0L) return@LaunchedEffect
        delay(5_000)
        repo.addRecent(ch)
    }

    // Tune (debounced so quick swipes skim channels without starting every stream).
    LaunchedEffect(current?.streamUrl, retryNonce, sourceIndex, sources) {
        val channel = current ?: return@LaunchedEffect
        if (sources.firstOrNull()?.first?.id != channel.id) return@LaunchedEffect // still looking them up
        val (ch, url) = sources.getOrNull(sourceIndex) ?: sources.first()
        if (!firstTune) delay(350)
        firstTune = false
        buffering = true
        sourcePlayed = false
        playingSince = 0L
        player.playChannel(ch, url)
        container.settings.setLastWatched(channel.id, category)
    }

    LaunchedEffect(showOverlay, overlayNonce) {
        if (showOverlay) {
            delay(4_000)
            showOverlay = false
        }
    }

    BackHandler(onBack = onExit)

    val nowNext by produceState(emptyList<ProgramEntity>(), current?.epgId, now / 60_000) {
        value = current?.epgId?.let { repo.upcoming(it, 2) }.orEmpty()
    }
    val zapRef by rememberUpdatedState<(Int) -> Unit> { zap(it) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures { if (showOverlay) showOverlay = false else flashOverlay() }
            }
            .pointerInput(Unit) {
                var dy = 0f
                detectVerticalDragGestures(
                    onDragStart = { dy = 0f },
                    onVerticalDrag = { change, amount -> dy += amount; change.consume() },
                    onDragEnd = { if (abs(dy) > 60.dp.toPx()) zapRef(if (dy < 0) +1 else -1) },
                )
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
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (!inPip) {
            if (buffering && error == null) {
                Text(
                    "Loading…",
                    modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
                    color = Color.White,
                )
            }
            error?.let {
                Column(
                    Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Can't play ${current?.name ?: "channel"}", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text(it, color = CoxColors.TextDim)
                    Text(if (retries in 1..3) "Retrying…" else "Swipe up or down to change channel", color = CoxColors.TextDim)
                }
            }

            AnimatedVisibility(visible = showOverlay && current != null, enter = fadeIn(), exit = fadeOut()) {
                current?.let { ch ->
                    Overlay(
                        channel = ch,
                        programs = nowNext,
                        now = now,
                        fmt = fmt,
                        pipSupported = activity.pipSupported,
                        onBack = onExit,
                        onPip = activity::enterPip,
                        onFavorite = {
                            flashOverlay()
                            scope.launch { repo.toggleFavorite(ch) }
                        },
                        onUp = { zap(+1) },
                        onDown = { zap(-1) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Overlay(
    channel: Channel,
    programs: List<ProgramEntity>,
    now: Long,
    fmt: TimeFormatter,
    pipSupported: Boolean,
    onBack: () -> Unit,
    onPip: () -> Unit,
    onFavorite: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    val current = programs.firstOrNull { now in it.startMs until it.endMs }
    val next = programs.firstOrNull { it.startMs >= (current?.endMs ?: now) }
    Box(Modifier.fillMaxSize()) {
        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                .safeDrawingPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Text(
                "${channel.number}  ${channel.name}",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onFavorite) {
                Icon(
                    if (channel.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (channel.favorite) CoxColors.Fav else Color.White,
                )
            }
            if (pipSupported) {
                IconButton(onClick = onPip) { Icon(PipIcon, contentDescription = "Picture in picture", tint = Color.White) }
            }
        }

        // Channel up/down on the right edge
        Column(
            Modifier.align(Alignment.CenterEnd).safeDrawingPadding().padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RoundButton(onUp) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Channel up", tint = Color.White) }
            RoundButton(onDown) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Channel down", tint = Color.White) }
        }

        // Now / next
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!channel.logo.isNullOrBlank()) {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 96.dp, height = 56.dp).clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                if (current != null) {
                    Text(current.title, style = MaterialTheme.typography.titleLarge, color = CoxColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt.range(current.startMs, current.endMs), color = CoxColors.TextDim, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(10.dp))
                        LinearProgressIndicator(
                            progress = { ((now - current.startMs).toFloat() / (current.endMs - current.startMs).coerceAtLeast(1)).coerceIn(0f, 1f) },
                            modifier = Modifier.width(160.dp).height(3.dp),
                            drawStopIndicator = {},
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(minutesLeft(current.endMs, now), color = CoxColors.TextDim, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("No program information", color = CoxColors.TextDim)
                }
                next?.let {
                    Text("Next  ${fmt.time(it.startMs)}  ${it.title}", color = CoxColors.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(2.dp))
                Text("Swipe up/down to change channel  •  Tap to hide", color = CoxColors.TextDim.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick) { content() }
    }
}
