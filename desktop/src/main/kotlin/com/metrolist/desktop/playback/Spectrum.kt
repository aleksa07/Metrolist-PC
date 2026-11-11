package com.metrolist.desktop.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** FFT spectrum analyser for the visualizer: log-spaced bands, 0..1, with fall-off smoothing. */
class Spectrum(private val size: Int = 2048, val bandCount: Int = 48) {
    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val window = FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / (size - 1))).toFloat() }
    val samples = FloatArray(size)
    val bands = FloatArray(bandCount)

    // Band edges from 40 Hz to 16 kHz on a log scale, as FFT bin indices.
    private val edges =
        IntArray(bandCount + 1) { i ->
            val hz = 40.0 * (16_000.0 / 40.0).pow(i.toDouble() / bandCount)
            (hz * size / AudioEngine.SAMPLE_RATE).toInt().coerceIn(1, size / 2)
        }

    /** Analyses [samples]; when [active] is false the bars just fall back to zero. */
    fun update(active: Boolean) {
        if (!active) {
            for (b in bands.indices) bands[b] *= 0.85f
            return
        }
        for (i in 0 until size) {
            re[i] = samples[i] * window[i]
            im[i] = 0f
        }
        fft(re, im)
        for (b in 0 until bandCount) {
            var peak = 0f
            for (k in edges[b]..maxOf(edges[b], edges[b + 1] - 1)) {
                val m = sqrt(re[k] * re[k] + im[k] * im[k])
                if (m > peak) peak = m
            }
            val db = 20 * log10(peak / (size / 4f) + 1e-9f)
            val v = ((db + 60f) / 60f).coerceIn(0f, 1f)
            bands[b] = if (v > bands[b]) v else bands[b] * 0.85f + v * 0.15f
        }
    }

    companion object {
        fun fft(re: FloatArray, im: FloatArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    re[i] = re[j].also { re[j] = re[i] }
                    im[i] = im[j].also { im[j] = im[i] }
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                for (i in 0 until n step len) {
                    for (k in 0 until len / 2) {
                        val wr = cos(ang * k).toFloat()
                        val wi = sin(ang * k).toFloat()
                        val a = i + k
                        val b = a + len / 2
                        val xr = re[b] * wr - im[b] * wi
                        val xi = re[b] * wi + im[b] * wr
                        re[b] = re[a] - xr
                        im[b] = im[a] - xi
                        re[a] += xr
                        im[a] += xi
                    }
                }
                len = len shl 1
            }
        }

        /** Self-check: a 1 kHz tone must light up the band that contains 1 kHz, and only that region. */
        fun selfCheck() {
            val s = Spectrum()
            for (i in s.samples.indices) s.samples[i] = sin(2 * PI * 1000 * i / AudioEngine.SAMPLE_RATE).toFloat() * 0.5f
            s.update(true)
            val loudest = s.bands.indices.maxBy { s.bands[it] }
            val hzLow = 40.0 * (16_000.0 / 40.0).pow(loudest.toDouble() / s.bandCount)
            val hzHigh = 40.0 * (16_000.0 / 40.0).pow((loudest + 1.0) / s.bandCount)
            check(1000.0 in hzLow * 0.9..hzHigh * 1.1) { "spectrum peak at $hzLow–$hzHigh Hz, expected 1 kHz" }
            check(s.bands[s.bandCount - 1] < 0.2f) { "high band should be quiet" }
        }
    }
}
