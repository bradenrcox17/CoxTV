package com.coxtv.mobile.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coxtv.AppContainer
import com.coxtv.data.DeviceLink
import com.coxtv.data.TvRepository
import com.coxtv.mobile.ui.theme.CoxColors
import kotlinx.coroutines.launch

private const val MINE = "★ Your teams"
private const val OTHER = "Other games"
private val SEP = Regex("""\s+(vs\.?|at|@)\s+""", RegexOption.IGNORE_CASE)

private fun matchupText(g: DeviceLink.CfbGame): String {
    if (g.ranks.none { it != null }) return g.matchup
    val parts = g.matchup.split(SEP, limit = 2)
    if (parts.size < 2) return g.matchup
    val sep = SEP.find(g.matchup)?.value ?: " vs "
    fun tag(i: Int) = g.ranks.getOrNull(i)?.let { "#$it " }.orEmpty()
    return "${tag(0)}${parts[0]}$sep${tag(1)}${parts[1]}"
}

private fun statusText(g: DeviceLink.CfbGame): String =
    if (g.live) listOf(g.score, g.period, g.clock).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Live now" }
    else listOf(g.kickoff, g.network).filter { it.isNotBlank() }.joinToString(" · ")

private fun teamsOf(g: DeviceLink.CfbGame): List<DeviceLink.Team> {
    val names = g.matchup.split(SEP).map { it.trim() }
    return g.teamKeys.mapIndexed { i, key -> DeviceLink.Team(key, names.getOrNull(i)?.ifBlank { null } ?: key) }
}

/** The College Football guide: your teams first, then by conference. Tap to watch; hold to star teams. */
@Composable
fun CfbList(container: AppContainer, onPlay: (channelId: String) -> Unit) {
    val repo = container.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val guide by repo.cfbGuide.collectAsStateWithLifecycle(null)
    val linked by container.settings.isLinked.collectAsStateWithLifecycle(true)
    val myTeams by repo.favoriteTeams().collectAsStateWithLifecycle(emptyList())
    val mine = myTeams.map { it.key }.toSet()

    val g = guide
    when {
        !linked -> Message("The College Football guide comes from tv.thecoxhome.com. Set this phone up with a code (Settings > Edit sources) to see it.")
        g == null -> Message("Loading the College Football guide…")
        g.rows.isEmpty() -> Message("No college football games this week.")
        else -> {
            val sections = g.rows.groupBy { if (it.mine) MINE else it.game.conference.ifBlank { OTHER } }
                .entries.sortedWith(compareBy({ it.key != MINE }, { it.key == OTHER }, { it.key }))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                for ((head, rows) in sections) {
                    item(key = "h:$head") {
                        Text(
                            head,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
                        )
                    }
                    items(rows, key = { "g:" + it.game.matchup + it.game.kickoffSort }) { row ->
                        GameItem(row, mine,
                            onClick = {
                                val ch = row.channel
                                if (ch != null) onPlay(ch.id)
                                else Toast.makeText(context, "No channel is showing this game yet.", Toast.LENGTH_SHORT).show()
                            },
                            onToggleTeam = { team -> scope.launch { repo.toggleTeam(team) } },
                        )
                    }
                }
                item(key = "hint") {
                    Text(
                        "Press and hold a game to star your teams. Your teams are shared with tv.thecoxhome.com.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CoxColors.TextDim,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameItem(row: TvRepository.CfbRow, mine: Set<String>, onClick: () -> Unit, onToggleTeam: (DeviceLink.Team) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true }),
            colors = ListItemDefaults.colors(containerColor = CoxColors.Bg),
            leadingContent = {
                Box(
                    Modifier.width(48.dp).height(26.dp)
                        .background(if (row.game.live) Color(0xFFC62828) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (row.game.live) "LIVE" else "🏈", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            },
            headlineContent = {
                Text(matchupText(row.game) + if (row.mine) "  ★" else "", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(
                    statusText(row.game) + (row.channel?.let { " · ${it.name}" } ?: " · no channel found yet"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = CoxColors.TextDim,
                )
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            teamsOf(row.game).forEach { team ->
                DropdownMenuItem(
                    text = { Text((if (team.key in mine) "★  Remove " else "☆  Star ") + team.display) },
                    onClick = { menu = false; onToggleTeam(team) },
                )
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = CoxColors.TextDim)
    }
}
