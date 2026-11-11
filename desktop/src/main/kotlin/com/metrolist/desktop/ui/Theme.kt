package com.metrolist.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle
import com.metrolist.desktop.data.Settings
import com.metrolist.desktop.data.ThemeMode
import com.metrolist.desktop.data.resized
import com.metrolist.desktop.playback.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Metrolist's default accent when no artwork colour is available. */
val DefaultSeed = Color(0xFFED5564)

private val seedCache = ConcurrentHashMap<String, Color>()

@Composable
fun MetrolistTheme(content: @Composable () -> Unit) {
    val settings by Settings.state.collectAsState()
    val current by Player.current.collectAsState()
    val dark =
        when (settings.theme) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
        }
    var seed by remember { mutableStateOf(DefaultSeed) }
    val art = current?.thumbnail
    LaunchedEffect(art, settings.dynamicColor) {
        seed = if (settings.dynamicColor && art != null) artworkSeed(art) ?: DefaultSeed else DefaultSeed
    }
    DynamicMaterialTheme(
        seedColor = seed,
        isDark = dark,
        isAmoled = dark && settings.pureBlack,
        style = PaletteStyle.TonalSpot,
        animate = true,
        content = content,
    )
}

/** Picks the most prominent saturated hue of an image, used as the dynamic theme seed. */
suspend fun artworkSeed(url: String): Color? {
    seedCache[url]?.let { return it }
    return withContext(Dispatchers.Default) {
        runCatching {
            val ctx = PlatformContext.INSTANCE
            val result = SingletonImageLoader.get(ctx).execute(ImageRequest.Builder(ctx).data(url.resized(120)).size(64).build())
            val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@runCatching null
            val bins = DoubleArray(36)
            val sumR = DoubleArray(36)
            val sumG = DoubleArray(36)
            val sumB = DoubleArray(36)
            val hsv = FloatArray(3)
            for (y in 0 until bitmap.height step 2) {
                for (x in 0 until bitmap.width step 2) {
                    val c = bitmap.getColor(x, y)
                    val r = c shr 16 and 0xff
                    val g = c shr 8 and 0xff
                    val b = c and 0xff
                    java.awt.Color.RGBtoHSB(r, g, b, hsv)
                    if (hsv[1] < 0.18f || hsv[2] < 0.15f) continue
                    val bin = (hsv[0] * 36).toInt().coerceIn(0, 35)
                    val w = (hsv[1] * hsv[2]).toDouble()
                    bins[bin] += w
                    sumR[bin] += r * w
                    sumG[bin] += g * w
                    sumB[bin] += b * w
                }
            }
            val best = bins.indices.maxByOrNull { bins[it] } ?: return@runCatching null
            if (bins[best] <= 0.0) return@runCatching null
            Color((sumR[best] / bins[best]).toInt(), (sumG[best] / bins[best]).toInt(), (sumB[best] / bins[best]).toInt())
        }.getOrNull()?.also { seedCache[url] = it }
    }
}
