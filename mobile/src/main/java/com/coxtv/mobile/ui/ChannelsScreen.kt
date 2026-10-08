package com.coxtv.mobile.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coxtv.AppContainer
import com.coxtv.data.Categories
import com.coxtv.data.CategoryExtras
import com.coxtv.data.DeviceLink
import com.coxtv.data.Teams
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
    val context = LocalContext.current
    val tvs = rememberOnlineTvs(container)
    val scope = rememberCoroutineScope()
    val now by rememberNow()
    val channels by repo.channels.collectAsStateWithLifecycle(null)
    val shown by repo.categories.collectAsStateWithLifecycle(null)
    val cats = shown.orEmpty()
    val nowPlaying by repo.nowPlaying.collectAsStateWithLifecycle(emptyMap())
    val extras by repo.categoryExtras.collectAsStateWithLifecycle(CategoryExtras.EMPTY)
    val all = channels.orEmpty()

    // First visit: last watched category, else Favorites if there are any, else All.
    LaunchedEffect(channels != null, shown != null) {
        if (category != null || channels == null || shown == null) return@LaunchedEffect
        val last = container.settings.lastWatched()?.category
        val hasFavorites = all.any { it.favorite }
        onCategory(
            when {
                last != null && last in cats && (last != Categories.FAVORITES || hasFavorites) -> last
                hasFavorites && Categories.FAVORITES in cats -> Categories.FAVORITES
                // Not an empty Recent / Sports list: All Channels, else the first playlist group.
                Categories.ALL in cats -> Categories.ALL
                else -> cats.firstOrNull { !Categories.isBuiltIn(it) } ?: cats.first()
            },
        )
    }
    // A category turned off in Categories & favorites: fall back to the first one still shown.
    LaunchedEffect(cats) {
        if (category != null && cats.isNotEmpty() && category !in cats) onCategory(cats.first())
    }

    val cat = category ?: Categories.ALL
    val visible = remember(all, cat, extras) { Categories.filter(all, cat, extras) }
    val cfbGuide by repo.cfbGuide.collectAsStateWithLifecycle(null)
    val allTeams by repo.allTeams.collectAsStateWithLifecycle(emptyMap())
    val counts = remember(all, extras, cfbGuide) {
        all.groupingBy { it.groupName }.eachCount() +
            Categories.LEAGUE_KEYS.associateWith { 0 } +
            extras.sports.groupingBy { Categories.LEAGUE + it.league }.eachCount() +
            mapOf(
                Categories.ALL to all.size,
                Categories.FAVORITES to all.count { it.favorite },
                Categories.RECENT to extras.recent.size,
                Categories.SPORTS to extras.sports.size,
            ) + (cfbGuide?.let { mapOf(Categories.CFB to it.rows.size) } ?: emptyMap())
    }
    val gridState = rememberLazyGridState()
    LaunchedEffect(cat) { gridState.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(Categories.label(cat), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    cat == Categories.CFB -> cfbGuide?.let { g -> "${g.rows.size} games" + (g.week?.let { " · week $it" } ?: "") }.orEmpty()
                    cat == Categories.SPORTS || Categories.leagueOf(cat) != null -> "${visible.size} live games"
                    else -> "${visible.size} channels"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = CoxColors.TextDim,
            )
        }
        CategoryChips(cats, cat, counts, onCategory)
        Spacer(Modifier.height(6.dp))

        when {
            channels == null -> Unit
            cat == Categories.CFB -> CfbList(container, onPlay = onPlay)
            visible.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        cat == Categories.FAVORITES -> "No favorites yet.\nTap the heart on a channel to add it."
                        cat == Categories.RECENT -> "Channels you watch will show up here."
                        cat == Categories.SPORTS -> "No live games right now (or the TV guide hasn't loaded yet)."
                        Categories.leagueOf(cat) != null -> "No live ${Categories.leagueOf(cat)} games right now."
                        else -> "No channels in this category."
                    },
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
                        label = when {
                            cat == Categories.SPORTS -> extras.sportsLabels[ch.id]
                            Categories.leagueOf(cat) != null -> extras.sports.firstOrNull { it.channel.id == ch.id }
                                ?.let { (if (it.mine) "★ " else "") + it.title }
                            else -> null
                        },
                        now = now,
                        onClick = { onPlay(ch.id) },
                        onToggleFavorite = { scope.launch { repo.toggleFavorite(ch) } },
                        onHide = { scope.launch { repo.setHidden(ch, true) } },
                        tvs = tvs,
                        onCast = { tv -> castChannel(container, context, tv, ch) },
                        teams = (if (cat == Categories.SPORTS || Categories.leagueOf(cat) != null)
                            extras.sports.firstOrNull { it.channel.id == ch.id } else null)
                            ?.let { g -> Teams.inGame(g.title, g.league, allTeams).map { (t, mine) -> Triple(t, mine, Teams.sportFor(g.league)) } }
                            .orEmpty(),
                        onToggleTeam = { team, sport -> scope.launch { repo.toggleTeam(team, sport) } },
                        // Your team's game: shown as a card.
                        featured = (if (cat == Categories.SPORTS || Categories.leagueOf(cat) != null)
                            extras.sports.firstOrNull { it.channel.id == ch.id && it.mine } else null)
                            ?.let { it.league to it.title },
                    )
                }
            }
        }
    }
}

