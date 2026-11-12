package com.metrolist.desktop.playback

import com.metrolist.desktop.integrations.Spotify
import com.metrolist.desktop.data.Library
import com.metrolist.desktop.data.QueueSnapshot
import com.metrolist.desktop.data.QueueStore
import com.metrolist.desktop.data.Settings
import com.metrolist.desktop.data.Track
import com.metrolist.desktop.data.toTrack
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

enum class RepeatMode { OFF, ALL, ONE }

/** Queue + transport controller; the desktop counterpart of the Android MusicService/PlayerConnection. */
object Player : AudioEngine.Listener {
    // All queue/transport state is confined to the Swing UI thread; network work suspends off it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val engine = AudioEngine(this)
    private val nativesReady = CompletableDeferred<Unit>()

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()
    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()
    private val _current = MutableStateFlow<Track?>(null)
    val current: StateFlow<Track?> = _current.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _repeat = MutableStateFlow(RepeatMode.OFF)
    val repeat: StateFlow<RepeatMode> = _repeat.asStateFlow()
    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()
    private val _queueTitle = MutableStateFlow<String?>(null)

    /** Id of the track whose audio is decoded and ready (Listen Together reports this as "buffered"). */
    private val _readyTrackId = MutableStateFlow<String?>(null)
    val readyTrackId: StateFlow<String?> = _readyTrackId.asStateFlow()
    val queueTitle: StateFlow<String?> = _queueTitle.asStateFlow()

    private var loadJob: Job? = null
    private var radioJob: Job? = null
    private var stream: ResolvedStream? = null
    private var proxyUrl: String? = null
    private var retriedFor: String? = null
    private var engineLoaded = false
    private var radioEndpoint: WatchEndpoint? = null
    private var radioContinuation: String? = null
    private var unshuffled: List<Track>? = null

    /** Where a track's audio comes from: a downloaded file, or a stream behind the loopback proxy. */
    private class Source(val url: String, val entry: RangeProxy.Entry?, val stream: ResolvedStream?, val loudnessDb: Double?)

    // Gapless: the next track is opened shortly before the current one ends.
    private var preparedFor: String? = null
    private var preparedSource: Source? = null

    private var sleepJob: Job? = null
    private val _sleepEndsAt = MutableStateFlow<Long?>(null)
    val sleepEndsAt: StateFlow<Long?> = _sleepEndsAt.asStateFlow()
    private val _sleepAtEnd = MutableStateFlow(false)
    val sleepAtEnd: StateFlow<Boolean> = _sleepAtEnd.asStateFlow()

    init {
        Thread({
            runCatching { AudioEngine.preload() }.onFailure { Timber.tag("Player").e(it, "FFmpeg failed to load") }
            nativesReady.complete(Unit)
        }, "ffmpeg-preload").apply { isDaemon = true }.start()

        engine.volume = Settings.value.volume
        engine.outputDevice = Settings.value.outputDevice
        engine.equalizer.setGains(Settings.value.eqGains)
        scope.launch {
            while (true) {
                if (engineLoaded) {
                    _position.value = engine.positionMs
                    _buffering.value = engine.isBuffering
                    val next = expectedNext()
                    if (preparedFor != null && preparedFor != next?.id) cancelPrepared()
                    if (preparedFor == null && next != null && _isPlaying.value && _duration.value > 0 && _duration.value - _position.value < 20_000) prepare(next)
                }
                delay(150)
            }
        }
        restore()
    }

    private fun restore() {
        val snap = QueueStore.value
        if (snap.tracks.isEmpty()) return
        _queue.value = snap.tracks
        _index.value = snap.index.coerceIn(snap.tracks.indices)
        _current.value = snap.tracks[_index.value]
        _position.value = snap.positionMs
        _duration.value = (_current.value?.durationSec ?: 0) * 1000L
        _repeat.value = RepeatMode.entries.getOrElse(snap.repeat) { RepeatMode.OFF }
    }

    private fun snapshot() =
        QueueStore.update { QueueSnapshot(_queue.value, _index.value.coerceAtLeast(0), _position.value, _repeat.value.ordinal) }

    fun saveState() {
        snapshot()
        QueueStore.flush()
    }

    // ---- queue building ----

    fun playQueue(tracks: List<Track>, startIndex: Int = 0, title: String? = null, shuffle: Boolean = false, autoPlay: Boolean = true) {
        if (tracks.isEmpty()) return
        radioEndpoint = null
        radioContinuation = null
        radioJob?.cancel()
        var list = tracks.distinctBy { it.id }
        var start = tracks.getOrNull(startIndex)?.let { t -> list.indexOfFirst { it.id == t.id } } ?: 0
        unshuffled = null
        _shuffle.value = shuffle
        if (shuffle) {
            unshuffled = list
            list = list.shuffled()
            start = 0
        }
        _queueTitle.value = title
        setQueue(list, start.coerceAtLeast(0), autoPlay)
    }

