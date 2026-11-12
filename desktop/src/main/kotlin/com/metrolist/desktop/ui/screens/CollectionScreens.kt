package com.metrolist.desktop.ui.screens

import kotlinx.coroutines.launch
import com.metrolist.desktop.data.Settings
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.data.Library
import com.metrolist.desktop.data.SavedItem
import com.metrolist.desktop.data.SavedKind
import com.metrolist.desktop.data.Track
import com.metrolist.desktop.data.formatDuration
import com.metrolist.desktop.data.toSaved
import com.metrolist.desktop.data.toTrack
import com.metrolist.desktop.playback.Player
import com.metrolist.desktop.ui.Artwork
import com.metrolist.desktop.ui.Carousel
import com.metrolist.desktop.ui.ItemCard
import com.metrolist.desktop.ui.LoadView
import com.metrolist.desktop.ui.LoadMoreOnScroll
import com.metrolist.desktop.ui.LocalNavigator
import com.metrolist.desktop.ui.MenuEntry
import com.metrolist.desktop.ui.MoreButton
import com.metrolist.desktop.ui.Screen
import com.metrolist.desktop.ui.SectionHeader
import com.metrolist.desktop.ui.TrackRow
import com.metrolist.desktop.ui.Ui
import com.metrolist.desktop.ui.itemMenu
import com.metrolist.desktop.ui.rememberLoad
import com.metrolist.desktop.ui.trackMenu
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem

val screenPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp)

