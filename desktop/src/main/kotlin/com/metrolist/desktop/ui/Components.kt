package com.metrolist.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Explicit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import coil3.compose.AsyncImage
import com.metrolist.desktop.data.Library
import com.metrolist.desktop.data.Track
import com.metrolist.desktop.data.formatDuration
import com.metrolist.desktop.data.resized
import com.metrolist.desktop.data.toSaved
import com.metrolist.desktop.data.toTrack
import com.metrolist.desktop.playback.Player
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem

class MenuEntry(val label: String, val icon: ImageVector, val action: () -> Unit)

@Composable
fun Artwork(
    url: String?,
    size: Dp,
    shape: Shape = RoundedCornerShape(6.dp),
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url != null) {
            AsyncImage(
                model = url.resized((size.value * 2).toInt()),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WithContextMenu(entries: () -> List<MenuEntry>, content: @Composable () -> Unit) {
    ContextMenuArea(items = { entries().map { e -> ContextMenuItem(e.label, e.action) } }, content = content)
}

@Composable
fun MoreButton(entries: () -> List<MenuEntry>, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More", tint = tint) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries().forEach { e ->
                DropdownMenuItem(
                    text = { Text(e.label) },
                    leadingIcon = { Icon(e.icon, null) },
                    onClick = {
                        open = false
                        e.action()
                    },
                )
            }
        }
    }
}

