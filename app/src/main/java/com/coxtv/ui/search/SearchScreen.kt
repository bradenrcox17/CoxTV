package com.coxtv.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.coxtv.AppContainer
import com.coxtv.data.Categories
import com.coxtv.data.SearchResult
import com.coxtv.ui.components.Clock
import com.coxtv.ui.components.FocusTile
import com.coxtv.ui.components.LocalIsTouch
import com.coxtv.ui.components.TvTextField
import com.coxtv.ui.components.collectAsStateCompat
import com.coxtv.ui.theme.CoxColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live search: shows airing now (Braves, White Sox) and channels by name (ESPN, SEC Network),
 * ignoring case, spaces and punctuation. Selecting a result tunes straight to the channel.
 */
@Composable
fun SearchScreen(container: AppContainer, onPlay: (channelId: String, group: String) -> Unit) {
    val repo = container.repository
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchedFor by remember { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val searches by container.settings.searches.collectAsStateCompat(emptyList())
    val games by repo.sports.collectAsStateCompat(emptyList())

    fun open(channelId: String, group: String) {
        val q = query.trim()
        scope.launch {
            if (q.length >= 2) container.settings.addSearch(q)
            onPlay(channelId, group)
        }
    }

    LaunchedEffect(Unit) {
        runCatching { fieldFocus.requestFocus() }
        runCatching { repo.prepareSearch() } // build the search index while you type
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            searchedFor = ""
            return@LaunchedEffect
        }
        delay(300) // debounce typing
        searching = true
        results = runCatching { repo.search(q) }.getOrDefault(emptyList())
        searchedFor = q
        searching = false
    }

    Column(Modifier.fillMaxSize().background(CoxColors.Bg).padding(horizontal = 48.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Search what's on now", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            Clock()
        }
        Text(
            "Shows on now (Braves, White Sox) or channels (ESPN, SEC Network)",
            style = MaterialTheme.typography.bodyMedium,
            color = CoxColors.TextDim,
        )
        Spacer(Modifier.height(16.dp))
        TvTextField(
            value = query,
            onValueChange = { query = it },
            label = "Search",
            placeholder = if (LocalIsTouch.current) "Tap to type" else "Press OK to type",
            keyboardType = KeyboardType.Text,
            modifier = Modifier.width(560.dp).focusRequester(fieldFocus),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                query.trim().length < 2 -> if (searches.isNotEmpty() || games.isNotEmpty()) "" else "Type at least 2 letters"
                searching && results.isEmpty() -> "Searching..."
                results.isNotEmpty() -> "${results.size} matches for \"$searchedFor\""
                searchedFor.isNotEmpty() -> "Nothing on now matches \"$searchedFor\""
                else -> ""
            },
            style = MaterialTheme.typography.bodyMedium,
            color = CoxColors.TextDim,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
            if (query.trim().length < 2) {
                // Before typing: recent searches, and games on now.
                if (searches.isNotEmpty()) {
                    item(key = "h-recent") { Heading("Recent searches") }
                    items(searches, key = { "s-$it" }) { q ->
                        SimpleRow(q, onClick = { query = q })
                    }
                    item(key = "clear") {
                        SimpleRow("Clear recent searches", dim = true, onClick = { scope.launch { container.settings.clearSearches() } })
                    }
                }
                if (games.isNotEmpty()) {
                    item(key = "h-games") { Heading("Games on now") }
                    items(games.take(6), key = { "g-" + it.channel.id + it.title }) { g ->
                        ResultRow(
                            SearchResult(g.channel.id, g.title, g.channel.name, g.channel.groupName, g.endMs),
                            onClick = { onPlay(g.channel.id, Categories.SPORTS) },
                        )
                    }
                }
            }
            items(results, key = { it.channelId + "|" + it.title }) { r ->
                ResultRow(r, onClick = { open(r.channelId, r.groupName) })
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = CoxColors.Accent,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 4.dp),
    )
}

@Composable
private fun SimpleRow(text: String, dim: Boolean = false, onClick: () -> Unit) {
    FocusTile(onClick = onClick, focusedScale = 1.01f, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                color = if (dim) LocalContentColor.current.copy(alpha = 0.7f) else LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ResultRow(r: SearchResult, onClick: () -> Unit) {
    val left = if (r.endMs > 0) {
        val mins = ((r.endMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        if (mins >= 60) "${mins / 60}h ${mins % 60}m left" else "$mins min left"
    } else ""
    FocusTile(onClick = onClick, focusedScale = 1.01f, modifier = Modifier.fillMaxWidth().height(64.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.Center) {
            Text(
                r.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (left.isNotEmpty()) "${r.channelName}   |   $left" else r.channelName,
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