@Composable
fun CollectionHeader(
    thumbnail: String?,
    kind: String,
    title: String,
    subtitle: String?,
    details: String?,
    shape: Shape = RoundedCornerShape(12.dp),
    actions: @Composable () -> Unit,
) {
    Row(Modifier.padding(vertical = 16.dp, horizontal = 8.dp), verticalAlignment = Alignment.Bottom) {
        Artwork(thumbnail, 220.dp, shape)
        Spacer(Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(kind.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            details?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}

@Composable
fun PlayShuffleButtons(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Button(onClick = onPlay) {
        Icon(Icons.Default.PlayArrow, null)
        Spacer(Modifier.width(6.dp))
        Text("Play")
    }
    FilledTonalButton(onClick = onShuffle) {
        Icon(Icons.Default.Shuffle, null)
        Spacer(Modifier.width(6.dp))
        Text("Shuffle")
    }
}

@Composable
fun SaveButton(saved: SavedItem?) {
    if (saved == null) return
    val lib by Library.state.collectAsState()
    val isSaved = lib.saved.any { it.id == saved.id }
    IconButton(onClick = { Library.toggleSaved(saved) }) {
        Icon(if (isSaved) Icons.Default.BookmarkAdded else Icons.Default.BookmarkAdd, if (isSaved) "Remove from library" else "Save to library", tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun totalDuration(tracks: List<Track>): String {
    val sec = tracks.sumOf { it.durationSec ?: 0 }.toLong()
    return "${tracks.size} songs" + if (sec > 0) " • ${formatDuration(sec)}" else ""
}

@Composable
fun AlbumScreen(screen: Screen.Album) {
    val nav = LocalNavigator.current
    val loader = rememberLoad("album:${screen.browseId}") { YouTube.album(screen.browseId) }
    LoadView(loader) { page ->
        val tracks = remember(page) { page.songs.map { it.toTrack().copy(thumbnail = it.thumbnail.ifBlank { page.album.thumbnail }) } }
        LazyColumn(contentPadding = screenPadding) {
            item {
                CollectionHeader(
                    page.album.thumbnail,
                    "Album",
                    page.album.title,
                    page.album.artists?.joinToString { it.name },
                    listOfNotNull(page.album.year?.toString(), totalDuration(tracks)).joinToString(" • "),
                ) {
                    PlayShuffleButtons({ Player.playQueue(tracks, 0, page.album.title) }, { Player.playQueue(tracks, 0, page.album.title, shuffle = true) })
                    SaveButton(page.album.toSaved())
                    MoreButton({ itemMenu(nav, page.album) })
                }
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                TrackRow(t, onClick = { Player.playQueue(tracks, i, page.album.title) }, menu = { trackMenu(nav, t) }, index = i, showArt = false)
            }
            if (page.otherVersions.isNotEmpty()) {
                item { SectionHeader("Other versions") }
                item { Carousel(page.otherVersions, nav) }
            }
        }
    }
}

@Composable
fun PlaylistScreen(screen: Screen.Playlist) {
    val nav = LocalNavigator.current
    val isPodcast = screen.playlistId.startsWith("MPSP")
    val loader =
        rememberLoad("playlist:${screen.playlistId}") {
            if (isPodcast) {
                YouTube.podcast(screen.playlistId).map { p ->
                    Triple(p.podcast.asPlaylistItem(), p.episodes.map { it.asSongItem() }, p.continuation)
                }
            } else {
                YouTube.playlist(screen.playlistId).map { p -> Triple(p.playlist, p.songs, p.songsContinuation ?: p.continuation) }
            }
        }
    LoadView(loader) { (playlist, firstSongs, firstContinuation) ->
        PlaylistContent(nav, playlist, firstSongs, firstContinuation, isPodcast)
    }
}

@Composable
private fun PlaylistContent(
    nav: com.metrolist.desktop.ui.Navigator,
    playlist: PlaylistItem,
    firstSongs: List<SongItem>,
    firstContinuation: String?,
    isPodcast: Boolean,
) {
    val songs = remember(playlist.id) { mutableStateListOf<Track>().apply { addAll(firstSongs.map { it.toTrack() }) } }
    // YouTube's per-entry ids, needed to remove a song from one of the account's own playlists.
    val setVideoIds = remember(playlist.id) { HashMap<String, String>().apply { firstSongs.forEach { s -> s.setVideoId?.let { put(s.id, it) } } } }
    var continuation by remember(playlist.id) { mutableStateOf(firstContinuation) }
    val listState = rememberLazyListState()
    LoadMoreOnScroll(listState, playlist.id) {
        val c = continuation?.takeUnless { isPodcast } ?: return@LoadMoreOnScroll
        continuation = null
        YouTube.playlistContinuation(c).onSuccess { more ->
            val known = songs.map { s -> s.id }.toHashSet()
            songs.addAll(more.songs.map { s -> s.toTrack() }.filterNot { s -> s.id in known })
            more.songs.forEach { s -> s.setVideoId?.let { setVideoIds[s.id] = it } }
            continuation = more.continuation
        }
    }
    LazyColumn(state = listState, contentPadding = screenPadding) {
        item {
            CollectionHeader(
                playlist.thumbnail,
                if (isPodcast) "Podcast" else "Playlist",
                playlist.title,
                playlist.author?.name,
                listOfNotNull(playlist.songCountText, playlist.description).joinToString(" • ").ifBlank { null },
            ) {
                PlayShuffleButtons({ Player.playQueue(songs.toList(), 0, playlist.title) }, { Player.playQueue(songs.toList(), 0, playlist.title, shuffle = true) })
                playlist.radioEndpoint?.let { ep ->
                    FilledTonalButton(onClick = { Player.playEndpoint(ep, "${playlist.title} radio") }) {
                        Icon(Icons.Default.Radio, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Radio")
                    }
                }
                SaveButton(playlist.toSaved())
                MoreButton({ itemMenu(nav, playlist) })
            }
        }
        itemsIndexed(songs, key = { i, t -> "$i-${t.id}" }) { i, t ->
            val remove =
                setVideoIds[t.id]?.takeIf { playlist.isEditable }?.let { setId ->
                    MenuEntry("Remove from this playlist", Icons.Default.Delete) {
                        Ui.scope.launch {
                            YouTube.removeFromPlaylist(playlist.id.removePrefix("VL"), t.id, setId)
                                .onSuccess {
                                    songs.remove(t)
                                    com.metrolist.desktop.ui.invalidateLoad("playlist:${playlist.id}")
                                    Ui.toast("Removed from ${playlist.title}")
                                }.onFailure { Ui.toast("Couldn't remove the song") }
                        }
                    }
                }
            TrackRow(t, onClick = { Player.playQueue(songs.toList(), i, playlist.title) }, menu = { trackMenu(nav, t, listOfNotNull(remove)) }, index = i)
        }
    }
}

@Composable
fun LocalPlaylistScreen(screen: Screen.LocalPlaylist) {
    val nav = LocalNavigator.current
    val lib by Library.state.collectAsState()
    val playlist = lib.playlists.firstOrNull { it.id == screen.id }
    if (playlist == null) {
        Text("Playlist not found", Modifier.padding(32.dp))
        return
    }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val tracks = playlist.tracks
    LazyColumn(contentPadding = screenPadding) {
        item {
            CollectionHeader(tracks.firstOrNull()?.thumbnail, "Playlist", playlist.name, "Local playlist", totalDuration(tracks)) {
                PlayShuffleButtons({ Player.playQueue(tracks, 0, playlist.name) }, { Player.playQueue(tracks, 0, playlist.name, shuffle = true) })
                MoreButton({
                    listOf(
                        MenuEntry("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) { Player.addToQueue(tracks) },
                        MenuEntry("Rename", Icons.Default.Edit) { renaming = true },
                        MenuEntry("Delete playlist", Icons.Default.Delete) { confirmDelete = true },
                    )
                })
            }
        }
        if (tracks.isEmpty()) {
            item { Text("This playlist is empty. Right-click any song and choose \"Add to playlist…\".", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            TrackRow(
                t,
                onClick = { Player.playQueue(tracks, i, playlist.name) },
                menu = {
                    trackMenu(
                        nav,
                        t,
                        listOf(
                            MenuEntry("Move up", Icons.Default.ArrowUpward) { Library.movePlaylistTrack(playlist.id, i, i - 1) },
                            MenuEntry("Move down", Icons.Default.ArrowDownward) { Library.movePlaylistTrack(playlist.id, i, i + 1) },
                            MenuEntry("Remove from playlist", Icons.Default.RemoveCircleOutline) { Library.removeFromPlaylist(playlist.id, t.id) },
                        ),
                    )
                },
                index = i,
            )
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(playlist.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename playlist") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { Library.renamePlaylist(playlist.id, name.trim().ifBlank { playlist.name }); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${playlist.name}\"?") },
            text = { Text("This removes the playlist from your library. Songs stay available on YouTube Music.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    nav.pop()
                    Library.deletePlaylist(playlist.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ArtistScreen(screen: Screen.Artist) {
    val nav = LocalNavigator.current
    val loader = rememberLoad("artist:${screen.browseId}") { YouTube.artist(screen.browseId) }
    LoadView(loader) { page ->
        LazyColumn(contentPadding = screenPadding) {
            item {
                CollectionHeader(
                    page.artist.thumbnail,
                    "Artist",
                    page.artist.title,
                    listOfNotNull(page.monthlyListenerCount, page.subscriberCountText).joinToString(" • ").ifBlank { null },
                    page.description,
                    CircleShape,
                ) {
                    page.artist.shuffleEndpoint?.let { ep ->
                        Button(onClick = { Player.playEndpoint(ep, page.artist.title) }) {
                            Icon(Icons.Default.Shuffle, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Shuffle")
                        }
                    }
                    page.artist.radioEndpoint?.let { ep ->
                        FilledTonalButton(onClick = { Player.playEndpoint(ep, "${page.artist.title} radio") }) {
                            Icon(Icons.Default.Radio, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Radio")
                        }
                    }
                    SaveButton(SavedItem(SavedKind.ARTIST, screen.browseId, page.artist.title, null, page.artist.thumbnail))
                    MoreButton({ itemMenu(nav, page.artist) })
                }
            }
            page.sections.forEach { section ->
                item { SectionHeader(section.title, onMore = section.moreEndpoint?.let { ep -> { nav.push(Screen.ArtistItems(ep, section.title)) } }) }
                val songs = section.items.filterIsInstance<SongItem>()
                if (songs.size == section.items.size && songs.isNotEmpty()) {
                    val tracks = songs.map { it.toTrack() }
                    itemsIndexed(tracks, key = { _, t -> "${section.title}-${t.id}" }) { i, t ->
                        TrackRow(t, onClick = { Player.playQueue(tracks, i, section.title) }, menu = { trackMenu(nav, t) })
                    }
                } else {
                    item { Carousel(section.items, nav) }
                }
            }
        }
    }
}

@Composable
fun ArtistItemsScreen(screen: Screen.ArtistItems) {
    val nav = LocalNavigator.current
    val loader = rememberLoad("artistItems:${screen.endpoint.browseId}:${screen.endpoint.params}") { YouTube.artistItems(screen.endpoint) }
    LoadView(loader) { page ->
        val items = remember(page) { mutableStateListOf<com.metrolist.innertube.models.YTItem>().apply { addAll(page.items) } }
        var continuation by remember(page) { mutableStateOf(page.continuation) }
        val songs = items.filterIsInstance<SongItem>()
        if (songs.size == items.size) {
            val listState = rememberLazyListState()
            LoadMoreOnScroll(listState, page) {
                val c = continuation ?: return@LoadMoreOnScroll
                continuation = null
                YouTube.artistItemsContinuation(c).onSuccess { more ->
                    items.addAll(more.items)
                    continuation = more.continuation
                }
            }
            val tracks = songs.map { it.toTrack() }
            LazyColumn(state = listState, contentPadding = screenPadding) {
                item { Text(page.title.ifBlank { screen.title }, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp)) }
                item {
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlayShuffleButtons({ Player.playQueue(tracks, 0, screen.title) }, { Player.playQueue(tracks, 0, screen.title, shuffle = true) })
                    }
                }
                itemsIndexed(tracks, key = { i, t -> "$i-${t.id}" }) { i, t ->
                    TrackRow(t, onClick = { Player.playQueue(tracks, i, screen.title) }, menu = { trackMenu(nav, t) }, index = i)
                }
            }
        } else {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Adaptive(180.dp), contentPadding = screenPadding) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Text(page.title.ifBlank { screen.title }, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                }
                items(items.size) { i -> ItemCard(items[i], nav) }
            }
        }
    }
}

@Composable
fun AddToPlaylistDialog() {
    val tracks = Ui.addToPlaylist ?: return
    val lib by Library.state.collectAsState()
    var newName by remember { mutableStateOf("") }
    val signedIn = Settings.value.cookie.isNotBlank()
    val accountPlaylists by produceState(emptyList<PlaylistItem>()) {
        if (signedIn) value = YouTube.library("FEmusic_liked_playlists").getOrNull()?.items?.filterIsInstance<PlaylistItem>()?.filter { it.isEditable }.orEmpty()
    }
    fun addToAccount(playlistId: String, name: String) {
        Ui.addToPlaylist = null
        Ui.scope.launch {
            val failed = tracks.count { YouTube.addToPlaylist(playlistId.removePrefix("VL"), it.id).isFailure }
            com.metrolist.desktop.ui.invalidateLoad("playlist:$playlistId")
            com.metrolist.desktop.ui.invalidateLoad("ytlibrary")
            Ui.toast(if (failed == 0) "Added to $name on YouTube Music" else "Couldn't add $failed of ${tracks.size} songs to $name")
        }
    }
    AlertDialog(
        onDismissRequest = { Ui.addToPlaylist = null },
        title = { Text(if (tracks.size == 1) "Add \"${tracks.first().title}\" to playlist" else "Add ${tracks.size} songs to playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lib.playlists.forEach { pl ->
                    TextButton(onClick = {
                        Library.addToPlaylist(pl.id, tracks)
                        Ui.addToPlaylist = null
                        Ui.toast("Added to ${pl.name}")
                    }) { Text("${pl.name}  (${pl.tracks.size})") }
                }
                accountPlaylists.forEach { pl ->
                    TextButton(onClick = { addToAccount(pl.id, pl.title) }) { Text("${pl.title}  (YouTube Music)") }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it }, label = { Text("New playlist") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = newName.isNotBlank(), onClick = {
                        Library.createPlaylist(newName, tracks)
                        Ui.addToPlaylist = null
                        Ui.toast("Created ${newName.trim()}")
                    }) { Text("Create") }
                }
                if (signedIn) {
                    TextButton(enabled = newName.isNotBlank(), onClick = {
                        val name = newName.trim()
                        Ui.scope.launch {
                            runCatching { YouTube.createPlaylist(name) }
                                .onSuccess { id -> addToAccount(id, name) }
                                .onFailure { Ui.toast("Couldn't create the playlist on YouTube Music") }
                        }
                    }) { Text("Create on YouTube Music instead") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { Ui.addToPlaylist = null }) { Text("Cancel") } },
    )
}

@Composable
fun SpotifyPlaylistScreen(screen: Screen.SpotifyPlaylist) {
    val nav = LocalNavigator.current
    val loader = rememberLoad("spotify:${screen.id}") { com.metrolist.desktop.integrations.Spotify.tracks(screen.id) }
    LoadView(loader) { tracks ->
        LazyColumn(contentPadding = screenPadding) {
            item {
                CollectionHeader(tracks.firstOrNull()?.thumbnail, "Spotify playlist", screen.name, "Plays from YouTube Music", totalDuration(tracks)) {
                    PlayShuffleButtons({ Player.playQueue(tracks, 0, screen.name) }, { Player.playQueue(tracks, 0, screen.name, shuffle = true) })
                    MoreButton({
                        listOf(
                            MenuEntry("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) { Player.addToQueue(tracks) },
                            MenuEntry("Save as local playlist", Icons.Default.BookmarkAdd) {
                                nav.push(Screen.LocalPlaylist(Library.createPlaylist(screen.name, tracks)))
                            },
                        )
                    })
                }
            }
            if (tracks.isEmpty()) item { Text("This playlist is empty.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
                TrackRow(t, onClick = { Player.playQueue(tracks, i, screen.name) }, menu = { trackMenu(nav, t) })
            }
        }
    }
}