fun trackMenu(
    nav: Navigator,
    track: Track,
    extra: List<MenuEntry> = emptyList(),
): List<MenuEntry> =
    buildList {
        add(MenuEntry("Play next", Icons.AutoMirrored.Filled.PlaylistPlay) { Player.playNext(listOf(track)); Ui.toast("Will play next") })
        add(MenuEntry("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) { Player.addToQueue(listOf(track)); Ui.toast("Added to queue") })
        add(MenuEntry("Start radio", Icons.Default.Radio) { Player.playSong(track) })
        val liked = Library.isLiked(track.id)
        add(MenuEntry(if (liked) "Remove from liked songs" else "Like", if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder) { Actions.toggleLike(track) })
        add(MenuEntry("Add to playlist…", Icons.AutoMirrored.Filled.PlaylistAdd) { Ui.addToPlaylist = listOf(track) })
        track.album?.let { a -> add(MenuEntry("Go to album", Icons.Default.Album) { nav.push(Screen.Album(a.id)) }) }
        track.artists.filter { it.id != null }.take(3).forEach { a ->
            add(MenuEntry("Go to ${a.name}", Icons.Default.Person) { nav.push(Screen.Artist(a.id!!)) })
        }
        add(MenuEntry("Copy link", Icons.Default.Link) { Ui.copy(track.shareLink) })
        addAll(extra)
    }

fun itemMenu(nav: Navigator, item: YTItem): List<MenuEntry> =
    when (item) {
        is SongItem -> trackMenu(nav, item.toTrack())
        is EpisodeItem -> trackMenu(nav, item.toTrack())
        else ->
            buildList {
                if (item !is ArtistItem) {
                    add(MenuEntry("Play", Icons.Default.PlayArrow) { Actions.play(item) })
                }
                add(MenuEntry("Shuffle", Icons.Default.Shuffle) { Actions.play(item, shuffle = true) })
                if (item is ArtistItem || item is PlaylistItem) add(MenuEntry("Start radio", Icons.Default.Radio) { Actions.radio(item) })
                if (item !is ArtistItem) {
                    add(MenuEntry("Play next", Icons.AutoMirrored.Filled.PlaylistPlay) { Actions.enqueue(item, next = true) })
                    add(MenuEntry("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) { Actions.enqueue(item, next = false) })
                    add(MenuEntry("Add to playlist…", Icons.AutoMirrored.Filled.PlaylistAdd) { Actions.addToPlaylist(item) })
                }
                item.toSaved()?.let { saved ->
                    val isSaved = Library.isSaved(saved.id)
                    add(
                        MenuEntry(if (isSaved) "Remove from library" else "Save to library", if (isSaved) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd) {
                            Library.toggleSaved(saved)
                            Ui.toast(if (isSaved) "Removed from library" else "Saved to library")
                        },
                    )
                }
                if (item is AlbumItem) {
                    item.artists?.filter { it.id != null }?.take(2)?.forEach { a ->
                        add(MenuEntry("Go to ${a.name}", Icons.Default.Person) { nav.push(Screen.Artist(a.id!!)) })
                    }
                }
                add(MenuEntry("Copy link", Icons.Default.Link) { Ui.copy(item.shareLink) })
            }
    }

fun subtitleOf(item: YTItem): String =
    when (item) {
        is SongItem -> listOfNotNull(item.artists.joinToString { it.name }.ifBlank { null }, item.album?.name).joinToString(" • ")
        is AlbumItem -> listOfNotNull("Album", item.artists?.joinToString { it.name }?.ifBlank { null }, item.year?.toString()).joinToString(" • ")
        is ArtistItem -> "Artist"
        is PlaylistItem -> listOfNotNull("Playlist", item.author?.name, item.songCountText).joinToString(" • ")
        is PodcastItem -> listOfNotNull("Podcast", item.author?.name).joinToString(" • ")
        is EpisodeItem -> listOfNotNull(item.author?.name, item.publishDateText).joinToString(" • ")
    }

@Composable
fun PlayingIndicator(playing: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
        Icon(if (playing) Icons.Default.GraphicEq else Icons.Default.PlayArrow, null, tint = Color.White)
    }
}

/** A song list row (the desktop version of the Android SongListItem). */
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    menu: () -> List<MenuEntry>,
    index: Int? = null,
    showArt: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val current by Player.current.collectAsState()
    val playing by Player.isPlaying.collectAsState()
    val lib by Library.state.collectAsState()
    val isCurrent = current?.id == track.id
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val liked = lib.liked.any { it.id == track.id }
    WithContextMenu(menu) {
        Row(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                .hoverable(interaction)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index != null) {
                Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                    if (isCurrent && !showArt) {
                        Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Text("${index + 1}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            if (showArt) {
                Box {
                    Artwork(track.thumbnail, 48.dp)
                    if (isCurrent || hovered) PlayingIndicator(isCurrent && playing)
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (track.explicit) {
                        Icon(Icons.Default.Explicit, "Explicit", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        listOfNotNull(track.artistText.ifBlank { null }, track.album?.name).joinToString(" • "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (hovered || liked) {
                IconButton(onClick = { Actions.toggleLike(track) }) {
                    Icon(
                        if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        "Like",
                        tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            track.durationSec?.let {
                Text(formatDuration(it.toLong()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MoreButton(menu)
        }
    }
}

@Composable
fun ItemRow(item: YTItem, nav: Navigator, index: Int? = null) {
    when (item) {
        is SongItem -> {
            val t = item.toTrack()
            TrackRow(t, onClick = { Player.playSong(t, item.endpoint) }, menu = { itemMenu(nav, item) }, index = index)
        }
        is EpisodeItem -> {
            val t = item.toTrack()
            TrackRow(t, onClick = { Player.playSong(t) }, menu = { itemMenu(nav, item) }, index = index)
        }
        else -> {
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            WithContextMenu({ itemMenu(nav, item) }) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                        .hoverable(interaction)
                        .clickable { Actions.open(nav, item) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(item.thumbnail, 56.dp, if (item is ArtistItem) CircleShape else RoundedCornerShape(6.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text(subtitleOf(item), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MoreButton({ itemMenu(nav, item) })
                }
            }
        }
    }
}

/** Grid/carousel card (the desktop version of the Android YouTubeGridItem). */
@Composable
fun ItemCard(item: YTItem, nav: Navigator, width: Dp = 176.dp) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val current by Player.current.collectAsState()
    val isArtist = item is ArtistItem
    WithContextMenu({ itemMenu(nav, item) }) {
        Column(
            Modifier
                .width(width)
                .clip(RoundedCornerShape(10.dp))
                .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                .hoverable(interaction)
                .clickable { Actions.open(nav, item) }
                .padding(8.dp),
        ) {
            Box {
                Artwork(item.thumbnail, width - 16.dp, if (isArtist) CircleShape else RoundedCornerShape(8.dp))
                if (hovered && !isArtist) {
                    FilledIconButton(
                        onClick = { Actions.play(item) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) { Icon(Icons.Default.PlayArrow, "Play") }
                }
                if ((item is SongItem || item is EpisodeItem) && current?.id == item.id) {
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.GraphicEq, null, tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                item.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                modifier = if (isArtist) Modifier.align(Alignment.CenterHorizontally) else Modifier,
            )
            Text(
                subtitleOf(item),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (isArtist) Modifier.align(Alignment.CenterHorizontally) else Modifier,
            )
        }
    }
}

@Composable
private fun Modifier.matchParentSize(): Modifier = this.size(160.dp)

@Composable
fun SectionHeader(title: String, subtitle: String? = null, onMore: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            subtitle?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        if (onMore != null) OutlinedButton(onClick = onMore) { Text("More") }
    }
}

@Composable
fun Carousel(items: List<YTItem>, nav: Navigator) {
    val state = rememberLazyListState()
    ScrollArrows(state) {
        LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(items, key = { it.id + it::class.simpleName }) { ItemCard(it, nav) }
        }
    }
}

/** Left/right buttons over a horizontal list, since a mouse wheel only scrolls the page vertically. */
@Composable
fun ScrollArrows(state: ScrollableState, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var width by remember { mutableStateOf(0) }
    Box(Modifier.onSizeChanged { width = it.width }) {
        content()
        for (forward in listOf(false, true)) {
            if (if (forward) state.canScrollForward else state.canScrollBackward) {
                FilledTonalIconButton(
                    onClick = { scope.launch { state.animateScrollBy(width * if (forward) 0.8f else -0.8f) } },
                    modifier = Modifier.align(if (forward) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 4.dp),
                ) {
                    Icon(
                        if (forward) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        if (forward) "Scroll right" else "Scroll left",
                    )
                }
            }
        }
    }
}

@Composable
fun CenteredLoading() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Something went wrong", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
fun <T> LoadView(loader: Loader<T>, content: @Composable (T) -> Unit) {
    when (val s = loader.state) {
        is Load.Loading -> CenteredLoading()
        is Load.Err -> ErrorView(s.message, loader.reload)
        is Load.Ok -> content(s.value)
    }
}

/** Runs [loadMore] each time the list is scrolled to within a few items of its end (one call at a time). */
@Composable
fun LoadMoreOnScroll(state: LazyListState, key: Any?, loadMore: suspend () -> Unit) {
    LaunchedEffect(state, key) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= state.layoutInfo.totalItemsCount - 5 }
            .collect { loadMore() }
    }
}