    /** Plays a single song and fills the queue with its YouTube Music radio, as the Android app does. */
    fun playSong(track: Track, endpoint: WatchEndpoint? = null) {
        _shuffle.value = false
        unshuffled = null
        _queueTitle.value = null
        setQueue(listOf(track), 0)
        if (Settings.value.autoRadio) startRadio(endpoint ?: WatchEndpoint(videoId = track.id), keepCurrent = track)
    }

    /** Starts playback from a watch endpoint (artist/playlist radio, shuffle, mixes). */
    fun playEndpoint(endpoint: WatchEndpoint, title: String? = null) {
        _shuffle.value = false
        unshuffled = null
        _queueTitle.value = title
        startRadio(endpoint, keepCurrent = null)
    }

    private fun startRadio(endpoint: WatchEndpoint, keepCurrent: Track?) {
        radioJob?.cancel()
        radioEndpoint = null
        radioContinuation = null
        radioJob =
            scope.launch {
                val result = YouTube.next(endpoint).getOrElse {
                    if (keepCurrent == null) _error.value = "Couldn't start radio: ${it.message}"
                    return@launch
                }
                radioEndpoint = result.endpoint
                radioContinuation = result.continuation
                if (_queueTitle.value == null) _queueTitle.value = result.title
                val items = result.items.map { it.toTrack() }
                if (keepCurrent != null) {
                    if (_current.value?.id != keepCurrent.id) return@launch
                    val rest = items.filterNot { it.id == keepCurrent.id }
                    _queue.value = listOf(keepCurrent) + rest
                    _index.value = 0
                } else if (items.isNotEmpty()) {
                    setQueue(items, (result.currentIndex ?: 0).coerceIn(items.indices))
                }
            }
    }

    private fun extendRadioIfNeeded() {
        val endpoint = radioEndpoint ?: return
        val cont = radioContinuation ?: return
        if (_queue.value.size - _index.value > 4 || radioJob?.isActive == true) return
        radioJob =
            scope.launch {
                val result = YouTube.next(endpoint, cont).getOrNull() ?: return@launch
                radioContinuation = result.continuation
                val known = _queue.value.map { it.id }.toHashSet()
                _queue.value = _queue.value + result.items.map { it.toTrack() }.filterNot { it.id in known }
            }
    }

    fun playNext(tracks: List<Track>) {
        if (_current.value == null) return playQueue(tracks)
        val ids = tracks.map { it.id }.toSet()
        val q = _queue.value.toMutableList()
        val cur = _current.value!!
        q.removeAll { it.id in ids && it.id != cur.id }
        val i = q.indexOfFirst { it.id == cur.id }
        q.addAll(i + 1, tracks.filterNot { it.id == cur.id })
        _queue.value = q
        _index.value = i
    }

    fun addToQueue(tracks: List<Track>) {
        if (_current.value == null) return playQueue(tracks)
        val known = _queue.value.map { it.id }.toHashSet()
        _queue.value = _queue.value + tracks.filterNot { it.id in known }
    }

    fun removeFromQueue(position: Int) {
        val q = _queue.value.toMutableList()
        if (position !in q.indices || position == _index.value) return
        q.removeAt(position)
        if (position < _index.value) _index.value -= 1
        _queue.value = q
    }

    fun moveInQueue(from: Int, to: Int) {
        val q = _queue.value.toMutableList()
        if (from !in q.indices || to !in q.indices) return
        val cur = _current.value
        q.add(to, q.removeAt(from))
        _queue.value = q
        _index.value = q.indexOfFirst { it.id == cur?.id }
    }

    /** Keeps everything up to [current] and replaces what follows (used to mirror a Listen Together host). */
    fun replaceUpcoming(current: Track, upcoming: List<Track>) {
        val i = _queue.value.indexOfFirst { it.id == current.id }.takeIf { it >= 0 } ?: return
        _queue.value = _queue.value.take(i + 1) + upcoming.filter { it.id != current.id }
        _index.value = i
        radioEndpoint = null
    }

    fun clearQueue() {
        val cur = _current.value ?: return
        _queue.value = listOf(cur)
        _index.value = 0
        radioEndpoint = null
    }

    fun skipTo(position: Int) {
        if (position !in _queue.value.indices) return
        _index.value = position
        _current.value = _queue.value[position]
        load(0, true)
    }

    private fun setQueue(tracks: List<Track>, start: Int, play: Boolean = true) {
        _queue.value = tracks
        _index.value = start
        _current.value = tracks[start]
        load(0, play)
    }

    // ---- transport ----

    fun togglePlay() = if (_isPlaying.value) pause() else play()

