package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.data.Library
import com.metrolist.desktop.data.toTrack
import com.metrolist.desktop.playback.Player
import com.metrolist.desktop.ui.Carousel
import com.metrolist.desktop.ui.ItemCard
import com.metrolist.desktop.ui.ItemRow
import com.metrolist.desktop.ui.LoadView
import com.metrolist.desktop.ui.LocalNavigator
import com.metrolist.desktop.ui.Screen
import com.metrolist.desktop.ui.SectionHeader
import com.metrolist.desktop.ui.TrackRow
import com.metrolist.desktop.ui.rememberLoad
import com.metrolist.desktop.ui.trackMenu
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.HomePage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

private val contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp)

@Composable
fun HomeScreen() {
    val nav = LocalNavigator.current
    var chip by remember { mutableStateOf<HomePage.Chip?>(null) }
    val loader = rememberLoad("home:${chip?.title}") { YouTube.home(params = chip?.endpoint?.params) }
    LoadView(loader) { page ->
        val sections = remember(page) { mutableStateListOf<HomePage.Section>().apply { addAll(page.sections) } }
        var continuation by remember(page) { mutableStateOf(page.continuation) }
        var loadingMore by remember(page) { mutableStateOf(false) }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        LaunchedEffect(listState, page) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .distinctUntilChanged()
                .filter { it >= listState.layoutInfo.totalItemsCount - 2 }
                .collect {
                    val c = continuation ?: return@collect
                    if (loadingMore) return@collect
                    loadingMore = true
                    scope.launch {
                        YouTube.home(continuation = c).onSuccess { more ->
                            sections.addAll(more.sections)
                            continuation = more.continuation
                        }.onFailure { continuation = null }
                        loadingMore = false
                    }
                }
        }
        val history = Library.state.value.history
        LazyColumn(state = listState, contentPadding = contentPadding) {
            page.chips?.takeIf { it.isNotEmpty() }?.let { chips ->
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        items(chips) { c ->
                            FilterChip(selected = chip?.title == c.title, onClick = { chip = if (chip?.title == c.title) null else c }, label = { Text(c.title) })
                        }
                    }
                }
            }
            if (chip == null && history.isNotEmpty()) {
                item { SectionHeader("Listen again", "Recently played") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(history.take(20), key = { it.track.id }) { h ->
                            val t = h.track
                            ItemCard(
                                SongItem(id = t.id, title = t.title, artists = t.artists.map { com.metrolist.innertube.models.Artist(it.name, it.id) }, thumbnail = t.thumbnail.orEmpty(), duration = t.durationSec, explicit = t.explicit, album = t.album?.let { com.metrolist.innertube.models.Album(it.name, it.id) }),
                                nav,
                            )
                        }
                    }
                }
            }
            items(sections.size) { i ->
                val section = sections[i]
                SectionHeader(section.title, section.label, section.endpoint?.let { ep -> { nav.push(Screen.Browse(ep.browseId, ep.params, section.title)) } })
                val songs = section.items.filterIsInstance<SongItem>()
                if (songs.size == section.items.size && songs.size > 4) {
                    QuickPicks(songs)
                } else {
                    Carousel(section.items, nav)
                }
            }
        }
    }
}

@Composable
private fun QuickPicks(songs: List<SongItem>) {
    val nav = LocalNavigator.current
    LazyHorizontalGrid(GridCells.Fixed(4), modifier = Modifier.height(62.dp * 4)) {
        items(songs, key = { it.id }) { s ->
            val t = s.toTrack()
            Box(Modifier.width(400.dp)) {
                TrackRow(t, onClick = { Player.playSong(t, s.endpoint) }, menu = { trackMenu(nav, t) })
            }
        }
    }
}

