package com.metrolist.desktop.playback

import com.metrolist.desktop.data.AppDirs
import com.metrolist.innertube.YouTube
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality as InnerTubeXAudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

enum class AudioQuality { AUTO, HIGH, LOW }

class ResolvedStream(
    val videoId: String,
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String,
    val bitrate: Int?,
    val contentLength: Long?,
    val durationSeconds: Int?,
    val loudnessDb: Double?,
    val client: String,
    val expiresAtMs: Long,
)

/** Desktop port of the Android app's InnerTubeXPlayer: resolves a playable direct audio URL for a video. */
object StreamResolver {
    private const val TAG = "StreamResolver"
    private const val FAILURE_TTL_MS = 5 * 60 * 1000L
    private const val DEFAULT_TTL_SECONDS = 5 * 60

    private class Bundle(val generation: Long, val cipher: YouTubeCipherService, val extractor: InnerTubeExtractor)

    private class Failures(val clients: Set<String>, val atMs: Long)

    @Volatile private var current: Bundle? = null
    private val mutex = Mutex()
    private val failures = ConcurrentHashMap<String, Failures>()
    private val cache = ConcurrentHashMap<String, ResolvedStream>()

    private val logger =
        InnerTubeLogger { event ->
            val details = event.details.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
            val message = event.message + details.takeUnless { event.details.isEmpty() }.orEmpty()
            val t = Timber.tag(event.tag)
            when (event.level) {
                InnerTubeLogLevel.DEBUG -> t.d(message)
                InnerTubeLogLevel.INFO -> t.d(message)
                InnerTubeLogLevel.WARN -> t.w(message)
                InnerTubeLogLevel.ERROR -> t.e(message)
            }
        }

