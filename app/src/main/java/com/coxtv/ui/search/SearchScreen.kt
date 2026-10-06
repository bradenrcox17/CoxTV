package com.coxtv.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import com.coxtv.data.SearchResult
import com.coxtv.ui.components.Clock
import com.coxtv.ui.components.FocusTile
import com.coxtv.ui.components.LocalIsTouch
import com.coxtv.ui.components.TvTextField
import com.coxtv.ui.theme.CoxColors
import kotlinx.coroutines.delay

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
                query.trim().length < 2 -> "Type at least 2 letters"
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
            items(results, key = { it.channelId + "|" + it.title }) { r ->
                ResultRow(r, onClick = { onPlay(r.channelId, r.groupName) })
            }
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