@Composable
fun ExploreScreen() {
    val nav = LocalNavigator.current
    val loader = rememberLoad("explore") { YouTube.explore() }
    LoadView(loader) { page ->
        LazyColumn(contentPadding = contentPadding) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                    ExploreTile("Charts", MaterialTheme.colorScheme.primaryContainer) { nav.push(Screen.Charts) }
                    ExploreTile("New releases", MaterialTheme.colorScheme.tertiaryContainer) { nav.push(Screen.Browse("FEmusic_new_releases_albums", null, "New releases")) }
                    ExploreTile("Moods & genres", MaterialTheme.colorScheme.secondaryContainer) { nav.push(Screen.Browse("FEmusic_moods_and_genres", null, "Moods & genres")) }
                }
            }
            if (page.newReleaseAlbums.isNotEmpty()) {
                item { SectionHeader("New releases", onMore = { nav.push(Screen.Browse("FEmusic_new_releases_albums", null, "New releases")) }) }
                item { Carousel(page.newReleaseAlbums, nav) }
            }
            if (page.moodAndGenres.isNotEmpty()) {
                item { SectionHeader("Moods & genres") }
                item {
                    MoodGrid(page.moodAndGenres.map { Triple(it.title, it.stripeColor, it.endpoint) })
                }
            }
        }
    }
}

@Composable
private fun ExploreTile(title: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.width(220.dp).height(72.dp).clip(RoundedCornerShape(12.dp)).background(color).clickable(onClick = onClick).padding(16.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
}

@Composable
fun MoodGrid(moods: List<Triple<String, Long, com.metrolist.innertube.models.BrowseEndpoint>>) {
    val nav = LocalNavigator.current
    val rows = (moods.size + 4) / 5
    LazyVerticalGrid(
        GridCells.Adaptive(190.dp),
        modifier = Modifier.fillMaxWidth().height((rows * 64).dp.coerceAtMost(2000.dp)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        userScrollEnabled = false,
    ) {
        items(moods) { (title, stripe, endpoint) ->
            Row(
                Modifier.height(52.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { nav.push(Screen.Browse(endpoint.browseId, endpoint.params, title)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(6.dp).fillMaxSize().background(Color(stripe.toInt() or 0xFF000000.toInt())))
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun BrowseScreen(screen: Screen.Browse) {
    val nav = LocalNavigator.current
    if (screen.browseId == "FEmusic_moods_and_genres") {
        val loader = rememberLoad("moods") { YouTube.moodAndGenres() }
        LoadView(loader) { groups ->
            LazyColumn(contentPadding = contentPadding) {
                item { Text(screen.title ?: "Moods & genres", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp)) }
                groups.forEach { g ->
                    item { SectionHeader(g.title) }
                    item { MoodGrid(g.items.map { Triple(it.title, it.stripeColor, it.endpoint) }) }
                }
            }
        }
        return
    }
    if (screen.browseId == "FEmusic_new_releases_albums") {
        val loader = rememberLoad("newreleases") { YouTube.newReleaseAlbums() }
        LoadView(loader) { albums ->
            LazyVerticalGrid(GridCells.Adaptive(180.dp), contentPadding = contentPadding) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Text(screen.title ?: "New releases", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                }
                items(albums, key = { it.id }) { ItemCard(it, nav) }
            }
        }
        return
    }
    val loader = rememberLoad("browse:${screen.browseId}:${screen.params}") { YouTube.browse(screen.browseId, screen.params) }
    LoadView(loader) { result ->
        LazyColumn(contentPadding = contentPadding) {
            item { Text(result.title ?: screen.title ?: "", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp)) }
            result.items.forEach { section ->
                section.title?.let { item { SectionHeader(it) } }
                val songs = section.items.filterIsInstance<SongItem>()
                if (songs.size == section.items.size && songs.isNotEmpty()) {
                    items(songs, key = { "${section.title}-${it.id}" }) { ItemRow(it, nav) }
                } else {
                    item { Carousel(section.items, nav) }
                }
            }
        }
    }
}

@Composable
fun ChartsScreen() {
    val nav = LocalNavigator.current
    val loader = rememberLoad("charts") { YouTube.getChartsPage() }
    LoadView(loader) { page ->
        LazyColumn(contentPadding = contentPadding) {
            item { Text("Charts", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp)) }
            page.sections.forEach { section ->
                item { SectionHeader(section.title) }
                val songs = section.items.filterIsInstance<SongItem>()
                if (songs.size == section.items.size && songs.isNotEmpty()) {
                    item { QuickPicks(songs) }
                } else {
                    item { Carousel(section.items, nav) }
                }
            }
        }
    }
}
