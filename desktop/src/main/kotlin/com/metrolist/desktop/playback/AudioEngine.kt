package com.metrolist.desktop.playback

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegLogCallback
import timber.log.Timber
import java.nio.ShortBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.pow

/**
 * Decodes any FFmpeg-supported stream (Opus/WebM, AAC/MP4, ...) to 48 kHz stereo PCM and plays it
 * through Java Sound. One decode thread per loaded source; a generation counter retires old ones.
 */
class AudioEngine(private val listener: Listener) {
    interface Listener {
        fun onReady(durationMs: Long)
        fun onEnded()
        fun onError(error: Throwable, httpStatus: Int?)
    }

    private val lock = Object()
    @Volatile private var generation = 0
    @Volatile private var paused = true
    @Volatile private var seekToMs: Long = -1
    @Volatile var volume: Float = 1f
    @Volatile var normalizationGain: Float = 1f

    @Volatile var positionMs: Long = 0
        private set
    @Volatile var durationMs: Long = 0
        private set
    @Volatile var isBuffering: Boolean = false
        private set

    val isPlaying get() = !paused

    // Mono copy of recent decoded audio; the visualizer reads the part that is audible right now.
    private val ring = FloatArray(RING)
    @Volatile private var ringPos = 0L
    @Volatile private var bufferedFrames = 0

    fun audibleSamples(out: FloatArray) {
        val end = ringPos - bufferedFrames
        for (i in out.indices) out[i] = ring[Math.floorMod(end - out.size + i, RING.toLong()).toInt()]
    }

    init {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR)
        runCatching { FFmpegLogCallback.setLevel(avutil.AV_LOG_ERROR) }
    }

    fun load(url: String, entry: RangeProxy.Entry?, startMs: Long, playWhenReady: Boolean) {
        val gen = synchronized(lock) { ++generation }
        paused = !playWhenReady
        seekToMs = if (startMs > 0) startMs else -1
        positionMs = startMs
        durationMs = 0
        isBuffering = true
        Thread({ decodeLoop(gen, url, entry) }, "audio-decode-$gen").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1 }.start()
    }

    fun play() {
        paused = false
        synchronized(lock) { lock.notifyAll() }
    }

    fun pause() {
        paused = true
    }

    fun seekTo(ms: Long) {
        seekToMs = ms.coerceAtLeast(0)
        positionMs = seekToMs
        synchronized(lock) { lock.notifyAll() }
    }

    fun stop() {
        synchronized(lock) {
            generation++
            lock.notifyAll()
        }
        paused = true
        isBuffering = false
    }

    private fun decodeLoop(gen: Int, url: String, entry: RangeProxy.Entry?) {
        var grabber: FFmpegFrameGrabber? = null
        var line: SourceDataLine? = null
        try {
            grabber =
                FFmpegFrameGrabber(url).apply {
                    sampleFormat = avutil.AV_SAMPLE_FMT_S16
                    sampleRate = SAMPLE_RATE
                    audioChannels = CHANNELS
                    setOption("multiple_requests", "0")
                    // Audio-only, single-stream containers: a small probe is enough and starts playback sooner.
                    setOption("probesize", "65536")
                    setOption("analyzeduration", "500000")
                    setOption("reconnect", "1")
                    setOption("reconnect_streamed", "1")
                    setOption("reconnect_delay_max", "5")
                    setOption("rw_timeout", "20000000")
                }
            val t0 = System.nanoTime()
            grabber.start()
            Timber.tag("AudioEngine").d("grabber.start took ${(System.nanoTime() - t0) / 1_000_000} ms")
            if (gen != generation) return
            durationMs = (grabber.lengthInTime / 1000).coerceAtLeast(0)
            val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, CHANNELS, true, false)
            line = AudioSystem.getSourceDataLine(format).apply { open(format, SAMPLE_RATE * CHANNELS * 2 / 4) } // ~250 ms
            val bytesPerMs = SAMPLE_RATE * CHANNELS * 2 / 1000.0
            listener.onReady(durationMs)

            var bytes = ByteArray(0)
            var lastTs = 0L
            while (gen == generation) {
                val seek = seekToMs
                if (seek >= 0) {
                    seekToMs = -1
                    isBuffering = true
                    line.stop()
                    line.flush()
                    grabber.setTimestamp(seek * 1000L, false)
                    positionMs = seek
                    lastTs = seek
                }
                if (paused) {
                    if (line.isRunning) line.stop()
                    isBuffering = false
                    synchronized(lock) {
                        if (paused && seekToMs < 0 && gen == generation) lock.wait(250)
                    }
                    continue
                }
                val frame = grabber.grabSamples() ?: break
                val samples = frame.samples?.firstOrNull() as? ShortBuffer ?: continue
                isBuffering = false
                if (frame.timestamp > 0) lastTs = frame.timestamp / 1000
                val n = samples.remaining()
                if (bytes.size < n * 2) bytes = ByteArray(n * 2)
                val gain = (volume * normalizationGain).coerceIn(0f, 4f)
                for (i in 0 until n) {
                    var s = samples.get(samples.position() + i).toInt()
                    if (gain != 1f) s = (s * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    bytes[i * 2] = (s and 0xff).toByte()
                    bytes[i * 2 + 1] = (s shr 8 and 0xff).toByte()
                }
                var p = ringPos
                for (i in 0 until n - 1 step 2) {
                    ring[(p % RING).toInt()] = (samples.get(samples.position() + i) + samples.get(samples.position() + i + 1)) / 65536f
                    p++
                }
                ringPos = p
                if (!line.isRunning) line.start()
                var off = 0
                val len = n * 2
                while (off < len && gen == generation && seekToMs < 0) {
                    off += line.write(bytes, off, minOf(len - off, line.bufferSize / 2))
                    val buffered = line.bufferSize - line.available()
                    bufferedFrames = buffered / (CHANNELS * 2) + (len - off) / (CHANNELS * 2)
                    positionMs = (lastTs + (off / bytesPerMs) - buffered / bytesPerMs).toLong().coerceAtLeast(0)
                    if (paused) break
                }
            }
            if (gen == generation) {
                line.drain()
                positionMs = durationMs
                listener.onEnded()
            }
        } catch (t: Throwable) {
            if (gen == generation) {
                Timber.tag("AudioEngine").e(t, "playback failed")
                isBuffering = false
                val status = entry?.lastStatus?.takeIf { it >= 400 }
                listener.onError(t, status)
            }
        } finally {
            runCatching { line?.stop(); line?.close() }
            runCatching { grabber?.stop(); grabber?.release() }
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000

        /** Loads the FFmpeg natives ahead of the first track (JavaCPP extracts them on first use). */
        fun preload() {
            val t0 = System.nanoTime()
            org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.avformat::class.java)
            org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.swresample::class.java)
            avutil.av_log_set_level(avutil.AV_LOG_ERROR)
            Timber.tag("AudioEngine").d("FFmpeg natives loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        const val CHANNELS = 2
        private const val RING = 32_768

        /** Mirrors the Android app's loudness normalisation: attenuate loud tracks toward -14 LUFS-ish. */
        fun normalizationFor(loudnessDb: Double?): Float =
            if (loudnessDb == null || loudnessDb <= 0) 1f else 10.0.pow(-loudnessDb / 20.0).toFloat()
    }
}
