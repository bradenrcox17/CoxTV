package com.coxtv.ui.organize

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.coxtv.AppContainer
import com.coxtv.data.DeviceLink
import com.coxtv.data.Teams
import com.coxtv.ui.components.FocusTile
import com.coxtv.ui.components.TvTextField
import com.coxtv.ui.components.collectAsStateCompat
import com.coxtv.ui.theme.CoxColors
import kotlinx.coroutines.launch

/**
 * Favorite teams by sport: star or un-star any team, or find one by name. Starred teams' games
 * are marked ★ and listed first in Sports on now, and sync with the other apps and the website.
 * Rows keep their place after OK, so focus never jumps.
 */
@Composable
fun TeamsPane(container: AppContainer) {
    val repo = container.repository
    val scope = rememberCoroutineScope()
    val linked by container.settings.isLinked.collectAsStateCompat(false)
    val loaded by repo.allTeams.collectAsStateCompat(null)
    val mine = loaded.orEmpty()
    // null while loading; empty when the stream server can't be reached (or not linked).
    val catalog by produceState<List<DeviceLink.TeamSport>?>(null, linked) { value = repo.teamCatalog().orEmpty() }
    var sport by rememberSaveable { mutableStateOf("nfl") }
    var query by rememberSaveable { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }

    val sports = remember(catalog) {
        catalog?.takeIf { it.isNotEmpty() }?.map { it.id to it.label } ?: Teams.SPORTS
    }
    // Left from the teams goes back to the sport being shown (not whichever sport is level with it).
    val sportFocus = remember(sports) { sports.associate { it.first to FocusRequester() } }
    val backToSport = sportFocus[sport] ?: FocusRequester.Default
    val myTeams = mine[sport].orEmpty()
    val myKeys = myTeams.map { it.key }.toSet()

    // Your teams first, then the rest A-Z. Worked out when the sport or search changes (not on
    // every star), so a row stays where it is after OK.
    val rows = remember(sport, query, catalog, loaded != null) {
        val all = catalog?.firstOrNull { it.id == sport }?.teams.orEmpty()
        val starred = mine[sport].orEmpty()
        val starredKeys = starred.map { it.key }.toSet()
        val found = Teams.search((starred + all.filterNot { it.key in starredKeys }).distinctBy { it.key }, query)
        val typed = query.trim()
        val typedKey = Teams.key(typed)
        // Not in the list (or no list without a setup code): offer the typed name itself.
        if (typedKey.length >= 2 && found.isEmpty()) listOf(DeviceLink.Team(typedKey, typed))
        else found
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(320.dp).fillMaxHeight()) {
            Text("Sport", style = MaterialTheme.typography.titleMedium)
            Text("College teams count in every NCAA sport.", style = MaterialTheme.typography.bodySmall, color = CoxColors.TextDim)
            Spacer(Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(sports, key = { it.first }) { (id, label) ->
                    FocusTile(
                        onClick = { sport = id },
                        selected = id == sport,
                        focusedScale = 1.01f,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                            .focusRequester(sportFocus.getValue(id))
                            .focusProperties { right = searchFocus } // always into "Find a team"
                            .onFocusChanged { if (it.isFocused && sport != id) { sport = id; query = "" } },
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f))
                            val n = mine[id].orEmpty().size
                            if (n > 0) Text("★ $n", style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(28.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val label = sports.firstOrNull { it.first == sport }?.second ?: Teams.sportLabel(sport)
            Text(
                if (myTeams.isEmpty()) "$label: no favorite teams yet" else "$label: ${myTeams.joinToString(", ") { it.display }}",
                style = MaterialTheme.typography.titleMedium, maxLines = 1,
            )
            Text(
                "Press OK to star or un-star a team. Their games are marked ★ and listed first in Sports on now.",
                style = MaterialTheme.typography.bodySmall, color = CoxColors.TextDim,
            )
            Spacer(Modifier.height(10.dp))
            TvTextField(
                value = query,
                onValueChange = { query = it },
                label = "Find a team",
                placeholder = if (sport == "ncaaf") "School name, e.g. Tennessee" else "Team name",
                keyboardType = KeyboardType.Text,
                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).focusProperties { left = backToSport },
            )
            Spacer(Modifier.height(10.dp))
            when {
                catalog == null -> Message("Loading teams…")
                rows.isEmpty() && catalog!!.isEmpty() && query.isBlank() -> Message(
                    if (linked) "The team list isn't available right now. Type a team's name above to add it."
                    else "The full team list comes from tv.thecoxhome.com (set this device up with a code in Edit sources).\nYou can still type a team's name above to add it.",
                )
                rows.isEmpty() -> Message("No teams match \"$query\".")
                else -> {
                    val listState = rememberLazyListState()
                    LaunchedEffect(sport, query) { listState.scrollToItem(0) }
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(rows, key = { it.key }) { team ->
                            val on = team.key in myKeys
                            val custom = catalog!!.none { s -> s.id == sport && s.teams.any { it.key == team.key } } && !on
                            FocusTile(
                                onClick = { scope.launch { repo.setTeam(team, sport, !on) } },
                                focusedScale = 1.01f,
                                modifier = Modifier.fillMaxWidth().height(44.dp).focusProperties { left = backToSport },
                            ) {
                                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (on) "★" else "☆", fontSize = 20.sp, modifier = Modifier.width(32.dp))
                                    Text(
                                        if (custom) "Add \"${team.display}\"" else team.display,
                                        style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f),
                                    )
                                    if (on) Text("Your team", style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.TopCenter) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = CoxColors.TextDim)
    }
}
