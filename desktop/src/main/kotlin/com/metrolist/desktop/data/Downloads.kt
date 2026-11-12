package com.metrolist.desktop.data

import com.metrolist.desktop.playback.RangeProxy
import com.metrolist.desktop.playback.StreamResolver
import com.metrolist.desktop.ui.Ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Songs saved to disk for offline playback; the player uses the file instead of streaming when it exists. */
object Downloads {
    private val dir = AppDirs.file("downloads").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val http = HttpClient.newHttpClient()
    private val _active = MutableStateFlow<Set<String>>(emptySet())

    /** Ids being downloaded right now. */
    val active: StateFlow<Set<String>> = _active.asStateFlow()

    // Ids also arrive from other people (Listen Together), so only plain YouTube ids may become file names.
    fun file(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "not a video id" }
        return File(dir, id)
    }

    fun entry(id: String): DownloadedTrack? =
        Library.value.downloads.firstOrNull { it.track.id == id }?.takeIf { runCatching { file(id).isFile }.getOrDefault(false) }

    fun sizeBytes(): Long = Library.value.downloads.sumOf { runCatching { file(it.track.id).length() }.getOrDefault(0) }

    /** Starts downloading what isn't on disk yet; returns how many downloads were started. */
    fun download(tracks: List<Track>): Int {
        val todo = tracks.distinctBy { it.id }.filter { entry(it.id) == null && it.id !in _active.value }
        _active.update { it + todo.map(Track::id) }
        todo.forEach { t ->
            scope.launch {
                runCatching { fetch(t) }.onFailure {
                    Timber.tag("Downloads").w(it, "download failed for ${t.id}")
                    Ui.toast("Couldn't download \"${t.title}\"")
                }
                _active.update { it - t.id }
            }
        }
        return todo.size
    }

    private suspend fun fetch(t: Track) {
        val stream = StreamResolver.resolve(t.id, Settings.value.audioQuality).getOrThrow()
        // The loopback proxy already does the ranged, throttling-safe reads the player uses.
        val (url, entry) = RangeProxy.register(stream)
        val tmp = File(dir, "${file(t.id).name}.part")
        try {
            val resp = http.send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
            check(resp.statusCode() == 200 && tmp.length() > 0 && (entry.length == null || tmp.length() == entry.length)) { "incomplete download" }
            Files.move(tmp.toPath(), file(t.id).toPath(), StandardCopyOption.REPLACE_EXISTING)
            Library.update { it.copy(downloads = it.downloads.filterNot { d -> d.track.id == t.id } + DownloadedTrack(t, stream.loudnessDb)) }
        } finally {
            tmp.delete()
            RangeProxy.unregister(url)
        }
    }

    fun remove(id: String) {
        runCatching { file(id).delete() }
        Library.update { it.copy(downloads = it.downloads.filterNot { d -> d.track.id == id }) }
    }
}

/** Likes, playlists, history and settings in one file, to move to another PC. Sign-ins are never included. */
object Backup {
    @Serializable
    private data class Contents(val library: LibraryData, val settings: AppSettings)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun export(file: File) {
        val lib = Library.value.copy(downloads = emptyList(), likedSnapshot = emptyList(), pendingLikes = emptyMap())
        file.writeText(json.encodeToString(Contents.serializer(), Contents(lib, withAccounts(Settings.value, AppSettings()))))
    }

    /** Replaces the library and settings with the file's; this PC keeps its own sign-ins and downloads. */
    fun import(file: File): Result<Unit> =
        runCatching {
            val backup = json.decodeFromString(Contents.serializer(), file.readText())
            Library.update { cur -> backup.library.copy(downloads = cur.downloads, likedSnapshot = cur.likedSnapshot, pendingLikes = cur.pendingLikes) }
            Settings.update { cur -> withAccounts(backup.settings, cur) }
        }

    /** [settings] with every sign-in and device-specific value taken from [from]. */
    private fun withAccounts(settings: AppSettings, from: AppSettings) =
        settings.copy(
            cookie = from.cookie,
            lastfmApiKey = from.lastfmApiKey,
            lastfmSecret = from.lastfmSecret,
            lastfmSession = from.lastfmSession,
            lastfmUser = from.lastfmUser,
            spotifyCookie = from.spotifyCookie,
            autoInstallTried = from.autoInstallTried,
            outputDevice = from.outputDevice,
        )
}