    fun play() {
        if (_current.value == null) return
        if (!engineLoaded) return load(_position.value, true)
        engine.play()
        _isPlaying.value = true
    }

    fun pause() {
        engine.pause()
        _isPlaying.value = false
    }

    fun next() {
        val q = _queue.value
        if (q.isEmpty()) return
        val nextIndex =
            when {
                _index.value + 1 < q.size -> _index.value + 1
                _repeat.value == RepeatMode.ALL -> 0
                else -> return
            }
        skipTo(nextIndex)
    }

    fun previous() {
        if (_position.value > 3000 || _index.value <= 0) {
            seekTo(0)
            return
        }
        skipTo(_index.value - 1)
    }

    /** Fills [out] with the audio currently being heard (for the visualizer); false when nothing plays. */
    fun audibleSamples(out: FloatArray): Boolean {
        if (!engineLoaded || !_isPlaying.value) return false
        engine.audibleSamples(out)
        return true
    }

    fun seekTo(ms: Long) {
        _position.value = ms
        if (engineLoaded) engine.seekTo(ms) else if (_current.value != null) load(ms, _isPlaying.value)
    }

    /** [minutes] > 0 pauses after that long, 0 pauses when the current song ends, null turns the timer off. */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        _sleepAtEnd.value = minutes == 0
        _sleepEndsAt.value = minutes?.takeIf { it > 0 }?.let { System.currentTimeMillis() + it * 60_000L }
        if (minutes != null && minutes > 0) {
            sleepJob =
                scope.launch {
                    delay(minutes * 60_000L)
                    pause()
                    _sleepEndsAt.value = null
                }
        }
    }

    fun setOutputDevice(name: String) {
        Settings.update { it.copy(outputDevice = name) }
        engine.outputDevice = name
        if (engineLoaded) load(_position.value, _isPlaying.value) // the audio line is opened per load
    }

    fun setEqualizer(gains: List<Float>) {
        Settings.update { it.copy(eqGains = gains) }
        engine.equalizer.setGains(gains)
    }

    fun setVolume(v: Float) {
        engine.volume = v.coerceIn(0f, 1f)
        Settings.update { it.copy(volume = engine.volume) }
    }

    fun cycleRepeat() {
        _repeat.value = RepeatMode.entries[(_repeat.value.ordinal + 1) % RepeatMode.entries.size]
    }

    fun toggleShuffle() {
        val cur = _current.value ?: return
        val q = _queue.value
        if (!_shuffle.value) {
            unshuffled = q
            val rest = q.filterNot { it.id == cur.id }.shuffled()
            _queue.value = listOf(cur) + rest
            _index.value = 0
            _shuffle.value = true
        } else {
            val original = unshuffled ?: q
            _queue.value = original
            _index.value = original.indexOfFirst { it.id == cur.id }.coerceAtLeast(0)
            _shuffle.value = false
            unshuffled = null
        }
    }

    fun clearError() {
        _error.value = null
    }

    private fun load(startMs: Long, play: Boolean) {
        val track = _current.value ?: return
        loadJob?.cancel()
        cancelPrepared()
        engine.stop()
        engineLoaded = false
        _readyTrackId.value = null
        proxyUrl?.let(RangeProxy::unregister)
        _error.value = null
        _buffering.value = true
        _position.value = startMs
        _duration.value = (track.durationSec ?: 0) * 1000L
        _isPlaying.value = play
        snapshot() // survives a shutdown or crash that never reaches quit()
        loadJob =
            scope.launch {
                nativesReady.await()
                fun fail(e: Throwable) {
                    Timber.tag("Player").e(e, "resolve failed for ${track.id}")
                    _error.value = "Can't play \"${track.title}\": ${e.message ?: e::class.simpleName}"
                    _buffering.value = false
                    _isPlaying.value = false
                }
                val playing = resolved(track).getOrElse { return@launch fail(it) }
                val src = source(playing).getOrElse { return@launch fail(it) }
                if (_current.value?.id != playing.id) return@launch RangeProxy.unregister(src.url)
                stream = src.stream
                proxyUrl = src.url
                engine.normalizationGain = gainFor(src)
                engine.load(src.url, src.entry, startMs, play)
                engineLoaded = true
                if (Settings.value.recordHistory) Library.addHistory(playing)
                extendRadioIfNeeded()
                prefetchNext()
            }
    }

    /** Spotify songs are matched to YouTube Music only when they are about to play; the match replaces them in the queue. */
    private suspend fun resolved(track: Track): Result<Track> {
        if (!Spotify.isPlaceholder(track)) return Result.success(track)
        val yt = Spotify.match(track).getOrElse { return Result.failure(it) }
        fun List<Track>.swap() = map { if (it.id == track.id) yt else it }
        _queue.value = _queue.value.swap()
        unshuffled = unshuffled?.swap()
        if (_current.value?.id == track.id) _current.value = yt
        return Result.success(yt)
    }

    private suspend fun source(track: Track): Result<Source> {
        com.metrolist.desktop.data.Downloads.entry(track.id)?.let { d ->
            return Result.success(Source(com.metrolist.desktop.data.Downloads.file(track.id).absolutePath, null, null, d.loudnessDb))
        }
        if (com.metrolist.desktop.integrations.SoundCloud.isTrack(track)) return com.metrolist.desktop.integrations.SoundCloud.stream(track).map { Source(it, null, null, null) }
        return StreamResolver.resolve(track.id, Settings.value.audioQuality).map {
            val (url, entry) = RangeProxy.register(it)
            Source(url, entry, it, it.loudnessDb)
        }
    }

    private fun gainFor(src: Source) = if (Settings.value.normalizeVolume) AudioEngine.normalizationFor(src.loudnessDb) else 1f

    /** The track that follows the current one if nothing is touched (same rules as [onEnded]). */
    private fun expectedNext(): Track? =
        when {
            _sleepAtEnd.value -> null
            _repeat.value == RepeatMode.ONE -> _current.value
            else -> _queue.value.getOrNull(_index.value + 1) ?: _queue.value.firstOrNull().takeIf { _repeat.value == RepeatMode.ALL }
        }

    private fun prepare(next: Track) {
        preparedFor = next.id
        scope.launch {
            // A failed resolve leaves preparedFor set: no retry storm, the track just loads normally at the end.
            // A Spotify song comes back as a different track; the poll loop then prepares that one.
            if (resolved(next).getOrNull()?.id != next.id) return@launch
            val src = source(next).getOrNull() ?: return@launch
            if (preparedFor != next.id) return@launch RangeProxy.unregister(src.url)
            preparedSource = src
            engine.prepareNext(next.id, src.url, src.entry, gainFor(src))
        }
    }

    private fun cancelPrepared() {
        preparedFor = null
        preparedSource?.let { RangeProxy.unregister(it.url) }
        preparedSource = null
        engine.clearNext()
    }

    private fun prefetchNext() {
        val next = _queue.value.getOrNull(_index.value + 1)?.takeUnless { ':' in it.id } ?: return
        scope.launch {
            delay(5000)
            StreamResolver.resolve(next.id, Settings.value.audioQuality)
        }
    }

    // ---- engine callbacks (decode thread) ----

    override fun onReady(durationMs: Long) {
        if (durationMs > 0) _duration.value = durationMs
        scope.launch { _readyTrackId.value = _current.value?.id }
    }

    override fun onAdvanced(key: String, durationMs: Long) {
        scope.launch {
            val src = preparedSource
            val next = expectedNext()
            // The queue changed in the last moment: fall back to loading the right track.
            if (src == null || preparedFor != key || next?.id != key) return@launch onEnded()
            proxyUrl?.let(RangeProxy::unregister)
            proxyUrl = src.url
            stream = src.stream
            preparedFor = null
            preparedSource = null
            if (_repeat.value != RepeatMode.ONE) _index.value = if (_index.value + 1 < _queue.value.size) _index.value + 1 else 0
            _current.value = next
            _position.value = 0
            _duration.value = durationMs.takeIf { it > 0 } ?: ((next.durationSec ?: 0) * 1000L)
            _readyTrackId.value = next.id
            if (Settings.value.recordHistory) Library.addHistory(next)
            snapshot()
            extendRadioIfNeeded()
            prefetchNext()
        }
    }

    override fun onEnded() {
        scope.launch {
            val sleep = _sleepAtEnd.value
            _sleepAtEnd.value = false
            if (!sleep && _repeat.value == RepeatMode.ONE) {
                load(0, true)
            } else if (!sleep && (_index.value + 1 < _queue.value.size || _repeat.value == RepeatMode.ALL)) {
                next()
            } else {
                engine.stop() // also ends a gapless hand-over that was no longer wanted
                _isPlaying.value = false
                engineLoaded = false
                _position.value = 0
            }
        }
    }

    override fun onError(error: Throwable, httpStatus: Int?) {
        scope.launch {
            val failed = stream
            val track = _current.value
            // Expired or client-rejected URLs: re-resolve once with that client excluded, resuming in place.
            if (failed != null && track != null && retriedFor != track.id) {
                retriedFor = track.id
                StreamResolver.markFailed(failed)
                load(_position.value, true)
                return@launch
            }
            _error.value = "Playback error${httpStatus?.let { " (HTTP $it)" }.orEmpty()}: ${error.message ?: error::class.simpleName}"
            _isPlaying.value = false
            engineLoaded = false
        }
    }
}
