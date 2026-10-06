package com.coxtv.mobile.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coxtv.AppContainer
import com.coxtv.data.SearchResult
import com.coxtv.mobile.ui.theme.CoxColors
import kotlinx.coroutines.delay

/** Live search: shows airing now (Braves, White Sox) and channels by name (ESPN, SEC Network). */
@Composable
fun SearchScreen(container: AppContainer, onPlay: (channelId: String, group: String) -> Unit) {
    val repo = container.repository
    val focus = LocalFocusManager.current
    val now by rememberNow()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searchedFor by remember { mutableStateOf("") }

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
        Text(
            when {
                query.trim().length < 2 -> "Shows on now, or channels by name"
                results.isNotEmpty() -> "${results.size} matches for \"$searchedFor\""
                searchedFor.isNotEmpty() -> "Nothing on now matches \"$searchedFor\""
                else -> "Searching…"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = CoxColors.TextDim,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.channelId + "|" + it.title }) { r ->
                ListItem(
                    modifier = Modifier.clickable { onPlay(r.channelId, r.groupName) },
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
            }
        }
    }
}
