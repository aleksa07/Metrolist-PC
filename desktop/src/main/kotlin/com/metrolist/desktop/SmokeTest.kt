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

object SmokeTest {
    @JvmStatic
    fun main(args: Array<String>) = runSmokeTest(args.firstOrNull())
}

/** End-to-end check without UI: search, resolve, then decode ~6 s of audio at zero volume. */
fun runSmokeTest(queryArg: String?): Unit = runBlocking {
    val query = queryArg ?: "Daft Punk One More Time"
    com.metrolist.desktop.playback.Spectrum.selfCheck()
    println("spectrum self-check: ok")
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
    YouTube.visitorData = YouTube.visitorData().getOrNull()
    println("visitorData: ${if (YouTube.visitorData != null) "ok" else "missing"}")

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
    engine.stop()
    done.countDown()
    exitProcess(if (failure == null && engine.positionMs > 60_000) 0 else 1)
}
