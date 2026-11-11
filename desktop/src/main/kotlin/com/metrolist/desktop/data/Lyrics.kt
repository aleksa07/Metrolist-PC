package com.metrolist.desktop.data

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.kugou.KuGou
import com.metrolist.lrclib.LrcLib
import com.metrolist.music.betterlyrics.BetterLyrics
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(val synced: List<LyricLine>?, val plain: String?, val source: String) {
    val isEmpty get() = synced.isNullOrEmpty() && plain.isNullOrBlank()
}

/** Tries the Android app's lyrics providers in turn, preferring synced lyrics. */
object LyricsRepository {
    private val cache = ConcurrentHashMap<String, Lyrics>()

    private val providers: List<Pair<String, suspend (Track) -> Result<String?>>> =
        listOf(
            "LRCLIB" to { t -> LrcLib.getLyrics(t.title, mainArtist(t), t.durationSec ?: -1, t.album?.name) },
            "BetterLyrics" to { t -> BetterLyrics.getLyrics(t.title, mainArtist(t), t.durationSec ?: -1, t.album?.name) },
            "KuGou" to { t -> KuGou.getLyrics(t.title, mainArtist(t), t.durationSec ?: -1, t.album?.name) },
            "YouTube Music" to { t ->
                runCatching {
                    YouTube.next(WatchEndpoint(videoId = t.id)).getOrThrow().lyricsEndpoint?.let { YouTube.lyrics(it).getOrThrow() }
                }
            },
        )

    private fun mainArtist(t: Track) = t.artists.firstOrNull()?.name ?: t.artistText

    suspend fun get(track: Track): Lyrics {
        cache[track.id]?.let { return it }
        var plainFallback: Lyrics? = null
        for ((name, fetch) in providers) {
            val text =
                try {
                    fetch(track).getOrNull()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }?.takeIf { it.isNotBlank() } ?: continue
            val synced = parseLrc(text).takeIf { it.size >= 3 }
            if (synced != null) return Lyrics(synced, null, name).also { cache[track.id] = it }
            if (plainFallback == null) plainFallback = Lyrics(null, text.trim(), name)
        }
        return (plainFallback ?: Lyrics(null, null, "")).also { cache[track.id] = it }
    }

    private val lineTag = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val wordTag = Regex("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>")

    fun parseLrc(lrc: String): List<LyricLine> =
        lrc.lines().flatMap { line ->
            val tags = lineTag.findAll(line).toList()
            if (tags.isEmpty()) return@flatMap emptyList()
            // Word-level timings (from BetterLyrics) are dropped; lines are highlighted as a whole.
            val text = line.substring(tags.last().range.last + 1).replace(wordTag, "").replace(Regex("\\s+"), " ").trim()
            tags.map { m ->
                val (min, sec, frac) = m.destructured
                val ms = frac.takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.take(3)?.toLong() ?: 0
                LyricLine(min.toLong() * 60_000 + sec.toLong() * 1000 + ms, text)
            }
        }.sortedBy { it.timeMs }
}
