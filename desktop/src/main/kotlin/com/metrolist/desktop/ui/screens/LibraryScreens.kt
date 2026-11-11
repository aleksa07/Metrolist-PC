package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.data.Library
import com.metrolist.desktop.data.SavedItem
import com.metrolist.desktop.data.SavedKind
import com.metrolist.desktop.data.Settings
import com.metrolist.desktop.playback.Player
import com.metrolist.desktop.ui.Artwork
import com.metrolist.desktop.ui.ItemCard
import com.metrolist.desktop.ui.LoadView
import com.metrolist.desktop.ui.LocalNavigator
import com.metrolist.desktop.ui.MenuEntry
import com.metrolist.desktop.ui.Screen
import com.metrolist.desktop.ui.TrackRow
import com.metrolist.desktop.ui.WithContextMenu
import com.metrolist.desktop.ui.rememberLoad
import com.metrolist.desktop.ui.trackMenu
import java.text.DateFormat
import java.util.Date

private val tabs = listOf("Songs", "Playlists", "Albums", "Artists", "History", "YouTube Music")

@Composable
fun LibraryScreen() {
    val nav = LocalNavigator.current
    val lib by Library.state.collectAsState()
    val settings by Settings.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    val visibleTabs = if (settings.cookie.isBlank()) tabs.dropLast(1) else tabs
    Column {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Library", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 24.dp)) {
            itemsIndexed(visibleTabs) { i, t -> FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(t) }) }
        }
        when (visibleTabs.getOrNull(tab)) {
            "Songs" -> {
                val liked = lib.liked
                LazyColumn(contentPadding = screenPadding) {
                    item {
                        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Liked songs • ${liked.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (liked.isNotEmpty()) PlayShuffleButtons({ Player.playQueue(liked, 0, "Liked songs") }, { Player.playQueue(liked, 0, "Liked songs", shuffle = true) })
                        }
                    }
                    if (liked.isEmpty()) item { Empty("Songs you like appear here. Click the heart on any song.") }
                    itemsIndexed(liked, key = { _, t -> t.id }) { i, t ->
                        TrackRow(t, onClick = { Player.playQueue(liked, i, "Liked songs") }, menu = { trackMenu(nav, t) })
                    }
                }
            }
            "Playlists" -> {
                val saved = lib.saved.filter { it.kind == SavedKind.PLAYLIST }
                LazyVerticalGrid(GridCells.Adaptive(180.dp), contentPadding = screenPadding) {
                    item {
                        Tile(null, "New playlist", "Create", RoundedCornerShape(8.dp), icon = true) { creating = true }
                    }
                    item {
                        Tile(lib.liked.firstOrNull()?.thumbnail, "Liked songs", "${lib.liked.size} songs", RoundedCornerShape(8.dp)) { tab = 0 }
                    }
                    items(lib.playlists, key = { it.id }) { pl ->
                        WithContextMenu({ listOf(MenuEntry("Play", Icons.Default.Favorite) { Player.playQueue(pl.tracks, 0, pl.name) }) }) {
                            Tile(pl.tracks.firstOrNull()?.thumbnail, pl.name, "${pl.tracks.size} songs", RoundedCornerShape(8.dp)) { nav.push(Screen.LocalPlaylist(pl.id)) }
                        }
                    }
                    items(saved, key = { it.id }) { s -> SavedTile(s) }
                }
            }
            "Albums" -> SavedGrid(lib.saved.filter { it.kind == SavedKind.ALBUM }, "Albums you save appear here.")
            "Artists" -> SavedGrid(lib.saved.filter { it.kind == SavedKind.ARTIST }, "Artists you save appear here.")
            "History" -> {
                val history = lib.history
                LazyColumn(contentPadding = screenPadding) {
                    item {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.History, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Recently played", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (history.isNotEmpty()) OutlinedButton(onClick = Library::clearHistory) { Text("Clear history") }
                        }
                    }
                    if (history.isEmpty()) item { Empty("Nothing played yet.") }
                    itemsIndexed(history, key = { _, h -> h.track.id + h.playedAt }) { i, h ->
                        Column {
                            if (i == 0 || dayOf(history[i - 1].playedAt) != dayOf(h.playedAt)) {
                                Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(h.playedAt)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp))
                            }
                            TrackRow(h.track, onClick = { Player.playSong(h.track) }, menu = { trackMenu(nav, h.track) })
                        }
                    }
                }
            }
            "YouTube Music" -> YouTubeLibrary()
        }
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New playlist") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val id = Library.createPlaylist(name)
                    creating = false
                    nav.push(Screen.LocalPlaylist(id))
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}

private fun dayOf(ms: Long) = ms / 86_400_000L

@Composable
private fun YouTubeLibrary() {
    val nav = LocalNavigator.current
    val loader = rememberLoad("ytlibrary") { com.metrolist.innertube.YouTube.library("FEmusic_liked_playlists") }
    LoadView(loader) { page ->
        LazyVerticalGrid(GridCells.Adaptive(180.dp), contentPadding = screenPadding) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Playlists from your YouTube Music account", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
            }
            items(page.items, key = { it.id }) { ItemCard(it, nav) }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SavedGrid(items: List<SavedItem>, empty: String) {
    if (items.isEmpty()) {
        Empty(empty)
        return
    }
    LazyVerticalGrid(GridCells.Adaptive(180.dp), contentPadding = screenPadding) {
        items(items, key = { it.id }) { SavedTile(it) }
    }
}

@Composable
private fun SavedTile(item: SavedItem) {
    val nav = LocalNavigator.current
    WithContextMenu({ listOf(MenuEntry("Remove from library", Icons.Default.Favorite) { Library.toggleSaved(item) }) }) {
        Tile(
            item.thumbnail,
            item.title,
            listOfNotNull(item.kind.name.lowercase().replaceFirstChar { it.uppercase() }, item.subtitle).joinToString(" • "),
            if (item.kind == SavedKind.ARTIST) CircleShape else RoundedCornerShape(8.dp),
        ) {
            nav.push(
                when (item.kind) {
                    SavedKind.ALBUM -> Screen.Album(item.id)
                    SavedKind.ARTIST -> Screen.Artist(item.id)
                    SavedKind.PLAYLIST -> Screen.Playlist(item.id)
                },
            )
        }
    }
}

@Composable
private fun Tile(
    thumbnail: String?,
    title: String,
    subtitle: String,
    shape: androidx.compose.ui.graphics.Shape,
    icon: Boolean = false,
    onClick: () -> Unit,
) {
    Column(Modifier.padding(4.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(8.dp)) {
        if (icon) {
            Box(Modifier.size(160.dp).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        } else {
            Artwork(thumbnail, 160.dp, shape)
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(160.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(160.dp))
    }
}
