package com.metrolist.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.desktop.data.Library
import com.metrolist.desktop.data.Track
import com.metrolist.desktop.data.toTrack
import com.metrolist.desktop.playback.Player
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** App-wide UI events that are not tied to a single screen. */
object Ui {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()

    var addToPlaylist by mutableStateOf<List<Track>?>(null)
    var expandedPlayer by mutableStateOf(false)

    fun toast(message: String) {
        messages.trySend(message)
    }

    fun copy(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
        toast("Link copied")
    }
}

object Actions {
    fun open(nav: Navigator, item: YTItem) {
        when (item) {
            is SongItem -> Player.playSong(item.toTrack(), item.endpoint)
            is EpisodeItem -> Player.playSong(item.toTrack())
            is AlbumItem -> nav.push(Screen.Album(item.browseId))
            is ArtistItem -> nav.push(Screen.Artist(item.id))
            is PlaylistItem -> nav.push(Screen.Playlist(item.id))
            is PodcastItem -> nav.push(Screen.Playlist(item.id))
        }
    }

    /** Resolves the songs behind any collection item, for play / shuffle / queue actions. */
    suspend fun tracksOf(item: YTItem): List<Track> =
        when (item) {
            is SongItem -> listOf(item.toTrack())
            is EpisodeItem -> listOf(item.toTrack())
            is AlbumItem -> YouTube.album(item.browseId).getOrNull()?.songs?.map { it.toTrack() }.orEmpty()
            is PlaylistItem -> YouTube.playlist(item.id).getOrNull()?.songs?.map { it.toTrack() }.orEmpty()
            is PodcastItem -> YouTube.playlist(item.id).getOrNull()?.songs?.map { it.toTrack() }.orEmpty()
            is ArtistItem -> emptyList()
        }

    fun play(item: YTItem, shuffle: Boolean = false) {
        when {
            item is ArtistItem -> {
                val ep = if (shuffle) item.shuffleEndpoint else item.radioEndpoint ?: item.shuffleEndpoint
                if (ep != null) Player.playEndpoint(ep, item.title) else Ui.toast("Nothing to play")
            }
            item is PlaylistItem && !shuffle && item.playEndpoint != null -> Player.playEndpoint(item.playEndpoint!!, item.title)
            item is PlaylistItem && shuffle && item.shuffleEndpoint != null -> Player.playEndpoint(item.shuffleEndpoint!!, item.title)
            item is SongItem || item is EpisodeItem -> open(Navigator(), item)
            else ->
                Ui.scope.launch {
                    val tracks = tracksOf(item)
                    if (tracks.isEmpty()) Ui.toast("Couldn't load ${item.title}") else Player.playQueue(tracks, 0, item.title, shuffle)
                }
        }
    }

    fun radio(item: YTItem) {
        when (item) {
            is SongItem -> Player.playSong(item.toTrack(), item.endpoint)
            is ArtistItem -> item.radioEndpoint?.let { Player.playEndpoint(it, "${item.title} radio") } ?: Ui.toast("No radio for this artist")
            is PlaylistItem -> item.radioEndpoint?.let { Player.playEndpoint(it, "${item.title} radio") } ?: play(item, shuffle = true)
            else -> play(item)
        }
    }

    fun enqueue(item: YTItem, next: Boolean) {
        Ui.scope.launch {
            val tracks = tracksOf(item)
            if (tracks.isEmpty()) return@launch Ui.toast("Nothing to add")
            if (next) Player.playNext(tracks) else Player.addToQueue(tracks)
            Ui.toast(if (next) "Will play next" else "Added to queue")
        }
    }

    fun addToPlaylist(item: YTItem) {
        Ui.scope.launch {
            val tracks = tracksOf(item)
            if (tracks.isNotEmpty()) Ui.addToPlaylist = tracks
        }
    }

    fun toggleLike(track: Track) {
        val liked = Library.isLiked(track.id)
        Library.toggleLike(track)
        com.metrolist.desktop.integrations.Integrations.onLike(track, !liked)
        com.metrolist.desktop.data.LikeSync.push(track, !liked)
        Ui.toast(if (liked) "Removed from liked songs" else "Added to liked songs")
    }
}
