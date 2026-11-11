package com.metrolist.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.metrolist.desktop.data.AppDirs
import com.metrolist.desktop.data.Library
import com.metrolist.desktop.data.Settings
import com.metrolist.desktop.playback.Player
import com.metrolist.desktop.playback.StreamResolver
import com.metrolist.desktop.ui.App
import com.metrolist.desktop.ui.AppState
import com.metrolist.desktop.ui.MetrolistTheme
import com.metrolist.desktop.ui.Ui
import com.metrolist.desktop.ui.applyAccountSettings
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath


fun main(args: Array<String>) {
    if ("--smoke-test" in args) {
        // The packaged GUI launcher has no console, so the self-test reports to a log file.
        val log = java.io.PrintStream(java.io.File(System.getProperty("java.io.tmpdir"), "metrolist-smoke.log"), Charsets.UTF_8)
        System.setOut(log)
        System.setErr(log)
        return runSmokeTest(null)
    }
    // Building the YouTube client (HTTP stack, JSON) takes ~0.5 s; do it off the UI thread.
    Thread({ applyAccountSettings() }, "youtube-init").start()
    Ui.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        YouTube.visitorData().onSuccess { YouTube.visitorData = it }
        com.metrolist.desktop.data.LikeSync.syncInBackground()
        if (Settings.value.checkUpdates) com.metrolist.desktop.integrations.Updater.check()
        StreamResolver.prewarm()
    }
    Player // starts loading FFmpeg natives and restores the last queue
    com.metrolist.desktop.integrations.Integrations.start()

    application {
        val windowState = rememberWindowState(size = DpSize(1360.dp, 860.dp))
        val appState = remember { AppState() }
        val current by Player.current.collectAsState()
        val playing by Player.isPlaying.collectAsState()
        val icon = painterResource("icon.png")
        var visible by remember { androidx.compose.runtime.mutableStateOf(true) }
        fun show() {
            Ui.miniPlayer = false
            visible = true
            windowState.isMinimized = false
        }
        fun quit() {
            Player.saveState()
            Settings.flush()
            Library.flush()
            com.metrolist.desktop.integrations.GoogleLogin.dispose()
            exitApplication()
            // Idle HTTP/decoder threads would keep the JVM alive; a clean exit also lets Java save the class archive.
            kotlin.system.exitProcess(0)
        }
        com.metrolist.desktop.integrations.Updater.quitForInstall = ::quit
        setSingletonImageLoaderFactory { ctx ->
            ImageLoader.Builder(ctx)
                .memoryCache { MemoryCache.Builder().maxSizeBytes(160L * 1024 * 1024).build() }
                .diskCache { DiskCache.Builder().directory(AppDirs.file("image_cache").toOkioPath()).maxSizeBytes(300L * 1024 * 1024).build() }
                .crossfade(true)
                .build()
        }
        Tray(
            icon = icon,
            tooltip = current?.let { "${it.title} – ${it.artistText}" } ?: "Metrolist",
            onAction = ::show,
            menu = {
                Item("Show Metrolist") { show() }
                current?.let { Item("${it.title} – ${it.artistText}".take(60), enabled = false) {} }
                Item(if (playing) "Pause" else "Play") { Player.togglePlay() }
                Item("Next") { Player.next() }
                Item("Previous") { Player.previous() }
                Separator()
                Item("Quit Metrolist") { quit() }
            },
        )
        if (Ui.miniPlayer) {
            // Stands in for the main window while open; closing it brings the main window back.
            Window(onCloseRequest = ::show, state = rememberWindowState(size = DpSize(440.dp, 180.dp)), title = "Metrolist", icon = icon, alwaysOnTop = true, resizable = false) {
                MetrolistTheme {
                    val surface = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer
                    androidx.compose.runtime.LaunchedEffect(surface) { com.metrolist.desktop.ui.TitleBar.apply(window, surface) }
                    com.metrolist.desktop.ui.MiniPlayer()
                }
            }
        }
        Window(
            // With "close to tray" the X button only hides the window; music keeps playing and the tray icon brings it back.
            onCloseRequest = { if (Settings.value.closeToTray) visible = false else quit() },
            visible = visible && !Ui.miniPlayer,
            state = windowState,
            title = current?.let { "${it.title} • ${it.artistText} — Metrolist" } ?: "Metrolist",
            icon = icon,
            onPreviewKeyEvent = { e ->
                if (e.type != KeyEventType.KeyDown) return@Window false
                when {
                    e.key == Key.MediaPlayPause -> Player.togglePlay().let { true }
                    e.key == Key.MediaNext -> Player.next().let { true }
                    e.key == Key.MediaPrevious -> Player.previous().let { true }
                    e.key == Key.Spacebar && !appState.searchFocused -> Player.togglePlay().let { true }
                    e.isCtrlPressed && e.key == Key.DirectionRight -> Player.next().let { true }
                    e.isCtrlPressed && e.key == Key.DirectionLeft -> Player.previous().let { true }
                    e.isCtrlPressed && (e.key == Key.F || e.key == Key.L) -> runCatching { appState.searchFocus.requestFocus() }.isSuccess
                    e.isAltPressed && e.key == Key.DirectionLeft -> appState.nav.pop().let { true }
                    e.key == Key.Escape && Ui.expandedPlayer -> { Ui.expandedPlayer = false; true }
                    else -> false
                }
            },
        ) {
            window.minimumSize = java.awt.Dimension(960, 640)
            MetrolistTheme {
                val surface = androidx.compose.material3.MaterialTheme.colorScheme.surface
                androidx.compose.runtime.LaunchedEffect(surface) { com.metrolist.desktop.ui.TitleBar.apply(window, surface) }
                androidx.compose.runtime.LaunchedEffect(Unit) { com.metrolist.desktop.integrations.MediaControls.start(window.windowHandle) }
                App(appState)
            }
        }
    }
}
