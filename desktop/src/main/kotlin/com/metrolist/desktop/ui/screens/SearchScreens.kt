package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.metrolist.desktop.data.Library
import com.metrolist.desktop.ui.Artwork
import com.metrolist.desktop.ui.Actions
import com.metrolist.desktop.ui.ItemRow
import com.metrolist.desktop.ui.LoadView
import com.metrolist.desktop.ui.LocalNavigator
import com.metrolist.desktop.ui.Screen
import com.metrolist.desktop.ui.SectionHeader
import com.metrolist.desktop.ui.rememberLoad
import com.metrolist.desktop.ui.subtitleOf
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SearchSuggestions
import com.metrolist.innertube.models.YTItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

private val filters =
    listOf(
        "All" to null,
        "Songs" to YouTube.SearchFilter.FILTER_SONG,
        "Videos" to YouTube.SearchFilter.FILTER_VIDEO,
        "Albums" to YouTube.SearchFilter.FILTER_ALBUM,
        "Artists" to YouTube.SearchFilter.FILTER_ARTIST,
        "Community playlists" to YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST,
        "Featured playlists" to YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST,
        "Podcasts" to YouTube.SearchFilter.FILTER_PODCAST,
        "Episodes" to YouTube.SearchFilter.FILTER_EPISODE,
    )

/** Top-bar search field with live suggestions, recent searches and recommended results. */
@Composable
fun SearchBox(focusRequester: FocusRequester, onFocus: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    var text by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<SearchSuggestions?>(null) }
    val lib by Library.state.collectAsState()
    val current = nav.current
    LaunchedEffect(current) { text = if (current is Screen.Search) current.query else "" }
    LaunchedEffect(text) {
        if (text.isBlank()) {
            suggestions = null
            return@LaunchedEffect
        }
        delay(180)
        suggestions = YouTube.searchSuggestions(text).getOrNull()
    }
    fun submit(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        text = query
        Library.addSearch(query)
        nav.push(Screen.Search(query))
        focused = false
        onFocus(false)
    }
    Box(modifier) {
        TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Search songs, albums, artists, podcasts") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Default.Close, "Clear") } },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors =
                TextFieldDefaults.colors(
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        focused = it.isFocused
                        onFocus(it.isFocused)
                    }.onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                            submit(text)
                            true
                        } else if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                            focused = false
                            false
                        } else {
                            false
                        }
                    },
        )
        val s = suggestions
        val showHistory = text.isBlank() && lib.searchHistory.isNotEmpty()
        if (focused && (showHistory || (s != null && (s.queries.isNotEmpty() || s.recommendedItems.isNotEmpty())))) {
            Popup(offset = IntOffset(0, 150), properties = PopupProperties(focusable = false), onDismissRequest = { focused = false }) {
                Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 8.dp, shadowElevation = 12.dp, modifier = Modifier.widthIn(min = 420.dp, max = 640.dp)) {
                    LazyColumn(Modifier.heightIn(max = 520.dp).padding(vertical = 8.dp)) {
                        if (showHistory) {
                            items(lib.searchHistory.take(10)) { q ->
                                SuggestionRow(q, Icons.Default.History, onRemove = { Library.removeSearch(q) }) { submit(q) }
                            }
                        } else if (s != null) {
                            items(s.queries) { q -> SuggestionRow(q, Icons.Default.Search) { submit(q) } }
                            items(s.recommendedItems, key = { it.id }) { item ->
                                RecommendedRow(item) {
                                    focused = false
                                    Actions.open(nav, item)
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
private fun SuggestionRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp)) }
    }
}

@Composable
private fun RecommendedRow(item: YTItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(item.thumbnail, 40.dp, if (item is com.metrolist.innertube.models.ArtistItem) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(4.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitleOf(item), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SearchScreen(screen: Screen.Search) {
    val nav = LocalNavigator.current
    var filterIndex by remember(screen.query) { mutableStateOf(0) }
    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            items(filters.size) { i ->
                FilterChip(selected = filterIndex == i, onClick = { filterIndex = i }, label = { Text(filters[i].first) })
            }
        }
        val filter = filters[filterIndex].second
        if (filter == null) {
            val loader = rememberLoad("searchSummary:${screen.query}") { YouTube.searchSummary(screen.query) }
            LoadView(loader) { page ->
                if (page.summaries.isEmpty()) {
                    Text("No results for \"${screen.query}\"", Modifier.padding(32.dp), style = MaterialTheme.typography.titleMedium)
                }
                LazyColumn(contentPadding = screenPadding) {
                    page.summaries.forEach { summary ->
                        item { SectionHeader(summary.title) }
                        if (summary === page.summaries.first() && summary.items.isNotEmpty()) {
                            item { TopResult(summary.items.first()) }
                            items(summary.items.drop(1), key = { "${summary.title}-${it.id}" }) { ItemRow(it, nav) }
                        } else {
                            items(summary.items, key = { "${summary.title}-${it.id}" }) { ItemRow(it, nav) }
                        }
                    }
                }
            }
        } else {
            val loader = rememberLoad("search:${screen.query}:$filterIndex") { YouTube.search(screen.query, filter) }
            LoadView(loader) { first ->
                val items = remember(first) { mutableStateListOf<YTItem>().apply { addAll(first.items) } }
                var continuation by remember(first) { mutableStateOf(first.continuation) }
                val listState = rememberLazyListState()
                LaunchedEffect(listState, first) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .distinctUntilChanged()
                        .filter { it >= listState.layoutInfo.totalItemsCount - 5 }
                        .collect {
                            val c = continuation ?: return@collect
                            continuation = null
                            YouTube.searchContinuation(c).onSuccess { more ->
                                val known = items.map { i -> i.id }.toHashSet()
                                items.addAll(more.items.filterNot { i -> i.id in known })
                                continuation = more.continuation
                            }
                        }
                }
                LazyColumn(state = listState, contentPadding = screenPadding) {
                    if (items.isEmpty()) item { Text("No results", Modifier.padding(24.dp)) }
                    items(items.size) { i -> ItemRow(items[i], nav) }
                }
            }
        }
    }
}

@Composable
private fun TopResult(item: YTItem) {
    val nav = LocalNavigator.current
    Row(
        Modifier.fillMaxWidth().padding(4.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { Actions.open(nav, item) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(item.thumbnail, 112.dp, if (item is com.metrolist.innertube.models.ArtistItem) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(8.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitleOf(item), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayShuffleButtons({ Actions.play(item) }, { Actions.play(item, shuffle = true) })
            }
        }
    }
}
