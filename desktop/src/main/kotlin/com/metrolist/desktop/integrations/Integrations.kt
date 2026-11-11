package com.metrolist.desktop.integrations

import com.metrolist.desktop.data.AppSettings
import com.metrolist.desktop.data.Settings
import com.metrolist.desktop.data.Track
import com.metrolist.desktop.data.resized
import com.metrolist.desktop.playback.Player
import com.metrolist.lastfm.LastFM
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import java.awt.Desktop
import java.io.Closeable
import java.io.RandomAccessFile
import java.net.URI
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.util.UUID
import java.util.concurrent.Executors

/** Last.fm scrobbling and Discord Rich Presence, driven by the player state once per second. */
object Integrations {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Scrobble state for the current play of the current track.
    private var trackId: String? = null
    private var startedAtSec = 0L
    private var playedMs = 0L
    private var scrobbled = false
    private var nowPlayingSent = false

    // Last Discord update, to only send when something visible changed.
    private var discordKey: String? = null
    private var lastTickMs = System.currentTimeMillis()

    fun start() {
        scope.launch {
            while (true) {
                runCatching { tick() }.onFailure { Timber.tag("Integrations").w(it, "tick failed") }
                delay(1000)
            }
        }
    }

    private fun lastFmReady(s: AppSettings): Boolean {
        if (s.lastfmApiKey.isBlank() || s.lastfmSecret.isBlank() || s.lastfmSession.isBlank()) return false
        LastFM.initialize(s.lastfmApiKey.trim(), s.lastfmSecret.trim())
        LastFM.sessionKey = s.lastfmSession
        return true
    }

    private fun tick() {
        val s = Settings.value
        val track = Player.current.value
        val playing = Player.isPlaying.value && !Player.buffering.value
        val now = System.currentTimeMillis()
        val elapsed = (now - lastTickMs).coerceIn(0, 5000)
        lastTickMs = now

        if (track?.id != trackId) {
            trackId = track?.id
            startedAtSec = now / 1000
            playedMs = 0
            scrobbled = false
            nowPlayingSent = false
        }
        if (track != null && playing) {
            playedMs += elapsed
            if (s.scrobbleEnabled && lastFmReady(s)) {
                if (s.lastfmNowPlaying && !nowPlayingSent) {
                    nowPlayingSent = true
                    scope.launch { LastFM.updateNowPlaying(track.artists.firstOrNull()?.name ?: track.artistText, track.title, track.album?.name, duration = durationSec(track)) }
                }
                val duration = durationSec(track)
                // shortcut: repeat-one replays of the same track count as one play, until per-play tracking matters.
                if (!scrobbled && duration != null && duration >= s.scrobbleMinTrackSec &&
                    playedMs >= minOf(duration * 1000L * s.scrobblePercent, s.scrobbleMinutes * 60_000f).toLong()
                ) {
                    scrobbled = true
                    scope.launch {
                        LastFM.scrobble(track.artists.firstOrNull()?.name ?: track.artistText, track.title, startedAtSec, track.album?.name, duration = duration)
                            .onFailure { Timber.tag("LastFM").w(it, "scrobble failed") }
                    }
                }
            }
        }
        updateDiscord(s, track, playing)
    }

    private fun durationSec(track: Track): Int? = (Player.duration.value / 1000).toInt().takeIf { it > 0 } ?: track.durationSec

    fun onLike(track: Track, liked: Boolean) {
        val s = Settings.value
        if (s.lastfmSendLikes && lastFmReady(s)) {
            scope.launch { LastFM.setLoveStatus(track.artists.firstOrNull()?.name ?: track.artistText, track.title, liked) }
        }
    }

    /** Last.fm web auth: the user approves in the browser, so no password passes through the app. */
    suspend fun connectLastFm(): Result<String> =
        runCatching {
            val s = Settings.value
            require(s.lastfmApiKey.isNotBlank() && s.lastfmSecret.isNotBlank()) { "Enter your Last.fm API key and shared secret first" }
            LastFM.initialize(s.lastfmApiKey.trim(), s.lastfmSecret.trim())
            val token = LastFM.getToken().getOrThrow().token
            Desktop.getDesktop().browse(URI("https://www.last.fm/api/auth/?api_key=${s.lastfmApiKey.trim()}&token=$token"))
            repeat(60) {
                delay(3000)
                LastFM.getSession(token).getOrNull()?.session?.let { session ->
                    Settings.update { it.copy(lastfmSession = session.key, lastfmUser = session.name) }
                    return@runCatching session.name
                }
            }
            error("Timed out waiting for approval on last.fm")
        }

    // ---- Discord ----

    // Discord fetches these itself, so they must stay reachable on the default branch.
    private const val ICONS = "https://raw.githubusercontent.com/aleksa07/Metrolist-PC/desktop/desktop/assets/discord"

    private fun template(t: String, track: Track) =
        t.replace("{song.name}", track.title)
            .replace("{artist.name}", track.artistText.ifBlank { "Unknown Artist" })
            .replace("{album.name}", track.album?.name ?: "Unknown Album")
            .take(128)
            .padEnd(2, ' ')

