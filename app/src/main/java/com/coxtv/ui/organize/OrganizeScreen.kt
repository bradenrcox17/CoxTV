package com.coxtv.ui.organize

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.coxtv.AppContainer
import com.coxtv.data.Categories
import com.coxtv.data.db.Channel
import com.coxtv.ui.components.CoxButton
import com.coxtv.ui.components.FocusTile
import com.coxtv.ui.components.collectAsStateCompat
import com.coxtv.ui.theme.CoxColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Choose which categories appear in the sidebar, their order, and the order of favorites. */
@Composable
fun OrganizeScreen(container: AppContainer) {
    val repo = container.repository
    val scope = rememberCoroutineScope()
    val shown by repo.categories.collectAsStateCompat(null)
    val groups by repo.groups.collectAsStateCompat(emptyList())
    val channels by repo.channels.collectAsStateCompat(null)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val firstFocus = remember { FocusRequester() }

    val all = channels.orEmpty()
    val counts = remember(all) {
        all.groupingBy { it.groupName }.eachCount() +
            mapOf(Categories.ALL to all.size, Categories.FAVORITES to all.count { it.favorite })
    }
    val favorites = remember(all) { Categories.filter(all, Categories.FAVORITES) }

    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().background(CoxColors.Bg).padding(horizontal = 32.dp, vertical = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Categories & favorites", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            CoxButton("Categories", onClick = { tab = 0 }, primary = tab == 0, modifier = Modifier.focusRequester(firstFocus))
            Spacer(Modifier.width(12.dp))
            CoxButton("Favorites order", onClick = { tab = 1 }, primary = tab == 1)
            Spacer(Modifier.width(12.dp))
            CoxButton("Hidden channels", onClick = { tab = 2 }, primary = tab == 2)
        }
        Spacer(Modifier.height(16.dp))
        when (tab) {
            0 -> Row(Modifier.fillMaxSize()) {
                val enabled = shown.orEmpty()
                val enabledSet = remember(enabled) { enabled.toHashSet() }
                val shownState = rememberLazyListState()
                Pane("Shown in the sidebar", "Use ▲ ▼ to change the order.", Modifier.weight(1f)) {
                    LazyColumn(state = shownState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(enabled, key = { _, key -> key }) { i, key ->
                            OrderRow(
                                label = Categories.label(key),
                                count = counts[key],
                                canUp = i > 0,
                                canDown = i < enabled.lastIndex,
                                onMove = { delta ->
                                    scope.launch { repo.moveCategory(key, delta) }
                                    scope.keepVisible(shownState, i + delta)
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(28.dp))
                Pane("All categories", "Press OK to show or hide a category.", Modifier.weight(1f)) {
                    // Fixed order (unlike the list on the left), so rows never move while you check them.
                    val keys = remember(groups) { listOf(Categories.FAVORITES, Categories.ALL) + groups }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(keys, key = { it }) { key ->
                            val on = key in enabledSet
                            FocusTile(
                                onClick = { scope.launch { repo.setCategoryEnabled(key, !on) } },
                                focusedScale = 1.01f,
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                            ) {
                                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (on) "☑" else "☐", fontSize = 20.sp, modifier = Modifier.width(32.dp))
                                    Label(Categories.label(key), counts[key], Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            2 -> HiddenPane(container)

            else -> Pane(
                "Favorites order",
                "Use ▲ ▼ to move a channel. Add or remove favorites from a channel list with ☰ Menu (or hold OK).",
                Modifier.fillMaxSize(),
            ) {
                if (channels != null && favorites.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No favorites yet.", style = MaterialTheme.typography.bodyLarge, color = CoxColors.TextDim, textAlign = TextAlign.Center)
                    }
                } else {
                    val favState = rememberLazyListState()
                    LazyColumn(state = favState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(favorites, key = { _, ch -> ch.id }) { i, ch ->
                            OrderRow(
                                label = "${ch.number}   ${ch.name}",
                                count = null,
                                canUp = i > 0,
                                canDown = i < favorites.lastIndex,
                                onMove = { delta ->
                                    scope.launch { repo.moveFavorite(ch.id, delta) }
                                    scope.keepVisible(favState, i + delta)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Pane(title: String, hint: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxHeight()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = CoxColors.TextDim)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun Label(text: String, count: Int?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (count != null) {
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.6f))
        }
    }
}

/**
 * A row with ▲ ▼ buttons. The buttons stay enabled at the ends (a disabled button would drop
 * focus right after moving a row to the top or bottom); moving past an end does nothing.
 */
@Composable
private fun OrderRow(label: String, count: Int?, canUp: Boolean, canDown: Boolean, onMove: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).fillMaxHeight().background(CoxColors.Panel, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Label(label, count)
        }
        Spacer(Modifier.width(8.dp))
        CoxButton(if (canUp) "▲" else "△", onClick = { onMove(-1) })
        Spacer(Modifier.width(6.dp))
        CoxButton(if (canDown) "▼" else "▽", onClick = { onMove(1) })
    }
}

/** Keeps a moved row on screen (the focused row moves with its buttons). */
private fun CoroutineScope.keepVisible(state: LazyListState, index: Int) {
    if (index < 0) return
    val visible = state.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return
    if (index <= visible.first().index || index >= visible.last().index) {
        launch { state.animateScrollToItem((index - 2).coerceAtLeast(0)) }
    }
}

/** Channels hidden from lists and search. Rows stay put after OK so focus never jumps. */
@Composable
private fun HiddenPane(container: AppContainer) {
    val repo = container.repository
    val scope = rememberCoroutineScope()
    val hidden by repo.hiddenChannels.collectAsStateCompat(null)
    // Shown again during this visit: kept in the list (ticked) instead of disappearing.
    var restored by remember { mutableStateOf<List<Channel>>(emptyList()) }
    val rows = remember(hidden, restored) { (hidden.orEmpty() + restored).distinctBy { it.id }.sortedBy { it.name.lowercase() } }
    Pane("Hidden channels", "Press OK to show a channel again. Hide channels with ☰ Menu (or hold OK) in a channel list.", Modifier.fillMaxSize()) {
        if (hidden != null && rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No hidden channels.", style = MaterialTheme.typography.bodyLarge, color = CoxColors.TextDim)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows, key = { it.id }) { ch ->
                    val back = restored.any { it.id == ch.id }
                    FocusTile(
                        onClick = {
                            if (!back) {
                                restored = restored + ch
                                scope.launch { repo.setHidden(ch, false) }
                            }
                        },
                        focusedScale = 1.01f,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Label(ch.name, null, Modifier.weight(1f))
                            Text(if (back) "Shown again" else "Hidden", style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
}
