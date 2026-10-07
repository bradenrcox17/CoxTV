package com.coxtv.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coxtv.AppContainer
import com.coxtv.data.Categories
import com.coxtv.data.DeviceLink
import com.coxtv.data.SearchResult
import com.coxtv.mobile.ui.theme.CoxColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live search: shows airing now (Braves, White Sox) and channels by name (ESPN, SEC Network).
 * Before typing: recent searches and games on now. Press and hold a result to send it to a TV.
 */
@Composable
fun SearchScreen(container: AppContainer, onPlay: (channelId: String, group: String) -> Unit) {
    val repo = container.repository
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val now by rememberNow()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searchedFor by remember { mutableStateOf("") }
    val searches by container.settings.searches.collectAsStateWithLifecycle(emptyList())
    val games by repo.sports.collectAsStateWithLifecycle(emptyList())
    val tvs = rememberOnlineTvs(container)

    fun open(r: SearchResult) {
        val q = query.trim()
        scope.launch {
            if (q.length >= 2) container.settings.addSearch(q)
            onPlay(r.channelId, r.groupName)
        }
    }

    fun cast(tv: DeviceLink.Tv, channelId: String) {
        scope.launch { repo.channel(channelId)?.let { castChannel(container, context, tv, it) } }
    }

    LaunchedEffect(Unit) { runCatching { repo.prepareSearch() } }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            searchedFor = ""
            return@LaunchedEffect
        }
        delay(250) // debounce typing
        results = runCatching { repo.search(q) }.getOrDefault(emptyList())
        searchedFor = q
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Search what's on", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Braves, White Sox, ESPN, SEC Network…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
        )
        val typing = query.trim().length >= 2
        if (typing || (searches.isEmpty() && games.isEmpty())) {
            Text(
                when {
                    !typing -> "Shows on now, or channels by name"
                    results.isNotEmpty() -> "${results.size} matches for \"$searchedFor\""
                    searchedFor.isNotEmpty() -> "Nothing on now matches \"$searchedFor\""
                    else -> "Searching…"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = CoxColors.TextDim,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (!typing) {
                if (searches.isNotEmpty()) {
                    item(key = "h-recent") { Heading("Recent searches") }
                    items(searches, key = { "s-$it" }) { q ->
                        ListItem(
                            modifier = Modifier.clickable { query = q },
                            colors = ListItemDefaults.colors(containerColor = CoxColors.Bg),
                            headlineContent = { Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = {
                                IconButton(onClick = { scope.launch { container.settings.removeSearch(q) } }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Remove from recent searches", tint = CoxColors.TextDim)
                                }
                            },
                        )
                    }
                }
                if (games.isNotEmpty()) {
                    item(key = "h-games") { Heading("Games on now") }
                    items(games.take(8), key = { "g-" + it.channel.id + it.title }) { g ->
                        ResultItem(
                            SearchResult(g.channel.id, g.title, g.channel.name, Categories.SPORTS, g.endMs), now, tvs,
                            onClick = { onPlay(g.channel.id, Categories.SPORTS) },
                            onCast = { tv -> cast(tv, g.channel.id) },
                        )
                    }
                }
            }
            items(results, key = { it.channelId + "|" + it.title }) { r ->
                ResultItem(r, now, tvs, onClick = { open(r) }, onCast = { tv -> cast(tv, r.channelId) })
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultItem(
    r: SearchResult,
    now: Long,
    tvs: List<DeviceLink.Tv>,
    onClick: () -> Unit,
    onCast: (DeviceLink.Tv) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { if (tvs.isNotEmpty()) menu = true }),
            colors = ListItemDefaults.colors(containerColor = CoxColors.Bg),
            headlineContent = { Text(r.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    if (r.endMs > 0) "${r.channelName}   |   ${minutesLeft(r.endMs, now)}" else r.channelName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = CoxColors.TextDim,
                )
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            tvs.forEach { tv ->
                DropdownMenuItem(text = { Text("Play on ${tv.name}") }, onClick = { menu = false; onCast(tv) })
            }
        }
    }
}
