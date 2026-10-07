package com.coxtv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.coxtv.AppContainer
import com.coxtv.data.DeviceLink
import com.coxtv.data.TvRepository
import com.coxtv.ui.components.CoxButton
import com.coxtv.ui.components.FocusTile
import com.coxtv.ui.components.LocalIsTouch
import com.coxtv.ui.components.collectAsStateCompat
import com.coxtv.ui.components.touchClick
import com.coxtv.ui.theme.CoxColors
import kotlinx.coroutines.launch

/** "★ Your teams" first, then each conference, then everything else. */
private fun sections(rows: List<TvRepository.CfbRow>): List<Pair<String, List<TvRepository.CfbRow>>> {
    val grouped = rows.groupBy { if (it.mine) MINE else it.game.conference.ifBlank { OTHER } }
    return grouped.entries.sortedWith(compareBy({ it.key != MINE }, { it.key == OTHER }, { it.key })).map { it.key to it.value }
}

private const val MINE = "★ Your teams"
private const val OTHER = "Other games"

fun cfbMatchup(g: DeviceLink.CfbGame): String {
    if (g.ranks.none { it != null }) return g.matchup
    val parts = g.matchup.split(Regex("""\s+(vs\.?|at|@)\s+""", RegexOption.IGNORE_CASE), limit = 2)
    if (parts.size < 2) return g.matchup
    val sep = Regex("""\s+(vs\.?|at|@)\s+""", RegexOption.IGNORE_CASE).find(g.matchup)?.value ?: " vs "
    fun tag(i: Int) = g.ranks.getOrNull(i)?.let { "#$it " }.orEmpty()
    return "${tag(0)}${parts[0]}$sep${tag(1)}${parts[1]}"
}

fun cfbStatus(g: DeviceLink.CfbGame): String =
    if (g.live) listOf(g.score, g.period, g.clock).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Live now" }
    else listOf(g.kickoff, g.network).filter { it.isNotBlank() }.joinToString(" · ")

/** Team names in a matchup ("Florida at Missouri" -> Florida, Missouri) paired with their keys. */
fun cfbTeams(g: DeviceLink.CfbGame): List<DeviceLink.Team> {
    val names = g.matchup.split(Regex("""\s+(vs\.?|at|@)\s+""", RegexOption.IGNORE_CASE)).map { it.trim() }
    return g.teamKeys.mapIndexed { i, key -> DeviceLink.Team(key, names.getOrNull(i)?.ifBlank { null } ?: key) }
}

/** The College Football guide: this week's games, your teams first; select a game to watch it. */
@Composable
fun CfbPane(
    container: AppContainer,
    listState: LazyListState,
    listFocus: FocusRequester,
    onPlay: (channelId: String) -> Unit,
    onStatus: (String) -> Unit,
) {
    val repo = container.repository
    val scope = rememberCoroutineScope()
    val guide by repo.cfbGuide.collectAsStateCompat(null)
    val linked by container.settings.isLinked.collectAsStateCompat(true)
    var teamsFor by remember { mutableStateOf<DeviceLink.CfbGame?>(null) }
    val myTeams by repo.favoriteTeams().collectAsStateCompat(emptyList())
    val touch = LocalIsTouch.current

    val g = guide
    // A Box, so the teams menu draws over the list (not below it).
    Box(Modifier.fillMaxSize()) {
    when {
        !linked -> Empty("The College Football guide comes from tv.thecoxhome.com.\nSet this device up with a code (Edit sources > Enter setup code) to see it.")
        g == null -> Empty("Loading the College Football guide…")
        g.rows.isEmpty() -> Empty("No college football games this week.")
        else -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val firstKey = sections(g.rows).firstOrNull()?.second?.firstOrNull()?.let { it.game.matchup + it.game.kickoffSort }
            for ((head, rows) in sections(g.rows)) {
                item(key = "h:$head") {
                    Text(
                        head,
                        style = MaterialTheme.typography.labelLarge,
                        color = CoxColors.Accent,
                        modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp),
                    )
                }
                items(rows, key = { "g:" + it.game.matchup + it.game.kickoffSort }) { row ->
                    GameRow(
                        row = row,
                        // Entering the list (Right from the sidebar) lands on the first game.
                        modifier = if (row.game.matchup + row.game.kickoffSort == firstKey) Modifier.focusRequester(listFocus) else Modifier,
                        onClick = {
                            val ch = row.channel
                            if (ch != null) onPlay(ch.id) else onStatus("No channel is showing ${row.game.matchup} yet")
                        },
                        onOptions = { teamsFor = row.game },
                    )
                }
            }
            item(key = "hint") {
                Text(
                    if (touch) "Press and hold a game to star your teams. Your teams are shared with tv.thecoxhome.com."
                    else "Press ☰ Menu on a game to star your teams. Your teams are shared with tv.thecoxhome.com.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CoxColors.TextDim,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }

    teamsFor?.let { game ->
        TeamMenu(
            game = game,
            mine = myTeams.map { it.key }.toSet(),
            onToggle = { team -> scope.launch { repo.toggleTeam(team) } },
            onDismiss = { teamsFor = null; runCatching { listFocus.requestFocus() } },
        )
    }
    }
}

@Composable
private fun GameRow(row: TvRepository.CfbRow, modifier: Modifier, onClick: () -> Unit, onOptions: () -> Unit) {
    FocusTile(
        onClick = onClick,
        onLongClick = onOptions,
        focusedScale = 1.01f,
        modifier = modifier.fillMaxWidth().height(62.dp).onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Menu) { onOptions(); true } else false
        },
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(56.dp).height(28.dp)
                    .background(if (row.game.live) Color(0xFFC62828) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (row.game.live) "LIVE" else "🏈", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(cfbMatchup(row.game), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    cfbStatus(row.game) + (row.channel?.let { " · ${it.name}" } ?: " · no channel found yet"),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.mine) Text("★", color = CoxColors.Fav, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun TeamMenu(game: DeviceLink.CfbGame, mine: Set<String>, onToggle: (DeviceLink.Team) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(game) { runCatching { first.requestFocus() } }
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).touchClick(onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(420.dp).background(CoxColors.Panel, RoundedCornerShape(14.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Your teams", style = MaterialTheme.typography.titleMedium)
            cfbTeams(game).forEachIndexed { i, team ->
                CoxButton(
                    (if (team.key in mine) "★  " else "☆  ") + team.display,
                    onClick = { onToggle(team) },
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                )
            }
            CoxButton("Done", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            Text(
                "Starred teams' games are listed first here, in the phone app and on tv.thecoxhome.com.",
                style = MaterialTheme.typography.bodySmall,
                color = CoxColors.TextDim,
            )
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = CoxColors.TextDim, textAlign = TextAlign.Center)
    }
}
