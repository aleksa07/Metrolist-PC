package com.metrolist.desktop.integrations

import com.metrolist.desktop.data.ArtistRef
import com.metrolist.desktop.data.Settings
import com.metrolist.desktop.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Experimental SoundCloud library: the web player's api-v2 with its public client_id and the
 * oauth_token sign-in cookie. Unlike Spotify, songs play from SoundCloud itself (HLS through FFmpeg).
 */
object SoundCloud {
    private const val PREFIX = "soundcloud:"
    private const val API = "https://api-v2.soundcloud.com"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var clientId: String? = null
    private var userId: Long? = null

    fun isTrack(track: Track) = track.id.startsWith(PREFIX)

    fun signOut() {
        userId = null
        Settings.update { it.copy(soundcloudToken = "") }
    }

    // ---- HTTP ----

    private suspend fun text(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val b = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", UA)
            headers.forEach { (k, v) -> b.header(k, v) }
            http.send(b.build(), HttpResponse.BodyHandlers.ofString()).let { it.statusCode() to it.body() }
        }

    /** The client_id baked into soundcloud.com's scripts; it rotates, so a 401 fetches it again. */
    private suspend fun clientId(refresh: Boolean = false): String =
        lock.withLock {
            clientId?.takeUnless { refresh }?.let { return it }
            val page = text("https://soundcloud.com/").second
            val scripts = Regex("https://a-v2\\.sndcdn\\.com/assets/[^\"]+\\.js").findAll(page).map { it.value }.toList().asReversed()
            val id = scripts.firstNotNullOfOrNull { Regex("client_id[:=]\"([A-Za-z0-9]{32})\"").find(text(it).second)?.groupValues?.get(1) }
                ?: error("couldn't find SoundCloud's client id")
            id.also { clientId = it }
        }

    private suspend fun get(url: String): JsonObject = raw(url) as JsonObject

    /** api-v2 GET; [url] is a path or a full next_href. */
    private suspend fun raw(url: String, token: String = Settings.value.soundcloudToken): JsonElement {
        val full = if (url.startsWith("http")) url else API + url
        val auth = if (token.isBlank()) emptyMap() else mapOf("Authorization" to "OAuth $token")
        for (attempt in 0..1) {
            val sep = if ('?' in full) '&' else '?'
            val (status, body) = text("$full${sep}client_id=${clientId(refresh = attempt > 0)}", auth)
            if (status in 200..299) return json.parseToJsonElement(body)
            if (status != 401 && status != 403) error("SoundCloud HTTP $status")
        }
        error(if (token.isBlank()) "SoundCloud refused the request" else "SoundCloud sign-in expired, sign in again in Settings")
    }

    private suspend fun me(): Long =
        userId ?: (get("/me").long("id") ?: error("not signed in to SoundCloud")).also { userId = it }

    /** Follows next_href pages of a collection. shortcut: stops at 5000 items, page further if someone has more. */
    private suspend fun collection(path: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var next: String? = path
        while (next != null && out.size < 5000) {
            val page = get(next)
            out += page.arr("collection").orEmpty().map { it as JsonObject }
            next = page.str("next_href")
        }
        return out
    }

    // ---- library ----

    /** The user's own playlists and albums, then the ones they liked. */
    suspend fun playlists(): Result<List<Spotify.Playlist>> =
        runCatching {
            val uid = me()
            val own = collection("/users/$uid/playlists?limit=200")
            val liked = collection("/users/$uid/likes?limit=200").mapNotNull { it.obj("playlist") }
            (own + liked).distinctBy { it.long("id") }.mapNotNull { p ->
                Spotify.Playlist(p.long("id")?.toString() ?: return@mapNotNull null, p.str("title").orEmpty(), p.obj("user")?.str("username"), artwork(p))
            }
        }

    /** Every track of a playlist, or the liked tracks when [playlistId] is null. */
    suspend fun tracks(playlistId: String?): Result<List<Track>> =
        runCatching {
            if (playlistId == null) return@runCatching collection("/users/${me()}/track_likes?limit=200").mapNotNull { it.obj("track")?.let(::toTrack) }
            require(playlistId.matches(Regex("[0-9]{1,20}"))) { "not a SoundCloud playlist id" }
            val items = get("/playlists/$playlistId").arr("tracks").orEmpty().map { it as JsonObject }
            // Only the first few tracks come complete; the rest are bare ids.
            val missing = items.filter { it.str("title") == null }.mapNotNull { it.long("id") }
            val full = missing.chunked(50).flatMap { ids -> (raw("/tracks?ids=${ids.joinToString("%2C")}") as JsonArray).map { it as JsonObject } }
            val byId = (items + full).filter { it.str("title") != null }.associateBy { it.long("id") }
            items.mapNotNull { byId[it.long("id")]?.let(::toTrack) }
        }

    private fun toTrack(t: JsonObject): Track? {
        if (t.str("policy") == "BLOCK") return null
        return Track(
            id = PREFIX + (t.long("id") ?: return null),
            title = t.str("title").orEmpty(),
            artists = listOfNotNull(t.obj("user")?.str("username")?.let { ArtistRef(it) }),
            durationSec = t.long("duration")?.let { (it / 1000).toInt() },
            thumbnail = artwork(t),
        )
    }

    private fun artwork(o: JsonObject) = (o.str("artwork_url") ?: o.obj("user")?.str("avatar_url"))?.replace("-large.", "-t500x500.")

    // ---- playback ----

    /** A fresh stream URL (HLS, signed for a few hours) for a SoundCloud track. */
    suspend fun stream(track: Track): Result<String> =
        runCatching {
            val id = track.id.removePrefix(PREFIX).toLong()
            val t = get("/tracks/$id")
            val pick = pickTranscoding(t.obj("media")?.arr("transcodings").orEmpty().map { it as JsonObject })
                ?: error("SoundCloud has no playable stream for this track")
            val auth = t.str("track_authorization")?.let { "?track_authorization=" + URLEncoder.encode(it, Charsets.UTF_8) }.orEmpty()
            get(pick + auth).str("url") ?: error("SoundCloud gave no stream URL")
        }

    /** Unencrypted streams only, AAC over HLS first, then any HLS, then progressive MP3. */
    internal fun pickTranscoding(list: List<JsonObject>): String? =
        list.filter { it.obj("format")?.str("protocol") in setOf("hls", "progressive") && it.str("snipped") != "true" }
            .sortedBy { t ->
                val f = t.obj("format")
                when {
                    f?.str("protocol") == "hls" && f.str("mime_type").orEmpty().startsWith("audio/mp4") -> 0
                    f?.str("protocol") == "hls" -> 1
                    else -> 2
                }
            }.firstOrNull()?.str("url")

    fun selfCheck() {
        fun t(protocol: String, mime: String, url: String) = json.parseToJsonElement("""{"url":"$url","format":{"protocol":"$protocol","mime_type":"$mime"}}""") as JsonObject
        check(pickTranscoding(listOf(t("progressive", "audio/mpeg", "p"), t("hls", "audio/mpegurl", "m"), t("hls", "audio/mp4; codecs=mp4a.40.2", "a"))) == "a") { "aac first" }
        check(pickTranscoding(listOf(t("ctr-encrypted-hls", "audio/mp4", "e"), t("progressive", "audio/mpeg", "p"))) == "p") { "skip encrypted" }
        check(pickTranscoding(listOf(t("cbc-encrypted-hls", "audio/mp4", "e"))) == null) { "nothing playable" }
    }

    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.arr(k: String) = this[k] as? JsonArray
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
}
