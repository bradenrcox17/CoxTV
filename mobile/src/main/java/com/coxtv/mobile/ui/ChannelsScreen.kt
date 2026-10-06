package com.coxtv.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coxtv.AppContainer
import com.coxtv.data.Categories
import com.coxtv.data.db.Channel
import com.coxtv.data.db.ProgramEntity
import com.coxtv.mobile.ui.theme.CoxColors
import kotlinx.coroutines.launch

@Composable
fun ChannelsScreen(
    container: AppContainer,
    category: String?,
    onCategory: (String) -> Unit,
    onPlay: (channelId: String) -> Unit,
) {
    val repo = container.repository
    val scope = rememberCoroutineScope()
    val now by rememberNow()
    val channels by repo.channels.collectAsStateWithLifecycle(null)
    val groups by repo.groups.collectAsStateWithLifecycle(emptyList())
    val nowPlaying by repo.nowPlaying.collectAsStateWithLifecycle(emptyMap())
    val all = channels.orEmpty()

    // First visit: last watched category, else Favorites if there are any, else All.
    LaunchedEffect(channels != null) {
        if (category != null || channels == null) return@LaunchedEffect
        val last = container.settings.lastWatched()?.category
        onCategory(
            when {
                last != null && (last == Categories.ALL || last in groups ||
                    (last == Categories.FAVORITES && all.any { it.favorite })) -> last
                all.any { it.favorite } -> Categories.FAVORITES
                else -> Categories.ALL
            },
        )
    }

    val cat = category ?: Categories.ALL
    val visible = remember(all, cat) { Categories.filter(all, cat) }
    val counts = remember(all) {
        all.groupingBy { it.groupName }.eachCount() +
            mapOf(Categories.ALL to all.size, Categories.FAVORITES to all.count { it.favorite })
    }
    val gridState = rememberLazyGridState()
    LaunchedEffect(cat) { gridState.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(Categories.label(cat), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(10.dp))
            Text("${visible.size} channels", style = MaterialTheme.typography.bodyMedium, color = CoxColors.TextDim)
        }
        CategoryChips(groups, cat, counts, onCategory)
        Spacer(Modifier.height(6.dp))

        when {
            channels == null -> Unit
            visible.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (cat == Categories.FAVORITES) "No favorites yet.\nTap the heart on a channel (or press and hold it) to add it."
                    else "No channels in this category.",
                    textAlign = TextAlign.Center,
                    color = CoxColors.TextDim,
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 340.dp), // 1 column on phones, 2-3 on tablets
                state = gridState,
                contentPadding = PaddingValues(bottom = 12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = { it.id }) { ch ->
                    ChannelRow(
                        channel = ch,
                        program = ch.epgId?.let { nowPlaying[it] },
                        now = now,
                        onClick = { onPlay(ch.id) },
                        onToggleFavorite = { scope.launch { repo.toggleFavorite(ch) } },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: Channel,
    program: ProgramEntity?,
    now: Long,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onToggleFavorite),
        colors = ListItemDefaults.colors(containerColor = CoxColors.Bg),
        leadingContent = {
            Text(
                channel.number.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = CoxColors.TextDim,
                modifier = Modifier.width(44.dp),
            )
        },
        headlineContent = {
            Text(channel.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = if (program == null) null else {
            {
                Column {
                    Text(program.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = CoxColors.TextDim)
                    Spacer(Modifier.height(4.dp))
                    val fraction = (now - program.startMs).toFloat() / (program.endMs - program.startMs).coerceAtLeast(1)
                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(0.7f).height(3.dp),
                        drawStopIndicator = {},
                    )
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (channel.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (channel.favorite) "Remove from favorites" else "Add to favorites",
                    tint = if (channel.favorite) CoxColors.Fav else CoxColors.TextDim,
                )
            }
        },
    )
}
