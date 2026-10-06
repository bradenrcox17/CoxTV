@file:OptIn(ExperimentalCoroutinesApi::class)

package com.coxtv.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.coxtv.AppContainer
import com.coxtv.data.Categories
import com.coxtv.data.db.Channel
import com.coxtv.data.db.ProgramEntity
import com.coxtv.ui.components.Clock
import com.coxtv.ui.components.collectAsStateCompat
import com.coxtv.ui.components.rememberNow
import com.coxtv.ui.components.rememberTimeFormatter
import com.coxtv.ui.components.touchClick
import com.coxtv.ui.theme.CoxColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

private const val SLOT_MS = 30 * 60_000L
private const val WINDOW_MS = 4 * SLOT_MS // 2 hours visible
private val CHANNEL_COL = 210.dp
private val ROW_HEIGHT = 54.dp

private fun floorSlot(t: Long) = t - t % SLOT_MS

private data class FocusedCell(val channel: Channel, val program: ProgramEntity?)

@Composable
fun GuideScreen(container: AppContainer, category: String, onPlay: (channelId: String) -> Unit) {
    val repo = container.repository
    val fmt = rememberTimeFormatter()
    val now by rememberNow(30_000)

    val channels by repo.channels.collectAsStateCompat(null)
    val list = remember(channels, category) {
        val all = channels.orEmpty()
        Categories.filter(all, category).ifEmpty { all }
    }

    val minStart = remember { floorSlot(System.currentTimeMillis()) - 2 * SLOT_MS }
    val maxStart = remember { floorSlot(System.currentTimeMillis()) + 36 * 3_600_000L - WINDOW_MS }
    var windowStart by rememberSaveable { mutableLongStateOf(floorSlot(System.currentTimeMillis())) }
    val windowEnd = windowStart + WINDOW_MS

    var focused by remember { mutableStateOf<FocusedCell?>(null) }
    val gridFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // Load programmes only for the rows around the screen (a page of 40), not the whole
    // category - with 20,000 channels that's the difference between instant and sluggish.
    // Entries are merged, never cleared, so a focused cell never disappears mid-update.
    var programs by remember { mutableStateOf<Map<String, List<ProgramEntity>>>(emptyMap()) }
    var firstPageLoaded by remember { mutableStateOf(false) }
    val guideVersion by container.settings.lastEpgRefresh.collectAsStateCompat(0L)
    LaunchedEffect(list, windowStart, guideVersion) {
        snapshotFlow { listState.firstVisibleItemIndex / 20 }
            .distinctUntilChanged()
            .collectLatest { page ->
                val from = ((page - 1) * 20).coerceAtLeast(0)
                val to = ((page + 2) * 20).coerceAtMost(list.size)
                if (from >= to) return@collectLatest
                val ids = list.subList(from, to).mapNotNull { it.epgId }
                val loaded = repo.programsFor(ids, windowStart - SLOT_MS, windowStart + WINDOW_MS + SLOT_MS)
                val merged = HashMap(programs)
                ids.forEach { id -> merged[id] = loaded[id].orEmpty() }
                programs = merged
                firstPageLoaded = true
            }
    }
    var initialFocusDone by remember { mutableStateOf(false) }

    // Focus only once the first page of programmes is in, otherwise the focused placeholder
    // cell is replaced by real ones and focus falls to some other row.
    LaunchedEffect(list.isNotEmpty(), firstPageLoaded) {
        if (initialFocusDone || list.isEmpty() || !firstPageLoaded) return@LaunchedEffect
        initialFocusDone = true
        withFrameNanos { }
        runCatching { gridFocus.requestFocus() }
    }

    fun shift(slots: Int): Boolean {
        val target = (windowStart + slots * SLOT_MS).coerceIn(minStart, maxStart)
        if (target == windowStart) return false
        windowStart = target
        return true
    }

    Column(Modifier.fillMaxSize().background(CoxColors.Bg).padding(horizontal = 24.dp, vertical = 16.dp)) {
        // ---- Header: details of the focused program ----
        Row(Modifier.fillMaxWidth().height(104.dp)) {
            Column(Modifier.weight(1f)) {
                val cell = focused
                Text(
                    cell?.program?.title ?: cell?.channel?.name ?: "TV Guide",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (cell != null) {
                    val p = cell.program
                    Text(
                        buildString {
                            append("${cell.channel.number}  ${cell.channel.name}")
                            if (p != null) append("   •   ${fmt.day(p.startMs)}  ${fmt.range(p.startMs, p.endMs)}")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = CoxColors.Accent,
                        maxLines = 1,
                    )
                    Text(
                        p?.description ?: if (p == null) "No program information" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = CoxColors.TextDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Clock()
                Text(Categories.label(category), style = MaterialTheme.typography.bodySmall, color = CoxColors.TextDim)
            }
        }

        val shiftRef by rememberUpdatedState<(Int) -> Boolean>(::shift)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val timelineWidth = maxWidth - CHANNEL_COL
            val dpPerMs = timelineWidth / WINDOW_MS.toFloat()
            val slotPx = with(LocalDensity.current) { (timelineWidth / 4).toPx() }
            // Touch: swipe left/right to move through time, a half-hour per slot width dragged.
            val swipeTime = Modifier.pointerInput(slotPx) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onHorizontalDrag = { change, amount -> dx += amount; change.consume() },
                    onDragEnd = {
                        // Finger left (dx < 0) moves later in time; a short flick still moves one slot.
                        var slots = -(dx / slotPx).roundToInt()
                        if (slots == 0 && abs(dx) > slotPx / 4) slots = -dx.sign.toInt()
                        if (slots != 0) shiftRef(slots)
                    },
                )
            }

            Column(Modifier.fillMaxSize()) {
                // ---- Time ruler ----
                Row(Modifier.fillMaxWidth().height(28.dp)) {
                    Box(Modifier.width(CHANNEL_COL).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                        Text(fmt.day(windowStart), style = MaterialTheme.typography.labelMedium, color = CoxColors.TextDim)
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        for (i in 0 until (WINDOW_MS / SLOT_MS).toInt()) {
                            Text(
                                fmt.time(windowStart + i * SLOT_MS),
                                style = MaterialTheme.typography.labelMedium,
                                color = CoxColors.TextDim,
                                modifier = Modifier.offset(x = timelineWidth * (i / 4f) + 6.dp).align(Alignment.CenterStart),
                            )
                        }
                    }
                }

                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().then(swipeTime).focusRequester(gridFocus)) {
                    items(list, key = { it.id }) { ch ->
                        GuideRow(
                            channel = ch,
                            programs = ch.epgId?.let { programs[it] },
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            dpPerMs = dpPerMs,
                            now = now,
                            onFocus = { focused = FocusedCell(ch, it) },
                            onClick = { onPlay(ch.id) },
                            onShift = ::shift,
                        )
                    }
                }
            }

            // ---- "Now" marker ----
            if (now in windowStart until windowEnd) {
                Box(
                    Modifier
                        .offset(x = CHANNEL_COL + dpPerMs * (now - windowStart).toFloat(), y = 22.dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(CoxColors.Accent.copy(alpha = 0.8f)),
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: Channel,
    programs: List<ProgramEntity>?,
    windowStart: Long,
    windowEnd: Long,
    dpPerMs: Dp,
    now: Long,
    onFocus: (ProgramEntity?) -> Unit,
    onClick: () -> Unit,
    onShift: (Int) -> Boolean,
) {
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(vertical = 2.dp)) {
        Row(
            Modifier.width(CHANNEL_COL).fillMaxHeight().background(CoxColors.Panel, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(channel.number.toString(), modifier = Modifier.width(40.dp), style = MaterialTheme.typography.labelLarge, color = CoxColors.TextDim)
            Text(
                channel.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (channel.favorite) Text("★", color = CoxColors.Fav, fontSize = 14.sp)
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val visible = programs?.filter { it.endMs > windowStart && it.startMs < windowEnd }
            if (visible.isNullOrEmpty()) {
                key("empty") {
                    ProgramCell(
                        title = "No information",
                        startMs = windowStart, endMs = windowEnd,
                        windowStart = windowStart, windowEnd = windowEnd, dpPerMs = dpPerMs,
                        airing = false, dim = true,
                        onFocus = { onFocus(null) }, onClick = onClick, onShift = onShift,
                    )
                }
            } else {
                for (p in visible) {
                    key(p.rowId) {
                        ProgramCell(
                            title = p.title,
                            startMs = p.startMs, endMs = p.endMs,
                            windowStart = windowStart, windowEnd = windowEnd, dpPerMs = dpPerMs,
                            airing = now in p.startMs until p.endMs, dim = false,
                            onFocus = { onFocus(p) }, onClick = onClick, onShift = onShift,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgramCell(
    title: String,
    startMs: Long,
    endMs: Long,
    windowStart: Long,
    windowEnd: Long,
    dpPerMs: Dp,
    airing: Boolean,
    dim: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onShift: (Int) -> Boolean,
) {
    val s = maxOf(startMs, windowStart)
    val e = minOf(endMs, windowEnd)
    val x = dpPerMs * (s - windowStart).toFloat()
    val w = (dpPerMs * (e - s).toFloat()).coerceAtLeast(2.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .offset(x = x)
            .width(w)
            .fillMaxHeight()
            .padding(start = 2.dp)
            .touchClick(onClick)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    // At the edge of the visible window, scroll time instead of moving focus.
                    Key.DirectionRight -> endMs >= windowEnd && onShift(1)
                    Key.DirectionLeft -> startMs <= windowStart && onShift(-1)
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (airing) CoxColors.PanelHi else CoxColors.Panel,
            contentColor = if (dim) CoxColors.TextDim else CoxColors.Text,
            focusedContainerColor = CoxColors.Accent,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(
            (if (startMs < windowStart) "‹ " else "") + title,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (airing) FontWeight.SemiBold else FontWeight.Normal,
            color = LocalContentColor.current,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
