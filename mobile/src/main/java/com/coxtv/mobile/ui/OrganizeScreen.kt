package com.coxtv.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coxtv.AppContainer
import com.coxtv.data.Categories
import com.coxtv.mobile.ui.theme.CoxColors
import kotlinx.coroutines.launch

/** Choose which categories appear as chips, their order, and the order of favorites. */
@Composable
fun OrganizeScreen(container: AppContainer, onBack: () -> Unit) {
    val repo = container.repository
    val scope = rememberCoroutineScope()
    val options by repo.categoryOptions.collectAsStateWithLifecycle(emptyList())
    val channels by repo.channels.collectAsStateWithLifecycle(null)
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val all = channels.orEmpty()
    val counts = remember(all) {
        all.groupingBy { it.groupName }.eachCount() +
            mapOf(Categories.ALL to all.size, Categories.FAVORITES to all.count { it.favorite })
    }
    val favorites = remember(all) { Categories.filter(all, Categories.FAVORITES) }

    // Own Surface: this screen sits outside the main Scaffold, which normally sets the text colour.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Categories & favorites", style = MaterialTheme.typography.titleLarge)
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Categories") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Favorites order") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Hidden") })
        }

        if (tab == 0) {
            val shown = options.filter { it.enabled }
            val hidden = options.filter { !it.enabled }
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "h1") { Header("Shown", "Use the arrows to change the order.") }
                itemsIndexed(shown, key = { _, o -> "s:${o.key}" }) { i, o ->
                    CategoryRow(o.key, counts[o.key]) {
                        IconButton(onClick = { scope.launch { repo.moveCategory(o.key, -1) } }, enabled = i > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                        }
                        IconButton(onClick = { scope.launch { repo.moveCategory(o.key, 1) } }, enabled = i < shown.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                        }
                        IconButton(onClick = { scope.launch { repo.setCategoryEnabled(o.key, false) } }, enabled = shown.size > 1) {
                            Icon(Icons.Filled.Close, contentDescription = "Hide")
                        }
                    }
                }
                if (hidden.isNotEmpty()) {
                    item(key = "h2") { Header("Add more", "Tap + to show a category.") }
                    items(hidden, key = { "h:${it.key}" }) { o ->
                        CategoryRow(o.key, counts[o.key]) {
                            IconButton(onClick = { scope.launch { repo.setCategoryEnabled(o.key, true) } }) {
                                Icon(Icons.Filled.Add, contentDescription = "Show")
                            }
                        }
                    }
                }
            }
        } else if (tab == 2) {
            HiddenList(container)
        } else if (channels != null && favorites.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No favorites yet.\nTap the heart on a channel (or press and hold it) to add it.",
                    textAlign = TextAlign.Center,
                    color = CoxColors.TextDim,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "h") { Header(null, "Use the arrows to change the order. Channel up/down in the player follows it too.") }
                itemsIndexed(favorites, key = { _, ch -> ch.id }) { i, ch ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = CoxColors.Bg),
                        leadingContent = { Text(ch.number.toString(), color = CoxColors.TextDim) },
                        headlineContent = { Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { scope.launch { repo.moveFavorite(ch.id, -1) } }, enabled = i > 0) {
                                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                                }
                                IconButton(onClick = { scope.launch { repo.moveFavorite(ch.id, 1) } }, enabled = i < favorites.lastIndex) {
                                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                                }
                                IconButton(onClick = { scope.launch { repo.removeFavorite(ch.id) } }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove from favorites")
                                }
                            }
                        },
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun Header(title: String?, hint: String) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = CoxColors.Accent)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = CoxColors.TextDim)
    }
}

@Composable
private fun CategoryRow(key: String, count: Int?, actions: @Composable () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = CoxColors.Bg),
        headlineContent = { Text(Categories.label(key), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = count?.let { { Text("$it channels", color = CoxColors.TextDim) } },
        trailingContent = { Row { actions() } },
    )
}

@Composable
private fun HiddenList(container: AppContainer) {
    val repo = container.repository
    val scope = rememberCoroutineScope()
    val hidden by repo.hiddenChannels.collectAsStateWithLifecycle(null)
    val list = hidden ?: return
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "No hidden channels.\nPress and hold a channel in the list to hide it.",
                textAlign = TextAlign.Center,
                color = CoxColors.TextDim,
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "h") { Header(null, "Hidden channels don't appear in lists, the guide or search.") }
        items(list, key = { it.id }) { ch ->
            ListItem(
                colors = ListItemDefaults.colors(containerColor = CoxColors.Bg),
                headlineContent = { Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(ch.groupName, color = CoxColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    androidx.compose.material3.TextButton(onClick = { scope.launch { repo.setHidden(ch, false) } }) { Text("Show") }
                },
            )
        }
    }
}
