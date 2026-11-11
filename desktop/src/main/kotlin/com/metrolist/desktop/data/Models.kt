package com.metrolist.desktop.data

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import kotlinx.serialization.Serializable

@Serializable
data class ArtistRef(val name: String, val id: String? = null)

@Serializable
data class AlbumRef(val name: String, val id: String)

/** Playable song as stored in the local library and queue (the desktop equivalent of SongEntity). */
@Serializable
data class Track(
    val id: String,
    val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val album: AlbumRef? = null,
    val durationSec: Int? = null,
    val thumbnail: String? = null,
    val explicit: Boolean = false,
    val isVideo: Boolean = false,
) {
    val artistText: String get() = artists.joinToString(", ") { it.name }
    val shareLink: String get() = "https://music.youtube.com/watch?v=$id"
}

fun SongItem.toTrack() =
    Track(
        id = id,
        title = title,
        artists = artists.map { ArtistRef(it.name, it.id) },
        album = album?.let { AlbumRef(it.name, it.id) },
        durationSec = duration,
        thumbnail = thumbnail,
        explicit = explicit,
        isVideo = isVideoSong,
    )

fun EpisodeItem.toTrack() = asSongItem().toTrack()

enum class SavedKind { ALBUM, ARTIST, PLAYLIST }

/** A bookmarked album, artist or playlist. */
@Serializable
data class SavedItem(
    val kind: SavedKind,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val thumbnail: String? = null,
    val playlistId: String? = null,
    val savedAt: Long = System.currentTimeMillis(),
)

fun YTItem.toSaved(): SavedItem? =
    when (this) {
        is AlbumItem -> SavedItem(SavedKind.ALBUM, browseId, title, artists?.joinToString { it.name }, thumbnail, playlistId)
        is ArtistItem -> SavedItem(SavedKind.ARTIST, id, title, null, thumbnail)
        is PlaylistItem -> SavedItem(SavedKind.PLAYLIST, id, title, author?.name, thumbnail)
        is PodcastItem -> SavedItem(SavedKind.PLAYLIST, id, title, author?.name, thumbnail)
        else -> null
    }

@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val tracks: List<Track> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class HistoryEntry(val track: Track, val playedAt: Long)

/** Rewrites Google image URLs to the requested square size, like the Android app's thumbnail resize helper. */
fun String?.resized(size: Int): String? {
    if (this == null) return null
    val px = size.coerceAtLeast(60)
    return when {
        Regex("=w\\d+-h\\d+").containsMatchIn(this) -> replace(Regex("=w\\d+-h\\d+[^&?]*"), "=w$px-h$px-l90-rj")
        Regex("=s\\d+").containsMatchIn(this) -> replace(Regex("=s\\d+[^&?]*"), "=s$px")
        else -> this
    }
}

fun formatDuration(seconds: Long): String {
    if (seconds < 0) return "--:--"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
