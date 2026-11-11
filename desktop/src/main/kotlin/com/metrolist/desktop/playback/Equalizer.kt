package com.metrolist.desktop.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** 5-band peaking equaliser (RBJ biquads) for interleaved stereo PCM at the engine's sample rate. */
class Equalizer {
    private class Band(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    @Volatile private var bands: List<Band> = emptyList()

    // Filter memory per band and channel: x[n-1], x[n-2], y[n-1], y[n-2].
    private val state = Array(FREQS.size) { Array(AudioEngine.CHANNELS) { DoubleArray(4) } }

    /** False when every band is flat, so the decode loop can skip the filter entirely. */
    val active get() = bands.isNotEmpty()

    fun setGains(db: List<Float>) {
        bands = if (db.all { it == 0f }) emptyList() else FREQS.mapIndexed { i, f -> band(f, db.getOrElse(i) { 0f }.coerceIn(-MAX_DB, MAX_DB)) }
    }

    fun process(sample: Int, channel: Int): Int {
        var x = sample.toDouble()
        val current = bands
        for (i in current.indices) {
            val b = current[i]
            val s = state[i][channel]
            val y = b.b0 * x + b.b1 * s[0] + b.b2 * s[1] - b.a1 * s[2] - b.a2 * s[3]
            s[1] = s[0]
            s[0] = x
            s[3] = s[2]
            s[2] = y
            x = y
        }
        return x.toInt()
    }

    companion object {
        const val MAX_DB = 12f
        val FREQS = listOf(60.0, 230.0, 910.0, 3600.0, 14000.0)
        val PRESETS =
            listOf(
                "Flat" to listOf(0f, 0f, 0f, 0f, 0f),
                "Bass boost" to listOf(6f, 3f, 0f, 0f, 0f),
                "Treble boost" to listOf(0f, 0f, 0f, 3f, 6f),
                "Vocal" to listOf(-2f, 0f, 4f, 3f, 0f),
                "Rock" to listOf(4f, 2f, -1f, 2f, 4f),
            )

        private fun band(freq: Double, gainDb: Float): Band {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2 * PI * freq / AudioEngine.SAMPLE_RATE
            val alpha = sin(w0) / 2 // Q = 1: each band covers roughly its own two octaves
            val a0 = 1 + alpha / a
            return Band((1 + alpha * a) / a0, -2 * cos(w0) / a0, (1 - alpha * a) / a0, -2 * cos(w0) / a0, (1 - alpha / a) / a0)
        }

        /** Peak level of a sine at [freq] after the equaliser has settled. */
        private fun response(eq: Equalizer, freq: Double): Double {
            var peak = 0.0
            for (n in 0 until AudioEngine.SAMPLE_RATE) {
                val out = eq.process((1000 * sin(2 * PI * freq * n / AudioEngine.SAMPLE_RATE)).toInt(), 0)
                if (n > AudioEngine.SAMPLE_RATE / 2) peak = maxOf(peak, abs(out).toDouble())
            }
            return peak
        }

        fun selfCheck() {
            val eq = Equalizer()
            check(!eq.active) { "flat equaliser must be bypassed" }
            eq.setGains(listOf(0f, 0f, 12f, 0f, 0f))
            // +12 dB is x3.98 at the band centre; a band two steps away must stay close to untouched.
            check(response(eq, 910.0) in 3600.0..4400.0) { "boosted band: ${response(eq, 910.0)}" }
            check(response(Equalizer().apply { setGains(listOf(0f, 0f, 12f, 0f, 0f)) }, 60.0) in 900.0..1150.0) { "far band changed" }
        }
    }
}
