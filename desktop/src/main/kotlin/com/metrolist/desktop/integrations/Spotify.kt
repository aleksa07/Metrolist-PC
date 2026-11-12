package com.metrolist.desktop.integrations

import com.metrolist.desktop.data.ArtistRef
import com.metrolist.desktop.data.Library
import com.metrolist.desktop.data.Settings
import com.metrolist.desktop.data.Track
import com.metrolist.desktop.data.toTrack
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Experimental Spotify library, ported from Meld (github.com/FrancescoGrazioso/Meld, GPL-3.0):
 * the web player's own GraphQL API with the sp_dc sign-in cookie. Spotify only supplies the song
 * list; each song is matched to YouTube Music when it is about to play.
 */
object Spotify {
    private const val PREFIX = "spotify:"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    // Spotify rotates the TOTP secret and the query hashes; Meld's maintainers publish the current ones.
    private const val SECRET_URL = "https://api.github.com/gists/22ed9c6ba463899e933427f7de1f0eef"
    private const val HASHES_URL = "https://raw.githubusercontent.com/FrancescoGrazioso/Meld/main/docs/spotify-gql-hashes.json"
    private val BUILT_IN_HASHES =
        mapOf(
            "libraryV3" to "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455",
            "fetchPlaylist" to "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf",
            "fetchLibraryTracks" to "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240",
        )

    data class Playlist(val id: String, val name: String, val owner: String?, val thumbnail: String?)

    class SpotifyException(message: String) : Exception(message)

    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenLock = Mutex()
    private var token: String? = null
    private var tokenFor: String? = null
    private var tokenExpires = 0L
    private var hashes: Map<String, List<String>>? = null

    fun isPlaceholder(track: Track) = track.id.startsWith(PREFIX)

    fun signOut() {
        token = null
        tokenFor = null
        Settings.update { it.copy(spotifyCookie = "") }
    }

    // ---- HTTP ----

    private suspend fun get(url: String, headers: Map<String, String> = emptyMap()): JsonObject = send(HttpRequest.newBuilder(URI(url)).GET(), headers)

