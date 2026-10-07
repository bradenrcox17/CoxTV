package com.coxtv.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coxtv.AppContainer
import com.coxtv.data.DeviceLink
import com.coxtv.data.Teams
import com.coxtv.mobile.ui.theme.CoxColors
import kotlinx.coroutines.launch

/**
 * Favorite teams by sport: tap a team to star or un-star it, or search by name. Starred teams'
 * games are marked ★ and listed first in Sports on now, and sync with your TVs and the website.
 * Rows keep their place after a tap.
 */
@Composable
fun TeamsList(container: AppContainer) {
    val repo = container.repository
    val scope = rememberCoroutineScope()
    val linked by container.settings.isLinked.collectAsStateWithLifecycle(false)
    val loaded by repo.allTeams.collectAsStateWithLifecycle(null)
    val mine = loaded.orEmpty()
    // null while loading; empty when the stream server can't be reached (or not linked).
    val catalog by produceState<List<DeviceLink.TeamSport>?>(null, linked) { value = repo.teamCatalog().orEmpty() }
    var sport by rememberSaveable { mutableStateOf("nfl") }
    var query by rememberSaveable { mutableStateOf("") }

    val sports = remember(catalog) { catalog?.takeIf { it.isNotEmpty() }?.map { it.id to it.label } ?: Teams.SPORTS }
    val myKeys = mine[sport].orEmpty().map { it.key }.toSet()

    // Your teams first, then the rest A-Z; worked out when the sport or search changes (not on
    // every tap), so a row stays where it is.
    val rows = remember(sport, query, catalog, loaded != null) {
        val all = catalog?.firstOrNull { it.id == sport }?.teams.orEmpty()
        val starred = mine[sport].orEmpty()
        val starredKeys = starred.map { it.key }.toSet()
        val found = Teams.search((starred + all.filterNot { it.key in starredKeys }).distinctBy { it.key }, query)
        val typed = query.trim()
        val typedKey = Teams.key(typed)
        // Not in the list (or no list without a setup code): offer the typed name itself.
        if (typedKey.length >= 2 && found.isEmpty()) listOf(DeviceLink.Team(typedKey, typed)) else found
    }

    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            items(sports, key = { it.first }) { (id, label) ->
                val n = mine[id].orEmpty().size
                FilterChip(
                    selected = id == sport,
                    onClick = { if (sport != id) { sport = id; query = "" } },
                    label = { Text(if (n > 0) "$label  ★$n" else label) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(if (sport == "ncaaf") "Find a school, e.g. Tennessee" else "Find a team") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Text(
            if (sport == "ncaaf") "College teams count in every NCAA sport: football, basketball, baseball and more."
            else "Tap a team to star it. Its games are marked ★ and listed first in Sports on now.",
            style = MaterialTheme.typography.bodySmall,
            color = CoxColors.TextDim,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        when {
            catalog == null -> Message("Loading teams…")
            rows.isEmpty() && catalog!!.isEmpty() && query.isBlank() -> Message(
                if (linked) "The team list isn't available right now. Type a team's name above to add it."
                else "The full team list comes from tv.thecoxhome.com (set this phone up with a code in Settings > Edit sources). You can still type a team's name above to add it.",
            )
            rows.isEmpty() -> Message("No teams match \"$query\".")
            else -> {
                val listState = rememberLazyListState()
                LaunchedEffect(sport, query) { listState.scrollToItem(0) }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(rows, key = { it.key }) { team ->
                        val on = team.key in myKeys
                        val custom = !on && catalog!!.none { s -> s.id == sport && s.teams.any { it.key == team.key } }
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = CoxColors.Bg),
                            leadingContent = {
                                Text(if (on) "★" else "☆", fontSize = 22.sp, color = if (on) CoxColors.Accent else CoxColors.TextDim, modifier = Modifier.width(28.dp))
                            },
                            headlineContent = {
                                Text(if (custom) "Add \"${team.display}\"" else team.display, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = if (on) { { Text("Your team", color = CoxColors.TextDim) } } else null,
                            modifier = Modifier.clickable { scope.launch { repo.setTeam(team, sport, !on) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
        Text(text, textAlign = TextAlign.Center, color = CoxColors.TextDim)
    }
}
