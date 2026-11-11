package com.metrolist.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.metrolist.desktop.playback.Player
import com.metrolist.desktop.playback.Spectrum

/** Spectrum bars driven by the audio that is playing right now. */
@Composable
fun Visualizer(color: Color, modifier: Modifier = Modifier) {
    val spectrum = remember { Spectrum() }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            spectrum.update(Player.audibleSamples(spectrum.samples))
            frame++
        }
    }
    Canvas(modifier) {
        frame // redraw every frame
        val n = spectrum.bandCount
        val slot = size.width / n
        val barW = slot * 0.62f
        for (i in 0 until n) {
            val h = (spectrum.bands[i] * size.height).coerceAtLeast(3f)
            drawRoundRect(
                color = color.copy(alpha = 0.45f + 0.55f * spectrum.bands[i]),
                topLeft = Offset(i * slot + (slot - barW) / 2, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2),
            )
        }
    }
}