    private suspend fun send(builder: HttpRequest.Builder, headers: Map<String, String>): JsonObject =
        withContext(Dispatchers.IO) {
            builder.timeout(Duration.ofSeconds(30)).header("User-Agent", UA).header("Accept", "application/json")
            headers.forEach { (k, v) -> builder.header(k, v) }
            val resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) throw HttpError(resp.statusCode(), resp.body().take(200))
            json.parseToJsonElement(resp.body()) as JsonObject
        }

    private class HttpError(val status: Int, body: String) : Exception("HTTP $status: $body")

    // ---- sign-in token ----

    /** A web-player token; [cookie] blank gives an anonymous one (public playlists only). */
    private suspend fun accessToken(cookie: String = Settings.value.spotifyCookie): String =
        tokenLock.withLock {
            token?.takeIf { tokenFor == cookie && System.currentTimeMillis() < tokenExpires - 60_000 }?.let { return it }
            val nuances = json.parseToJsonElement(get(SECRET_URL).obj("files")?.values?.firstOrNull()?.let { (it as JsonObject).str("content") } ?: throw SpotifyException("no TOTP secret")) as JsonArray
            val nuance = nuances.map { it as JsonObject }.maxBy { it.int("v") ?: 0 }
            val serverTime = get("https://open.spotify.com/api/server-time").long("serverTime") ?: (System.currentTimeMillis() / 1000)
            val totp = totp(nuance.str("s")!!, serverTime)
            val url = "https://open.spotify.com/api/token?reason=transport&productType=web-player&totp=$totp&totpServer=$totp&totpVer=${nuance.int("v")}"
            val res = get(url, if (cookie.isBlank()) emptyMap() else mapOf("Cookie" to "sp_dc=$cookie"))
            val anonymous = res["isAnonymous"]?.jsonPrimitive?.contentOrNull == "true"
            if (cookie.isNotBlank() && anonymous) throw SpotifyException("Spotify sign-in expired, sign in again in Settings")
            token = res.str("accessToken") ?: throw SpotifyException("no access token")
            tokenFor = cookie
            tokenExpires = res.long("accessTokenExpirationTimestampMs") ?: (System.currentTimeMillis() + 600_000)
            token!!
        }

    /** RFC 6238 TOTP (SHA-1, 30 s, 6 digits) from a base32 secret. */
    internal fun totp(base32: String, timeSec: Long): String {
        var buffer = 0
        var bits = 0
        val key = mutableListOf<Byte>()
        for (c in base32.uppercase()) {
            val v = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c).takeIf { it >= 0 } ?: continue
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                key += ((buffer shr bits) and 0xFF).toByte()
            }
        }
        val counter = java.nio.ByteBuffer.allocate(8).putLong(timeSec / 30).array()
        val hash = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA1")) }.doFinal(counter)
        val o = hash.last().toInt() and 0x0F
        val code = ((hash[o].toInt() and 0x7F) shl 24) or ((hash[o + 1].toInt() and 0xFF) shl 16) or ((hash[o + 2].toInt() and 0xFF) shl 8) or (hash[o + 3].toInt() and 0xFF)
        return (code % 1_000_000).toString().padStart(6, '0')
    }

    // ---- GraphQL ----

    private suspend fun hashesFor(op: String): List<String> {
        val known =
            hashes ?: runCatching {
                get(HASHES_URL).obj("operations")!!.mapValues { (_, v) -> listOfNotNull((v as JsonObject).str("hash"), v.str("previous_hash")) }
            }.getOrDefault(emptyMap()).also { hashes = it }
        return (known[op].orEmpty() + listOfNotNull(BUILT_IN_HASHES[op])).distinct()
    }

    private suspend fun gql(op: String, variables: JsonObject, cookie: String = Settings.value.spotifyCookie): JsonObject {
        val auth = mapOf("Authorization" to "Bearer ${accessToken(cookie)}", "Content-Type" to "application/json", "app-platform" to "WebPlayer", "Origin" to "https://open.spotify.com", "Referer" to "https://open.spotify.com/")
        var last: Exception = SpotifyException("no query hash for $op")
        for (hash in hashesFor(op)) {
            val body =
                buildJsonObject {
                    put("variables", variables)
                    put("operationName", op)
                    putJsonObject("extensions") { putJsonObject("persistedQuery") { put("version", 1); put("sha256Hash", hash) } }
                }
            val res =
                try {
                    send(HttpRequest.newBuilder(URI("https://api-partner.spotify.com/pathfinder/v2/query")).POST(HttpRequest.BodyPublishers.ofString(body.toString())), auth)
                } catch (e: HttpError) {
                    if (e.status == 412) { last = e; continue } // hash rotated: try the next one
                    throw e
                }
            val error = (res["errors"] as? JsonArray)?.firstOrNull()?.let { (it as JsonObject).str("message") }
            if (error == null) return res
            last = SpotifyException("Spotify: $error")
            if (!error.contains("PersistedQueryNotFound", ignoreCase = true)) break
        }
        throw last
    }

    // ---- library ----

    suspend fun playlists(): Result<List<Playlist>> =
        runCatching {
            val vars =
                json.parseToJsonElement(
                    """{"filters":["Playlists"],"order":null,"textFilter":"","features":["LIKED_SONGS","YOUR_EPISODES_V2","PRERELEASES","EVENTS"],
                    "limit":500,"offset":0,"flatten":true,"expandedFolders":[],"folderUri":null,"includeFoldersWhenFlattening":false}""",
                ) as JsonObject
            val items = gql("libraryV3", vars).path("data", "me", "libraryV3")?.arr("items").orEmpty()
            items.mapNotNull { e ->
                val w = (e as JsonObject).obj("item") ?: return@mapNotNull null
                val d = w.obj("data")?.takeIf { it.str("__typename") == "Playlist" } ?: return@mapNotNull null
                Playlist(
                    id = w.str("_uri")?.substringAfterLast(":") ?: return@mapNotNull null,
                    name = d.str("name").orEmpty(),
                    owner = d.path("ownerV2", "data")?.str("name"),
                    thumbnail = d.obj("images")?.arr("items")?.firstOrNull()?.let { image(it as JsonObject) },
                )
            }
        }

    /** Every song of a playlist, or of Liked Songs when [playlistId] is null. */
    suspend fun tracks(playlistId: String?, cookie: String = Settings.value.spotifyCookie): Result<List<Track>> =
        runCatching {
            val out = mutableListOf<Track>()
            // shortcut: stops at 5000 songs, page further if someone has a bigger playlist.
            while (out.size < 5000) {
                val page =
                    if (playlistId == null) {
                        gql("fetchLibraryTracks", buildJsonObject { put("offset", out.size); put("limit", 100) }, cookie).path("data", "me", "library", "tracks")
                    } else {
                        require(playlistId.matches(Regex("[A-Za-z0-9]{1,40}"))) { "not a Spotify playlist id" }
                        gql("fetchPlaylist", buildJsonObject { put("uri", "spotify:playlist:$playlistId"); put("offset", out.size); put("limit", 100); put("enableWatchFeedEntrypoint", false) }, cookie)
                            .path("data", "playlistV2", "content")
                    } ?: throw SpotifyException("unexpected Spotify response")
                val items = page.arr("items").orEmpty()
                items.forEach { e ->
                    val w = (e as JsonObject).obj(if (playlistId == null) "track" else "itemV2") ?: return@forEach
                    val d = w.obj("data")?.takeIf { it.str("__typename") in setOf(null, "Track") } ?: return@forEach
                    val id = (w.str("_uri") ?: d.str("uri"))?.takeIf { it.startsWith("spotify:track:") }?.substringAfterLast(":") ?: return@forEach
                    out +=
                        Track(
                            id = PREFIX + id,
                            title = d.str("name").orEmpty(),
                            artists = d.obj("artists")?.arr("items").orEmpty().mapNotNull { (it as JsonObject).path("profile")?.str("name")?.let { n -> ArtistRef(n) } },
                            durationSec = (d.path("duration")?.long("totalMilliseconds") ?: d.path("trackDuration")?.long("totalMilliseconds"))?.let { (it / 1000).toInt() },
                            thumbnail = d.obj("albumOfTrack")?.let { image(it.obj("coverArt") ?: it) },
                            explicit = d.path("contentRating")?.str("label") == "EXPLICIT",
                        )
                }
                if (items.isEmpty() || out.size >= (page.int("totalCount") ?: 0)) break
            }
            out
        }

    // ---- matching to YouTube Music ----

    /** The YouTube Music song for a Spotify placeholder, cached for good once found. */
    suspend fun match(track: Track): Result<Track> =
        runCatching {
            val key = track.id.removePrefix(PREFIX)
            Library.value.spotifyMatches[key]?.let { return@runCatching it }
            val query = listOfNotNull(track.artists.firstOrNull()?.name, track.title).joinToString(" ")
            val songs = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow().items.filterIsInstance<SongItem>()
            val best = songs.take(10).maxByOrNull { score(track, it.title, it.artists.firstOrNull()?.name.orEmpty(), it.duration) }
            val found = best?.takeIf { score(track, it.title, it.artists.firstOrNull()?.name.orEmpty(), it.duration) >= 0.35 }?.toTrack()
                ?: throw SpotifyException("\"${track.title}\" isn't on YouTube Music")
            Library.update { it.copy(spotifyMatches = it.spotifyMatches + (key to found)) }
            found
        }

    private val variant = Regex("\\b(live|remix|cover|karaoke|instrumental|acoustic|sped up|slowed|nightcore|8d)\\b")

    /** Meld's weighting: title 45 %, artist 35 %, duration 20 %, minus a penalty for live/remix/… versions Spotify didn't ask for. */
    internal fun score(track: Track, title: String, artist: String, durationSec: Int?): Double {
        fun norm(s: String) = s.lowercase().replace(Regex("\\((feat|ft)\\..*?\\)|\\[.*?]|\\(.*?remaster.*?\\)"), "").replace(Regex("[^\\p{L}\\p{N}\\s]"), "").replace(Regex("\\s+"), " ").trim()
        fun dice(a: String, b: String): Double {
            if (a == b) return 1.0
            val x = a.windowed(2).toSet()
            val y = b.windowed(2).toSet()
            return if (x.isEmpty() || y.isEmpty()) 0.0 else 2.0 * x.count { it in y } / (x.size + y.size)
        }
        val duration =
            if (durationSec == null || track.durationSec == null) {
                0.5
            } else {
                when (kotlin.math.abs(durationSec - track.durationSec)) {
                    in 0..2 -> 1.0
                    in 3..5 -> 0.8
                    in 6..10 -> 0.5
                    in 11..30 -> 0.2
                    else -> 0.0
                }
            }
        val extra = variant.findAll(title.lowercase()).map { it.value }.toSet() - variant.findAll(track.title.lowercase()).map { it.value }.toSet()
        return dice(norm(track.title), norm(title)) * 0.45 + dice(norm(track.artists.firstOrNull()?.name.orEmpty()), norm(artist)) * 0.35 + duration * 0.2 -
            (extra.size * 0.15).coerceAtMost(0.3)
    }

    fun selfCheck() {
        check(totp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59) == "287082") { "TOTP" } // RFC 6238 test vector
        val t = Track(PREFIX + "x", "Blinding Lights", listOf(ArtistRef("The Weeknd")), durationSec = 200)
        check(score(t, "Blinding Lights", "The Weeknd", 201) > score(t, "Blinding Lights (Live)", "The Weeknd", 201)) { "variant penalty" }
        check(score(t, "Save Your Tears", "The Weeknd", 215) < 0.75) { "different song" }
    }

    // ---- JSON helpers ----

    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.arr(k: String) = this[k] as? JsonArray
    private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
    private fun JsonObject.long(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
    private fun JsonObject.path(vararg keys: String): JsonObject? = keys.fold(this as JsonObject?) { o, k -> o?.obj(k) }

    /** The largest image of an `{ sources: [{url, width}] }` object. */
    private fun image(o: JsonObject): String? = o.arr("sources")?.map { it as JsonObject }?.maxByOrNull { it.int("width") ?: 0 }?.str("url")
}
