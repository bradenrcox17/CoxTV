package com.coxtv.mobile.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coxtv.AppContainer
import com.coxtv.data.Categories
import com.coxtv.data.db.Channel
import com.coxtv.data.db.ProgramEntity
import com.coxtv.mobile.ui.theme.CoxColors
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

private const val HOUR = 3_600_000L
private const val SLOT = HOUR / 2
private val HOUR_WIDTH = 220.dp
private val ROW_HEIGHT = 60.dp

private fun floorSlot(t: Long) = t - t % SLOT

@Composable
fun GuideScreen(
    container: AppContainer,
    category: String,
    onCategory: (String) -> Unit,
    onPlay: (channelId: String) -> Unit,
) {
    val repo = container.repository
    val fmt = rememberTimeFormatter()
    val now by rememberNow(30_000)
    val channels by repo.channels.collectAsStateWithLifecycle(null)
    val cats by repo.categories.collectAsStateWithLifecycle(emptyList())
    val guideVersion by container.settings.lastEpgRefresh.collectAsStateWithLifecycle(0L)
    val all = channels.orEmpty()
    val list = remember(all, category) { Categories.filter(all, category) }
    val counts = remember(all) {
        all.groupingBy { it.groupName }.eachCount() +
            mapOf(Categories.ALL to all.size, Categories.FAVORITES to all.count { it.favorite })
    }

    // The guide covers 1 hour back to 36 hours ahead (what the importer keeps).
    val windowStart = remember { floorSlot(System.currentTimeMillis()) - HOUR }
    val windowEnd = remember { windowStart + 37 * HOUR }
    val dpPerMs = HOUR_WIDTH / HOUR.toFloat()
    val totalWidth = dpPerMs * (windowEnd - windowStart).toFloat()

    val hScroll = rememberScrollState()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // Open on "now" (half an hour of context to the left).
    LaunchedEffect(Unit) {
        val x = with(density) { (dpPerMs * (System.currentTimeMillis() - SLOT - windowStart).toFloat()).roundToPx() }
        hScroll.scrollTo(x)
    }
    LaunchedEffect(category) { listState.scrollToItem(0) }

    // Programmes for the rows around the screen only (pages of 20), merged and never cleared.
    var programs by remember { mutableStateOf<Map<String, List<ProgramEntity>>>(emptyMap()) }
    LaunchedEffect(list, guideVersion) {
        snapshotFlow { listState.firstVisibleItemIndex / 20 }
            .distinctUntilChanged()
            .collectLatest { page ->
                val from = ((page - 1) * 20).coerceAtLeast(0)
                val to = ((page + 2) * 20).coerceAtMost(list.size)
                if (from >= to) return@collectLatest
                val ids = list.subList(from, to).mapNotNull { it.epgId }
                val loaded = repo.programsFor(ids, windowStart, windowEnd)
                programs = HashMap(programs).apply { ids.forEach { put(it, loaded[it].orEmpty()) } }
            }
    }

    var details by remember { mutableStateOf<Pair<Channel, ProgramEntity>?>(null) }

    Column(Modifier.fillMaxSize()) {
        Text(
            "TV Guide",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        CategoryChips(cats, category, counts, onCategory)
        Spacer(Modifier.height(6.dp))

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val channelCol: Dp = if (maxWidth < 500.dp) 112.dp else 180.dp
            Column(Modifier.fillMaxSize()) {
                // Time ruler, scrolled together with the rows.
                Row(Modifier.fillMaxWidth().height(28.dp)) {
                    Box(Modifier.width(channelCol).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                        Text(fmt.day(now), style = MaterialTheme.typography.labelMedium, color = CoxColors.TextDim, modifier = Modifier.padding(start = 12.dp))
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
                        Box(Modifier.width(totalWidth).fillMaxHeight()) {
                            var t = windowStart
                            while (t < windowEnd) {
                                Text(
                                    fmt.time(t),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CoxColors.TextDim,
                                    modifier = Modifier.offset(x = dpPerMs * (t - windowStart).toFloat() + 6.dp).align(Alignment.CenterStart),
                                )
                                t += SLOT
                            }
                        }
                    }
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { ch ->
                        GuideRow(
                            channel = ch,
                            programs = ch.epgId?.let { programs[it] },
                            channelCol = channelCol,
                            hScroll = hScroll,
                            totalWidth = totalWidth,
                            windowStart = windowStart,
                            dpPerMs = dpPerMs,
                            now = now,
                            onPlay = { onPlay(ch.id) },
                            onDetails = { p -> details = ch to p },
                        )
                    }
                }
            }
        }
    }

    details?.let { (ch, p) ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(p.title) },
            text = {
                Column {
                    Text("${ch.number}  ${ch.name}", color = CoxColors.Accent)
                    Text("${fmt.day(p.startMs)}  ${fmt.range(p.startMs, p.endMs)}", color = CoxColors.TextDim)
                    p.description?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { details = null; onPlay(ch.id) }) { Text("Watch channel") }
            },
            dismissButton = { TextButton(onClick = { details = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun GuideRow(
    channel: Channel,
    programs: List<ProgramEntity>?,
    channelCol: Dp,
    hScroll: ScrollState,
    totalWidth: Dp,
    windowStart: Long,
    dpPerMs: Dp,
    now: Long,
    onPlay: () -> Unit,
    onDetails: (ProgramEntity) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(vertical = 2.dp)) {
        Column(
            Modifier
                .width(channelCol)
                .fillMaxHeight()
                .padding(start = 4.dp)
                .background(CoxColors.Panel, RoundedCornerShape(6.dp))
                .clickable(onClick = onPlay)
                .padding(horizontal = 8.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Text(channel.number.toString(), style = MaterialTheme.typography.labelSmall, color = CoxColors.TextDim)
            Text(channel.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
            Box(Modifier.width(totalWidth).fillMaxHeight()) {
                if (programs.isNullOrEmpty()) {
                    Box(
                        Modifier.fillMaxSize().padding(start = 2.dp).background(CoxColors.Panel, RoundedCornerShape(6.dp)).clickable(onClick = onPlay),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        // Place the label near "now" so it's on screen (the row spans 37 hours).
                        Text(
                            "No information",
                            color = CoxColors.TextDim,
                            modifier = Modifier.offset(x = dpPerMs * (now - SLOT / 2 - windowStart).toFloat()),
                        )
                    }
                } else {
                    for (p in programs) {
                        val start = maxOf(p.startMs, windowStart)
                        val airing = now in p.startMs until p.endMs
                        Box(
                            Modifier
                                .offset(x = dpPerMs * (start - windowStart).toFloat())
                                .width((dpPerMs * (p.endMs - start).toFloat()).coerceAtLeast(2.dp))
                                .fillMaxHeight()
                                .padding(start = 2.dp)
                                .background(if (airing) CoxColors.PanelHi else CoxColors.Panel, RoundedCornerShape(6.dp))
                                .clickable { onDetails(p) },
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                p.title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (airing) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp),
                            )
                        }
                    }
                }
                // "Now" line
                Box(
                    Modifier
                        .offset(x = dpPerMs * (now - windowStart).toFloat())
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(CoxColors.Accent),
                )
            }
        }
    }
}
