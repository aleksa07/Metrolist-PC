package com.metrolist.desktop

import com.metrolist.desktop.playback.AudioEngine
import com.metrolist.desktop.playback.AudioQuality
import com.metrolist.desktop.playback.RangeProxy
import com.metrolist.desktop.playback.StreamResolver
import com.metrolist.desktop.data.toTrack
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.first

object SmokeTest {
    @JvmStatic
    fun main(args: Array<String>) = runSmokeTest(args.firstOrNull())
}

/** End-to-end check without UI: search, resolve, then decode ~6 s of audio at zero volume. */
fun runSmokeTest(queryArg: String?): Unit = runBlocking {
    val query = queryArg ?: "Daft Punk One More Time"
    com.metrolist.desktop.playback.Spectrum.selfCheck()
    println("spectrum self-check: ok")
    com.metrolist.desktop.playback.Equalizer.selfCheck()
    println("equaliser self-check: ok")
    with(com.metrolist.desktop.integrations.Updater) {
        check(isNewer("1.1.0", "1.0.0") && isNewer("1.0.10", "1.0.9") && !isNewer("1.0.0", "1.0.0") && !isNewer("1.0.0", "1.1.0")) { "version compare" }
    }
    println("update version self-check: ok")
    with(com.metrolist.desktop.data.LikeSync) {
        fun t(id: String) = com.metrolist.desktop.data.Track(id, id)
        val lib = com.metrolist.desktop.data.LibraryData(
            liked = listOf(t("local"), t("gone"), t("kept")),
            likedSnapshot = listOf("gone", "kept"),
            pendingLikes = mapOf("unliked" to false),
        )
        val remote = listOf(t("kept"), t("new"), t("unliked"))
        check(merge(lib, remote, complete = true).liked.map { it.id } == listOf("local", "kept", "new")) { "like merge" }
        check(merge(lib, remote, complete = false).liked.map { it.id } == listOf("local", "gone", "kept", "new")) { "partial like merge" }
    }
    println("like merge self-check: ok")
    java.io.File.createTempFile("metrolist-backup", ".json").let { f ->
        com.metrolist.desktop.data.Backup.export(f)
        val cookie = com.metrolist.desktop.data.Settings.value.cookie
        check(f.length() > 0 && (cookie.isBlank() || cookie !in f.readText())) { "backup must not contain the sign-in" }
        f.delete()
    }
    println("backup self-check: ok")
    com.metrolist.desktop.integrations.Spotify.selfCheck()
    println("spotify self-check: ok")
    com.metrolist.desktop.integrations.SoundCloud.selfCheck()
    println("soundcloud self-check: ok")
    // Informational: a public playlist big enough that most tracks come back as bare ids.
    com.metrolist.desktop.integrations.SoundCloud.tracks("545610837").fold(
        { println("soundcloud playlist: ${it.size} tracks, first: ${it.firstOrNull()?.let { t -> "${t.title} - ${t.artistText} (${t.durationSec}s)" }}") },
        { println("soundcloud playlist failed: ${it.message}") },
    )
    YouTube.visitorData = YouTube.visitorData().getOrNull()
    println("visitorData: ${if (YouTube.visitorData != null) "ok" else "missing"}")
    // Informational only: Spotify being down must not fail the YouTube checks. Anonymous token, public playlist.
    com.metrolist.desktop.integrations.Spotify.tracks("37i9dQZF1DXcBWIGoYBM5M", cookie = "").fold(
        { sp ->
            println("spotify playlist: ${sp.size} songs, first: ${sp.firstOrNull()?.let { "${it.title} - ${it.artistText} (${it.durationSec}s)" }}")
            sp.firstOrNull()?.let { com.metrolist.desktop.integrations.Spotify.match(it) }
                ?.onSuccess { println("spotify match: ${it.title} - ${it.artistText} (${it.id})") }
                ?.onFailure { println("spotify match failed: ${it.message}") }
        },
        { println("spotify playlist failed: ${it.message}") },
    )

    val home = YouTube.home().getOrNull()
    println("home sections: ${home?.sections?.size} ${home?.sections?.take(3)?.map { it.title }}")

    val results = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow()
    val song = results.items.filterIsInstance<SongItem>().first()
    println("song: ${song.title} - ${song.artists.joinToString { it.name }} (${song.id})")
    val lyrics = com.metrolist.desktop.data.LyricsRepository.get(song.toTrack())
    println("lyrics: source=${lyrics.source.ifBlank { "none" }} syncedLines=${lyrics.synced?.size ?: 0} plain=${lyrics.plain != null}")

    val t0 = System.currentTimeMillis()
    AudioEngine.preload()
    val stream = StreamResolver.resolve(song.id, AudioQuality.HIGH).getOrThrow()
    println("stream: client=${stream.client} mime=${stream.mimeType} bitrate=${stream.bitrate} len=${stream.contentLength} in ${System.currentTimeMillis() - t0} ms")

    val (url, entry) = RangeProxy.register(stream)
    val done = CountDownLatch(1)
    val ready = CountDownLatch(1)
    val advanced = CountDownLatch(1)
    var failure: Throwable? = null
    val t1 = System.currentTimeMillis()
    val engine =
        AudioEngine(
            object : AudioEngine.Listener {
                override fun onReady(durationMs: Long) {
                    println("ready: duration=${durationMs / 1000}s after ${System.currentTimeMillis() - t1} ms")
                    ready.countDown()
                }
                override fun onEnded() = done.countDown()
                override fun onAdvanced(key: String, durationMs: Long) = advanced.countDown()
                override fun onError(error: Throwable, httpStatus: Int?) {
                    failure = error
                    println("error: $error http=$httpStatus")
                    done.countDown()
                }
            },
        )
    engine.volume = 0f
    engine.load(url, entry, 0, playWhenReady = true)
    ready.await(60, java.util.concurrent.TimeUnit.SECONDS)
    Thread.sleep(4000)
    println("position after 4 s: ${engine.positionMs} ms")
    engine.seekTo(60_000)
    Thread.sleep(2500)
    println("position after seek to 60 s (+2.5 s): ${engine.positionMs} ms")
    val seekOk = engine.positionMs > 60_000
    // Gapless hand-over into a local file with the equaliser on: the path downloaded songs take.
    val local = java.io.File.createTempFile("metrolist-smoke", ".audio")
    java.net.URI(url).toURL().openStream().use { input -> local.outputStream().use { input.copyTo(it) } }
    engine.equalizer.setGains(listOf(3f, 0f, 0f, 0f, 3f))
    engine.prepareNext("local", local.absolutePath, null, 1f)
    engine.seekTo(engine.durationMs - 4000)
    val handedOver = advanced.await(25, java.util.concurrent.TimeUnit.SECONDS)
    Thread.sleep(2000)
    val after = engine.positionMs
    println("gapless into local file (${local.length()} bytes): advanced=$handedOver position=$after ms")
    engine.stop()
    local.delete()
    done.countDown()
    // A Spotify song in the queue is matched when it plays and swapped for the YouTube Music song.
    val placeholder = com.metrolist.desktop.data.Track("spotify:smoke", "One More Time", listOf(com.metrolist.desktop.data.ArtistRef("Daft Punk")), durationSec = 320)
    com.metrolist.desktop.playback.Player.setVolume(0f)
    com.metrolist.desktop.playback.Player.playQueue(listOf(placeholder))
    val swapped = kotlinx.coroutines.withTimeoutOrNull(90_000) {
        com.metrolist.desktop.playback.Player.readyTrackId.first { it != null && !it.startsWith("spotify:") }
    }
    println("spotify placeholder plays as: $swapped, queue=${com.metrolist.desktop.playback.Player.queue.value.map { it.id }}")
    // A SoundCloud song plays its own HLS stream.
    com.metrolist.desktop.playback.Player.playQueue(listOf(com.metrolist.desktop.data.Track("soundcloud:293", "Flickermood", durationSec = 213)))
    val scReady = kotlinx.coroutines.withTimeoutOrNull(60_000) { com.metrolist.desktop.playback.Player.readyTrackId.first { it == "soundcloud:293" } }
    Thread.sleep(3000)
    val scPos = com.metrolist.desktop.playback.Player.position.value
    println("soundcloud plays: ready=${scReady != null} position after 3 s=$scPos ms error=${com.metrolist.desktop.playback.Player.error.value}")
    com.metrolist.desktop.playback.Player.pause()
    exitProcess(if (failure == null && seekOk && handedOver && after in 500..6000 && swapped != null && scReady != null && scPos > 500) 0 else 1)
}
