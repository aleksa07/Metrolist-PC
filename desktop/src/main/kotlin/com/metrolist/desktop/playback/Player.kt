package com.metrolist.desktop.playback

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

    init {
        Thread({
            runCatching { AudioEngine.preload() }.onFailure { Timber.tag("Player").e(it, "FFmpeg failed to load") }
            nativesReady.complete(Unit)
        }, "ffmpeg-preload").apply { isDaemon = true }.start()

        engine.volume = Settings.value.volume
        scope.launch {
            while (true) {
                if (engineLoaded) {
                    _position.value = engine.positionMs
                    _buffering.value = engine.isBuffering
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

    fun saveState() {
        QueueStore.update { QueueSnapshot(_queue.value, _index.value.coerceAtLeast(0), _position.value, _repeat.value.ordinal) }
        QueueStore.flush()
    }

    // ---- queue building ----

    fun playQueue(tracks: List<Track>, startIndex: Int = 0, title: String? = null, shuffle: Boolean = false) {
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
        setQueue(list, start.coerceAtLeast(0))
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

    private fun setQueue(tracks: List<Track>, start: Int) {
        _queue.value = tracks
        _index.value = start
        _current.value = tracks[start]
        load(0, true)
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
        engine.stop()
        engineLoaded = false
        proxyUrl?.let(RangeProxy::unregister)
        _error.value = null
        _buffering.value = true
        _position.value = startMs
        _duration.value = (track.durationSec ?: 0) * 1000L
        _isPlaying.value = play
        loadJob =
            scope.launch {
                nativesReady.await()
                val resolved =
                    StreamResolver.resolve(track.id, Settings.value.audioQuality).getOrElse {
                        Timber.tag("Player").e(it, "resolve failed for ${track.id}")
                        _error.value = "Can't play \"${track.title}\": ${it.message ?: it::class.simpleName}"
                        _buffering.value = false
                        _isPlaying.value = false
                        return@launch
                    }
                if (_current.value?.id != track.id) return@launch
                stream = resolved
                val (url, entry) = RangeProxy.register(resolved)
                proxyUrl = url
                engine.normalizationGain =
                    if (Settings.value.normalizeVolume) AudioEngine.normalizationFor(resolved.loudnessDb) else 1f
                engine.load(url, entry, startMs, play)
                engineLoaded = true
                if (Settings.value.recordHistory) Library.addHistory(track)
                extendRadioIfNeeded()
                prefetchNext()
            }
    }

    private fun prefetchNext() {
        val next = _queue.value.getOrNull(_index.value + 1) ?: return
        scope.launch {
            delay(5000)
            StreamResolver.resolve(next.id, Settings.value.audioQuality)
        }
    }

    // ---- engine callbacks (decode thread) ----

    override fun onReady(durationMs: Long) {
        if (durationMs > 0) _duration.value = durationMs
    }

    override fun onEnded() {
        scope.launch {
            if (_repeat.value == RepeatMode.ONE) {
                load(0, true)
            } else if (_index.value + 1 < _queue.value.size || _repeat.value == RepeatMode.ALL) {
                next()
            } else {
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