    private fun updateDiscord(s: AppSettings, track: Track?, playing: Boolean) {
        val show = s.discordEnabled && track != null && (playing || s.discordShowPaused)
        // Position is quantised so a seek re-sends timestamps but normal playback does not.
        val startMs = System.currentTimeMillis() - Player.position.value
        val key = if (show) "${track!!.id}|$playing|${startMs / 3000}|${s.hashCode()}" else "clear"
        if (key == discordKey) return
        discordKey = key
        val activity =
            if (!show) {
                null
            } else {
                buildJsonObject {
                    put("type", s.discordActivityType)
                    put("details", template(s.discordDetails, track!!))
                    put("state", if (playing) template(s.discordState, track) else "Paused")
                    if (playing) {
                        putJsonObject("timestamps") {
                            put("start", startMs)
                            durationSec(track)?.let { put("end", startMs + it * 1000L) }
                        }
                    }
                    putJsonObject("assets") {
                        track.thumbnail.resized(512)?.let { put("large_image", it) }
                        // Discord shows this as a third line, so skip it when it would repeat the title.
                        track.album?.name?.takeIf { it != track.title }?.let { put("large_text", it.take(128).padEnd(2, ' ')) }
                        put("small_image", "$ICONS/${if (playing) "play" else "pause"}.png")
                        put("small_text", if (playing) "Playing" else "Paused")
                    }
                    if (s.discordListenButton || s.discordProjectButton) {
                        putJsonArray("buttons") {
                            if (s.discordListenButton) addJsonObject { put("label", "Listen on YouTube Music"); put("url", track.shareLink) }
                            if (s.discordProjectButton) addJsonObject { put("label", "Visit Metrolist"); put("url", "https://github.com/MetrolistGroup/Metrolist") }
                        }
                    }
                }
            }
        scope.launch { DiscordIpc.setActivity(activity, enabled = s.discordEnabled) }
    }
}

/** Minimal Discord local RPC client (the same IPC the official Game SDK uses). */
object DiscordIpc {
    // Metrolist's Discord application, as configured in the Android build.
    private const val APP_ID = "1447278780795064401"
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "discord-ipc").apply { isDaemon = true } }.asCoroutineDispatcher()

    private class Conn(val write: (ByteArray) -> Unit, val read: (ByteArray) -> Unit, private val onClose: () -> Unit) : Closeable {
        override fun close() = onClose()
    }

    private var conn: Conn? = null
    private var nextAttemptMs = 0L
    @Volatile var connected = false
        private set

    suspend fun setActivity(activity: JsonObject?, enabled: Boolean) =
        withContext(io) {
            if (!enabled && conn == null) return@withContext
            val c = conn ?: connect() ?: return@withContext
            try {
                send(c, 1, buildJsonObject {
                    put("cmd", "SET_ACTIVITY")
                    put("nonce", UUID.randomUUID().toString())
                    putJsonObject("args") {
                        put("pid", ProcessHandle.current().pid())
                        if (activity != null) put("activity", activity)
                    }
                })
                receive(c)
                if (!enabled) disconnect()
            } catch (e: Exception) {
                Timber.tag("Discord").d("IPC lost: $e")
                disconnect()
            }
        }

    private fun connect(): Conn? {
        if (System.currentTimeMillis() < nextAttemptMs) return null
        nextAttemptMs = System.currentTimeMillis() + 15_000
        for (i in 0..9) {
            val c = runCatching { open(i) }.getOrNull() ?: continue
            try {
                send(c, 0, buildJsonObject { put("v", 1); put("client_id", APP_ID) })
                val ready = receive(c)
                if (!ready.contains("READY")) throw IllegalStateException(ready)
                conn = c
                connected = true
                Timber.tag("Discord").d("connected on pipe $i")
                return c
            } catch (e: Exception) {
                runCatching { c.close() }
            }
        }
        return null
    }

    private fun open(i: Int): Conn =
        if (System.getProperty("os.name").startsWith("Windows")) {
            val f = RandomAccessFile("\\\\.\\pipe\\discord-ipc-$i", "rw")
            Conn({ f.write(it) }, { f.readFully(it) }, { f.close() })
        } else {
            val dir = listOf("XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP").firstNotNullOfOrNull { System.getenv(it) } ?: "/tmp"
            val ch = SocketChannel.open(UnixDomainSocketAddress.of("$dir/discord-ipc-$i"))
            Conn(
                { ch.write(ByteBuffer.wrap(it)) },
                { buf -> val bb = ByteBuffer.wrap(buf); while (bb.hasRemaining()) if (ch.read(bb) < 0) error("closed") },
                { ch.close() },
            )
        }

    private fun disconnect() {
        runCatching { conn?.close() }
        conn = null
        connected = false
    }

    private fun send(c: Conn, op: Int, payload: JsonObject) {
        val body = payload.toString().toByteArray()
        val frame = ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN).putInt(op).putInt(body.size).put(body)
        c.write(frame.array())
    }

    private fun receive(c: Conn): String {
        val header = ByteArray(8)
        c.read(header)
        val len = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
        require(len in 0..1_000_000) { "bad frame" }
        val body = ByteArray(len)
        c.read(body)
        return String(body)
    }
}
