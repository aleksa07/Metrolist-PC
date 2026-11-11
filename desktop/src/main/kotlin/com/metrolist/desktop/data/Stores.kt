package com.metrolist.desktop.data

import com.metrolist.desktop.playback.AudioQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.UUID

private val storeJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

/** Small JSON-file-backed state holder with debounced, atomic writes. */
@OptIn(FlowPreview::class)
open class JsonStore<T>(
    fileName: String,
    private val serializer: KSerializer<T>,
    default: () -> T,
) {
    private val file: File = AppDirs.file(fileName)
    private val _state = MutableStateFlow(load() ?: default())
    val state: StateFlow<T> = _state.asStateFlow()
    val value: T get() = _state.value

    init {
        _state.drop(1).debounce(400).onEach(::write).launchIn(scope)
    }

    fun update(transform: (T) -> T) = _state.update(transform)

    fun flush() = write(_state.value)

    private fun load(): T? =
        runCatching { if (file.exists()) storeJson.decodeFromString(serializer, file.readText()) else null }
            .onFailure { Timber.tag("Store").w(it, "failed to read ${file.name}") }
            .getOrNull()

    @Synchronized
    private fun write(value: T) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(storeJson.encodeToString(serializer, value))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Timber.tag("Store").w(it, "failed to write ${file.name}") }
    }

    companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

enum class ThemeMode { SYSTEM, DARK, LIGHT }

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = true,
    val pureBlack: Boolean = false,
    val audioQuality: AudioQuality = AudioQuality.AUTO,
    val normalizeVolume: Boolean = true,
    val volume: Float = 0.8f,
    val autoRadio: Boolean = true,
    val cookie: String = "",
    val contentLanguage: String = "en",
    val contentCountry: String = "US",
    val recordHistory: Boolean = true,
    val showQueuePanel: Boolean = false,
    val showVisualizer: Boolean = true,
    // Last.fm (same options and defaults as the Android app)
    val lastfmApiKey: String = System.getenv("LASTFM_API_KEY").orEmpty(),
    val lastfmSecret: String = System.getenv("LASTFM_SECRET").orEmpty(),
    val lastfmSession: String = "",
    val lastfmUser: String = "",
    val scrobbleEnabled: Boolean = true,
    val lastfmNowPlaying: Boolean = true,
    val lastfmSendLikes: Boolean = false,
    val scrobblePercent: Float = 0.5f,
    val scrobbleMinutes: Int = 4,
    val scrobbleMinTrackSec: Int = 30,
    // Discord Rich Presence
    val discordEnabled: Boolean = false,
    val discordActivityType: Int = 2,
    val discordDetails: String = "{song.name}",
    val discordState: String = "{artist.name}",
    val discordListenButton: Boolean = true,
    val discordProjectButton: Boolean = true,
    val discordShowPaused: Boolean = false,
)

object Settings : JsonStore<AppSettings>("settings.json", AppSettings.serializer(), { AppSettings() })

@Serializable
data class LibraryData(
    val liked: List<Track> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val saved: List<SavedItem> = emptyList(),
    val playlists: List<LocalPlaylist> = emptyList(),
    val searchHistory: List<String> = emptyList(),
)

object Library : JsonStore<LibraryData>("library.json", LibraryData.serializer(), { LibraryData() }) {
    fun isLiked(id: String) = value.liked.any { it.id == id }

    fun toggleLike(track: Track) =
        update { lib ->
            if (lib.liked.any { it.id == track.id }) {
                lib.copy(liked = lib.liked.filterNot { it.id == track.id })
            } else {
                lib.copy(liked = listOf(track) + lib.liked)
            }
        }

    fun addHistory(track: Track) =
        update { lib ->
            val entries = listOf(HistoryEntry(track, System.currentTimeMillis())) + lib.history.filterNot { it.track.id == track.id }
            lib.copy(history = entries.take(500))
        }

    fun clearHistory() = update { it.copy(history = emptyList()) }

    fun isSaved(id: String) = value.saved.any { it.id == id }

    fun toggleSaved(item: SavedItem) =
        update { lib ->
            if (lib.saved.any { it.id == item.id }) {
                lib.copy(saved = lib.saved.filterNot { it.id == item.id })
            } else {
                lib.copy(saved = listOf(item) + lib.saved)
            }
        }

    fun createPlaylist(name: String, tracks: List<Track> = emptyList()): String {
        val id = "LOCAL_" + UUID.randomUUID().toString().take(12)
        update { it.copy(playlists = it.playlists + LocalPlaylist(id, name.trim().ifBlank { "New playlist" }, tracks.distinctBy(Track::id))) }
        return id
    }

    fun addToPlaylist(playlistId: String, tracks: List<Track>) =
        updatePlaylist(playlistId) { pl -> pl.copy(tracks = (pl.tracks + tracks).distinctBy(Track::id)) }

    fun removeFromPlaylist(playlistId: String, trackId: String) =
        updatePlaylist(playlistId) { pl -> pl.copy(tracks = pl.tracks.filterNot { it.id == trackId }) }

    fun renamePlaylist(playlistId: String, name: String) = updatePlaylist(playlistId) { it.copy(name = name) }

    fun movePlaylistTrack(playlistId: String, from: Int, to: Int) =
        updatePlaylist(playlistId) { pl ->
            val list = pl.tracks.toMutableList()
            if (from !in list.indices || to !in list.indices) return@updatePlaylist pl
            list.add(to, list.removeAt(from))
            pl.copy(tracks = list)
        }

    fun deletePlaylist(playlistId: String) = update { it.copy(playlists = it.playlists.filterNot { p -> p.id == playlistId }) }

    private fun updatePlaylist(id: String, transform: (LocalPlaylist) -> LocalPlaylist) =
        update { lib -> lib.copy(playlists = lib.playlists.map { if (it.id == id) transform(it) else it }) }

    fun addSearch(query: String) =
        update { lib -> lib.copy(searchHistory = (listOf(query) + lib.searchHistory.filterNot { it.equals(query, true) }).take(30)) }

    fun removeSearch(query: String) = update { lib -> lib.copy(searchHistory = lib.searchHistory - query) }
}

@Serializable
data class QueueSnapshot(
    val tracks: List<Track> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val repeat: Int = 0,
)

object QueueStore : JsonStore<QueueSnapshot>("queue.json", QueueSnapshot.serializer(), { QueueSnapshot() })