    suspend fun resolve(
        videoId: String,
        quality: AudioQuality,
        playlistId: String? = null,
        forceRefresh: Boolean = false,
    ): Result<ResolvedStream> {
        if (!forceRefresh) {
            cache[videoId]?.takeIf { it.expiresAtMs - System.currentTimeMillis() > 60_000 }?.let { return Result.success(it) }
        }
        return try {
            val hints =
                ContentHints(isUploaded = playlistId?.contains("MLPT") == true)
                    // The FFmpeg engine plays progressive HTTP; bounded ranges are handled by RangeProxy.
                    .withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true)
            val excluded = failedClients(videoId)
            val q =
                when (quality) {
                    AudioQuality.HIGH -> InnerTubeXAudioQuality.HIGH
                    AudioQuality.LOW -> InnerTubeXAudioQuality.LOW
                    AudioQuality.AUTO -> InnerTubeXAudioQuality.AUTO
                }
            val stream =
                requireNotNull(
                    withClientFallback(excluded) { clients, low ->
                        bundle().extractor.extract(
                            videoId = videoId,
                            hints = hints,
                            excludedClients = clients,
                            audioQuality = if (low) InnerTubeXAudioQuality.LOW else q,
                            clientPlaybackNonce = generateClientPlaybackNonce(),
                        )
                    },
                ) { "No playable stream" }
            check(stream.sabrBootstrap == null) { "SABR streams are not supported" }
            val mime =
                if (stream.codecs.isNullOrBlank()) stream.mimeType.orEmpty() else "${stream.mimeType}; codecs=\"${stream.codecs}\""
            val ttl =
                stream.expiresAt
                    ?.let { ((it.toEpochMilliseconds() - Clock.System.now().toEpochMilliseconds()) / 1000L).toInt() }
                    ?.coerceAtLeast(1) ?: DEFAULT_TTL_SECONDS
            val resolved =
                ResolvedStream(
                    videoId = videoId,
                    url = stream.audioUrl,
                    headers = stream.headers,
                    mimeType = mime,
                    bitrate = stream.bitrate,
                    contentLength = stream.contentLengthBytes,
                    durationSeconds = stream.mediaMetadata?.durationSeconds?.toInt(),
                    loudnessDb = stream.loudnessDb,
                    client = stream.clientName,
                    expiresAtMs = System.currentTimeMillis() + ttl * 1000L,
                )
            cache[videoId] = resolved
            Result.success(resolved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StreamResolveException) {
            Result.failure(if (e.reason == StreamResolveException.Reason.NETWORK && e.cause != null) e.cause!! else e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Called when the media server rejects a stream; the next resolve avoids that client. */
    suspend fun markFailed(stream: ResolvedStream) {
        cache.remove(stream.videoId)
        failures.compute(stream.videoId) { _, f -> Failures(f?.clients.orEmpty() + stream.client, System.currentTimeMillis()) }
        runCatching { bundle().cipher.refreshAfterStreamRejection() }
    }

    private fun failedClients(videoId: String): Set<String> {
        val f = failures[videoId] ?: return emptySet()
        if (System.currentTimeMillis() - f.atMs > FAILURE_TTL_MS) {
            failures.remove(videoId)
            return emptySet()
        }
        return f.clients
    }

    private suspend fun <T : Any> withClientFallback(
        excluded: Set<String>,
        extract: suspend (Set<String>, Boolean) -> T?,
    ): T? {
        if (excluded.isEmpty()) return extract(emptySet(), false)
        val alt =
            try {
                extract(excluded, false)
            } catch (e: StreamResolveException) {
                if (e.reason != StreamResolveException.Reason.NO_PLAYABLE_STREAM) throw e
                null
            }
        if (alt != null) return alt
        Timber.tag(TAG).w("Alternate clients exhausted; retrying at low quality")
        return extract(emptySet(), true)
    }

    suspend fun prewarm() = runCatching { bundle().extractor.prewarm() }

    private suspend fun bundle(): Bundle {
        val transport = YouTube.extractionTransport()
        current?.takeIf { it.generation == transport.generation }?.let { return it }
        return mutex.withLock {
            val latest = YouTube.extractionTransport()
            current?.takeIf { it.generation == latest.generation }?.let { return@withLock it }
            runCatching { current?.cipher?.dispose() }
            cache.clear()
            val store = RemotePlayerConfigStore(latest.httpClient, configRepository, logger)
            val cipher = YouTubeCipherService(latest.httpClient, store, logger)
            val extractor =
                InnerTubeExtractor(
                    configParser = YtConfigParserImpl(latest.httpClient, latest.innerTube, store, logger).withEmbeddedFallback(),
                    cipherService = cipher,
                    innerTube = latest.innerTube,
                    logger = logger,
                )
            Bundle(latest.generation, cipher, extractor).also { current = it }
        }
    }

    private fun YtConfigParser.withEmbeddedFallback(): YtConfigParser =
        object : YtConfigParser by this {
            override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean) =
                try {
                    this@withEmbeddedFallback.fetchConfig(videoId, useLoginCookies)
                } catch (_: IllegalStateException) {
                    this@withEmbeddedFallback.fetchEmbeddedConfig(videoId, useLoginCookies = false)
                }
        }

    @Serializable
    private data class ConfigCache(
        val json: String = "",
        val cachedAtMs: Long = 0,
        val sourceUrl: String = "",
        val etag: String = "",
    )

    /** File-backed replacement for the Android SharedPreferences player-config cache. */
    private val configRepository =
        object : PlayerConfigRepository {
            private val file = AppDirs.file("player_config.json")
            private val json = Json { ignoreUnknownKeys = true }
            private var state: ConfigCache =
                runCatching { json.decodeFromString<ConfigCache>(file.readText()) }.getOrDefault(ConfigCache())

            private fun save(update: ConfigCache) {
                state = update
                runCatching { file.writeText(json.encodeToString(ConfigCache.serializer(), update)) }
            }

            override val enabled = true
            override val sourceUrl = PLAYER_CONFIG_URL
            override val defaultSourceUrl = PLAYER_CONFIG_URL
            override var cachedJson: String
                get() = state.json
                set(v) = save(state.copy(json = v))
            override var cachedAtMs: Long
                get() = state.cachedAtMs
                set(v) = save(state.copy(cachedAtMs = v))
            override var cachedSourceUrl: String
                get() = state.sourceUrl
                set(v) = save(state.copy(sourceUrl = v))
            override var cachedEtag: String
                get() = state.etag
                set(v) = save(state.copy(etag = v))
        }

    private const val PLAYER_CONFIG_URL =
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
}