/** A live game for one of your teams: LIVE badge and the matchup up front. */
@Composable
private fun YourTeamCard(channel: Channel, league: String, title: String, program: ProgramEntity?, now: Long, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .background(CoxColors.PanelHi, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "LIVE",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = CoxColors.OnAccent,
                modifier = Modifier.background(CoxColors.Accent, androidx.compose.foundation.shape.RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Text("  ★ Your team", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = CoxColors.Accent)
        }
        Spacer(Modifier.height(8.dp))
        val sides = com.coxtv.data.Teams.sides(title)
        Text(if (sides.size == 2) "${sides[0]} vs ${sides[1]}" else title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text("$league · ${channel.number}  ${channel.name}", style = MaterialTheme.typography.bodyMedium, color = CoxColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (program != null) {
            Spacer(Modifier.height(10.dp))
            val fraction = (now - program.startMs).toFloat() / (program.endMs - program.startMs).coerceAtLeast(1)
            LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(0.7f).height(3.dp), drawStopIndicator = {}, trackColor = com.coxtv.mobile.ui.theme.CoxColors.PanelHi)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: Channel,
    program: ProgramEntity?,
    label: String?,
    now: Long,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onHide: () -> Unit,
    tvs: List<DeviceLink.Tv>,
    onCast: (DeviceLink.Tv) -> Unit,
    teams: List<Triple<DeviceLink.Team, Boolean, String>> = emptyList(),
    onToggleTeam: (DeviceLink.Team, String) -> Unit = { _, _ -> },
    featured: Pair<String, String>? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        if (featured != null) {
            YourTeamCard(channel, featured.first, featured.second, program, now, Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true }))
        } else ListItem(
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true }),
            colors = ListItemDefaults.colors(containerColor = CoxColors.Bg),
            leadingContent = {
                Text(
                    channel.number.toString(),
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = com.coxtv.mobile.ui.theme.CoxFonts.Mono),
                    color = CoxColors.TextDim,
                    maxLines = 1,
                    modifier = Modifier.width(52.dp),
                )
            },
            headlineContent = {
                Text(channel.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = if (program == null && label == null) null else {
                {
                    Column {
                        Text(label ?: program!!.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = CoxColors.TextDim)
                        if (program != null) {
                            Spacer(Modifier.height(4.dp))
                            val fraction = (now - program.startMs).toFloat() / (program.endMs - program.startMs).coerceAtLeast(1)
                            LinearProgressIndicator(
                                progress = { fraction.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(0.7f).height(3.dp),
                                drawStopIndicator = {}, trackColor = com.coxtv.mobile.ui.theme.CoxColors.PanelHi,
                            )
                        }
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
        // Press and hold: channel options.
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (channel.favorite) "Remove from favorites" else "Add to favorites") },
                onClick = { menu = false; onToggleFavorite() },
            )
            DropdownMenuItem(
                text = { Text("Hide this channel") },
                onClick = { menu = false; onHide() },
            )
            tvs.forEach { tv ->
                DropdownMenuItem(
                    text = { Text("Play on ${tv.name}") },
                    onClick = { menu = false; onCast(tv) },
                )
            }
            teams.forEach { (team, mine, sport) ->
                DropdownMenuItem(
                    text = { Text((if (mine) "★  Remove " else "☆  Star ") + team.display + " (" + Teams.sportLabel(sport) + " team)") },
                    onClick = { menu = false; onToggleTeam(team, sport) },
                )
            }
        }
    }
}
