package com.metrolist.desktop.playback

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegLogCallback
import timber.log.Timber
import java.nio.ShortBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
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

        /** The source given to [prepareNext] under [key] started playing without a gap. */
        fun onAdvanced(key: String, durationMs: Long)
    }

    private class Prepared(val key: String, val grabber: FFmpegFrameGrabber, val entry: RangeProxy.Entry?, val gain: Float)

    private val lock = Object()
    @Volatile private var generation = 0
    @Volatile private var paused = true
    @Volatile private var seekToMs: Long = -1
    @Volatile var volume: Float = 1f
    @Volatile var normalizationGain: Float = 1f

    /** Fade the prepared song in over the last [crossfadeMs] of the current one; 0 = gapless only. */
    @Volatile var crossfadeMs: Long = 0

    /** Tempo without pitch change (FFmpeg atempo), for podcasts. 1 = off. */
    @Volatile var speed: Float = 1f

    /** Java Sound mixer name; blank or unknown means the system default device. Applies to the next [load]. */
    @Volatile var outputDevice: String = ""
    val equalizer = Equalizer()

    @Volatile var positionMs: Long = 0
        private set
    @Volatile var durationMs: Long = 0
        private set
    @Volatile var isBuffering: Boolean = false
        private set

    val isPlaying get() = !paused

    // Next source, opened ahead of time so the decode loop can switch to it at end of stream.
    private var prepared: Prepared? = null
    private var preparingKey: String? = null

    // Mono copy of recent decoded audio; the visualizer reads the part that is audible right now.
    private val ring = FloatArray(RING)
    @Volatile private var ringPos = 0L
    @Volatile private var bufferedFrames = 0

    fun audibleSamples(out: FloatArray) {
        val end = ringPos - bufferedFrames
        for (i in out.indices) out[i] = ring[Math.floorMod(end - out.size + i, RING.toLong()).toInt()]
    }

    fun load(url: String, entry: RangeProxy.Entry?, startMs: Long, playWhenReady: Boolean) {
        clearNext()
        val gen = synchronized(lock) { ++generation }
        paused = !playWhenReady
        seekToMs = if (startMs > 0) startMs else -1
        positionMs = startMs
        durationMs = 0
        isBuffering = true
        Thread({ decodeLoop(gen, url, entry) }, "audio-decode-$gen").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1 }.start()
    }

    /** Opens [url] in the background; when the current source ends, playback continues into it without a gap. */
    fun prepareNext(key: String, url: String, entry: RangeProxy.Entry?, gain: Float) {
        val gen = generation
        synchronized(lock) { preparingKey = key }
        Thread({
            val grabber = runCatching { open(url) }.getOrNull() ?: return@Thread
            synchronized(lock) {
                if (gen == generation && preparingKey == key && prepared == null) {
                    prepared = Prepared(key, grabber, entry, gain)
                } else {
                    close(grabber)
                }
            }
        }, "audio-prepare").apply { isDaemon = true }.start()
    }

    fun clearNext() {
        synchronized(lock) {
            preparingKey = null
            prepared?.let { close(it.grabber) }
            prepared = null
        }
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
        clearNext()
        paused = true
        isBuffering = false
    }

    private fun open(url: String): FFmpegFrameGrabber =
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
            start()
        }

    private fun close(grabber: FFmpegFrameGrabber?) = runCatching { grabber?.stop(); grabber?.release() }

    private fun openLine(): SourceDataLine {
        val mixer = AudioSystem.getMixerInfo().firstOrNull { it.name == outputDevice }
        // An unplugged or renamed device falls back to the system default instead of failing playback.
        val line = mixer?.let { runCatching { AudioSystem.getSourceDataLine(FORMAT, it) }.getOrNull() } ?: AudioSystem.getSourceDataLine(FORMAT)
        return line.apply { open(FORMAT, SAMPLE_RATE * CHANNELS * 2 / 4) } // ~250 ms
    }

    private fun decodeLoop(gen: Int, url: String, firstEntry: RangeProxy.Entry?) {
        var grabber: FFmpegFrameGrabber? = null
        var line: SourceDataLine? = null
        var entry = firstEntry
        // Per decode thread: an old loop may still be winding down when the next load starts.
        var tail: Tail? = null
        var tempo: Tempo? = null
        try {
            val t0 = System.nanoTime()
            grabber = open(url)
            Timber.tag("AudioEngine").d("grabber.start took ${(System.nanoTime() - t0) / 1_000_000} ms")
            if (gen != generation) return
            durationMs = (grabber.lengthInTime / 1000).coerceAtLeast(0)
            line = openLine()
            val bytesPerMs = SAMPLE_RATE * CHANNELS * 2 / 1000.0
            listener.onReady(durationMs)

            var bytes = ByteArray(0)
            var mix = ShortArray(0)
            var lastTs = 0L
            while (gen == generation) {
                val seek = seekToMs
                if (seek >= 0) {
                    seekToMs = -1
                    isBuffering = true
                    line.stop()
                    line.flush()
                    grabber!!.setTimestamp(seek * 1000L, false)
                    positionMs = seek
                    lastTs = seek
                    tail?.close()
                    tail = null
                    tempo?.close()
                    tempo = null
                }
                // Crossfade: the prepared song becomes the main source now and the ending one fades out under it.
                val cf = crossfadeMs
                if (cf > 0 && tail == null && durationMs > cf * 2 && lastTs >= durationMs - cf) {
                    val next = synchronized(lock) { prepared.also { prepared = null; preparingKey = null } }
                    if (next != null) {
                        tail = Tail(grabber!!, normalizationGain, ((durationMs - lastTs) * SAMPLE_RATE / 1000 * CHANNELS).toInt().coerceAtLeast(1))
                        grabber = next.grabber
                        entry = next.entry
                        normalizationGain = next.gain
                        durationMs = (next.grabber.lengthInTime / 1000).coerceAtLeast(0)
                        lastTs = 0
                        positionMs = 0
                        listener.onAdvanced(next.key, durationMs)
                    }
                }
                if (paused) {
                    if (line.isRunning) line.stop()
                    isBuffering = false
                    synchronized(lock) {
                        if (paused && seekToMs < 0 && gen == generation) lock.wait(250)
                    }
                    continue
                }
                val frame = grabber!!.grabSamples()
                if (frame == null) {
                    tail?.close()
                    tail = null
                    // End of stream: continue into the prepared source on the same audio line, or finish.
                    val next = synchronized(lock) { prepared.also { prepared = null; preparingKey = null } } ?: break
                    close(grabber)
                    grabber = next.grabber
                    entry = next.entry
                    normalizationGain = next.gain
                    durationMs = (next.grabber.lengthInTime / 1000).coerceAtLeast(0)
                    lastTs = 0
                    positionMs = 0
                    listener.onAdvanced(next.key, durationMs)
                    continue
                }
                val samples = frame.samples?.firstOrNull() as? ShortBuffer ?: continue
                isBuffering = false
                if (frame.timestamp > 0) lastTs = frame.timestamp / 1000
                val n = samples.remaining()
                if (mix.size < n) mix = ShortArray(n)
                val vol = volume.coerceIn(0f, 4f)
                val norm = normalizationGain
                val eq = equalizer.active
                val t = tail?.takeIf { it.fill(n) }
                for (i in 0 until n) {
                    var f = samples.get(samples.position() + i) * norm
                    if (t != null) f = t.mix(f)
                    var s = f.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    if (eq) s = equalizer.process(s, i % CHANNELS)
                    mix[i] = (s * vol).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                if (tail?.done == true) {
                    tail?.close()
                    tail = null
                }
                // Speed: the PCM goes through atempo, so each written sample covers sp samples of the song.
                val sp = speed
                var out = mix
                var outN = n
                if (sp != 1f) {
                    val f = tempo?.takeIf { it.speed == sp } ?: Tempo(sp).also { tempo?.close(); tempo = it }
                    outN = f.process(mix, n)
                    out = f.out
                } else if (tempo != null) {
                    tempo?.close()
                    tempo = null
                }
                if (bytes.size < outN * 2) bytes = ByteArray(outN * 2)
                for (i in 0 until outN) {
                    val s = out[i].toInt()
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
                val len = outN * 2
                while (off < len && gen == generation && seekToMs < 0) {
                    off += line.write(bytes, off, minOf(len - off, line.bufferSize / 2))
                    val buffered = line.bufferSize - line.available()
                    bufferedFrames = buffered / (CHANNELS * 2) + (len - off) / (CHANNELS * 2)
                    positionMs = (lastTs + (off - buffered) / bytesPerMs * sp).toLong().coerceAtLeast(0)
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
            close(grabber)
            tail?.close()
            tail = null
            tempo?.close()
            tempo = null
        }
    }

    /** The song that is fading out during a crossfade, mixed under the new one for its last [total] samples. */
    private inner class Tail(val grabber: FFmpegFrameGrabber, val gain: Float, val total: Int) {
        private val pcm = Pcm()
        private var left = total
        private var ended = false
        val done get() = left <= 0 || (ended && pcm.size == 0)

        /** Buffers [n] samples of the old song; false when it has nothing more to give. */
        fun fill(n: Int): Boolean {
            while (!ended && pcm.size < n) {
                // A read error just ends the fade early instead of stopping playback.
                val b = runCatching { grabber.grabSamples() }.getOrNull()?.samples?.firstOrNull() as? ShortBuffer
                if (b == null) ended = true else pcm.add(b)
            }
            return !done
        }

        fun mix(f: Float): Float {
            if (left <= 0 || pcm.size == 0) return f
            val fadeIn = 1f - left.toFloat() / total
            left--
            return f * fadeIn + pcm.take() * gain * (1f - fadeIn)
        }

        fun close() = close(grabber)
    }

    private class Tempo(val speed: Float) {
        private val filter =
            org.bytedeco.javacv.FFmpegFrameFilter("atempo=$speed", CHANNELS).apply {
                sampleFormat = avutil.AV_SAMPLE_FMT_S16
                sampleRate = SAMPLE_RATE
                start()
            }
        var out = ShortArray(0)
            private set

        /** Pushes [n] samples of [pcm] and returns how many came out into [out]. */
        fun process(pcm: ShortArray, n: Int): Int {
            filter.pushSamples(0, CHANNELS, SAMPLE_RATE, avutil.AV_SAMPLE_FMT_S16, ShortBuffer.wrap(pcm, 0, n))
            var count = 0
            while (true) {
                val b = filter.pullSamples()?.samples?.firstOrNull() as? ShortBuffer ?: break
                val m = b.remaining()
                if (out.size < count + m) out = out.copyOf(maxOf(count + m, out.size * 2))
                b.get(b.position(), out, count, m)
                count += m
            }
            return count
        }

        fun close() = runCatching { filter.stop(); filter.release() }
    }

    /** Growable FIFO of PCM samples. */
    internal class Pcm {
        private var buf = ShortArray(4096)
        private var start = 0
        private var end = 0
        val size get() = end - start

        fun add(src: ShortBuffer) {
            val n = src.remaining()
            if (end + n > buf.size) {
                buf = if (size + n > buf.size) buf.copyOfRange(start, end).copyOf(maxOf(buf.size * 2, size + n)) else buf.copyInto(buf, 0, start, end)
                end -= start
                start = 0
            }
            src.get(src.position(), buf, end, n)
            end += n
        }

        fun take(): Short = buf[start++]
    }

    companion object {
        fun selfCheck() {
            val p = Pcm()
            val got = ArrayList<Short>()
            var next = 0
            repeat(50) { round ->
                val chunk = ShortArray(round * 97 % 3000 + 1) { (next++).toShort() }
                p.add(ShortBuffer.wrap(chunk))
                repeat(p.size / 2) { got += p.take() }
            }
            while (p.size > 0) got += p.take()
            check(got.size == next && got.withIndex().all { (i, v) -> v == i.toShort() }) { "pcm fifo keeps order" }
        }

        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        private const val RING = 32_768
        private val FORMAT = AudioFormat(SAMPLE_RATE.toFloat(), 16, CHANNELS, true, false)

        /** Loads the FFmpeg natives ahead of the first track (JavaCPP extracts them on first use). */
        fun preload() {
            val t0 = System.nanoTime()
            org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.avformat::class.java)
            org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.swresample::class.java)
            avutil.av_log_set_level(avutil.AV_LOG_ERROR)
            runCatching { FFmpegLogCallback.setLevel(avutil.AV_LOG_ERROR) }
            Timber.tag("AudioEngine").d("FFmpeg natives loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }

        /** Names of the playback devices Java Sound can open; the settings screen lists these. */
        fun outputDevices(): List<String> =
            AudioSystem.getMixerInfo()
                .filter { runCatching { AudioSystem.getMixer(it).isLineSupported(DataLine.Info(SourceDataLine::class.java, FORMAT)) }.getOrDefault(false) }
                .map { it.name }

        /** Mirrors the Android app's loudness normalisation: attenuate loud tracks toward -14 LUFS-ish. */
        fun normalizationFor(loudnessDb: Double?): Float =
            if (loudnessDb == null || loudnessDb <= 0) 1f else 10.0.pow(-loudnessDb / 20.0).toFloat()
    }
}
