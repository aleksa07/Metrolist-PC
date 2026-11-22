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
import androidx.compose.ui.input.key.utf16CodePoint
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
    if (alreadyRunning()) exitWithoutClassArchive()
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
        appState.window = windowState
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
        // With "close to tray" the X button only hides the window; music keeps playing and the tray icon brings it back.
        val close = { if (Settings.value.closeToTray) visible = false else quit() }
        appState.closeWindow = close
        Window(
            onCloseRequest = close,
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
                    e.isCtrlPressed && e.key == Key.DirectionRight -> Player.next().let { true }
                    e.isCtrlPressed && e.key == Key.DirectionLeft -> Player.previous().let { true }
                    e.isCtrlPressed && (e.key == Key.F || e.key == Key.L) -> runCatching { appState.searchFocus.requestFocus() }.isSuccess
                    e.isAltPressed && e.key == Key.DirectionLeft -> appState.nav.pop().let { true }
                    e.key == Key.Escape && Ui.expandedPlayer -> { Ui.expandedPlayer = false; true }
                    e.key == Key.Escape && com.metrolist.desktop.ui.Selection.tracks.isNotEmpty() -> com.metrolist.desktop.ui.Selection.clear().let { true }
                    else -> false
                }
            },
            // Only keys that no focused control used, so moving the caret in a text field never seeks.
            onKeyEvent = { e ->
                if (e.isAltPressed) return@Window false
                // A text field passes Space/L key presses on but consumes the typed character, so these act on that.
                if (e.type == KeyEventType.Unknown && !e.isCtrlPressed) {
                    return@Window when (e.utf16CodePoint) {
                        ' '.code -> Player.togglePlay().let { true }
                        'l'.code, 'L'.code -> Player.current.value?.let { com.metrolist.desktop.ui.Actions.toggleLike(it) } != null
                        else -> false
                    }
                }
                if (e.type != KeyEventType.KeyDown) return@Window false
                val seekStep = 5_000L
                when {
                    e.isCtrlPressed && e.key == Key.DirectionUp -> Player.setVolume(Settings.value.volume + 0.05f).let { true }
                    e.isCtrlPressed && e.key == Key.DirectionDown -> Player.setVolume(Settings.value.volume - 0.05f).let { true }
                    e.isCtrlPressed -> false
                    e.key == Key.DirectionRight -> Player.seekTo((Player.position.value + seekStep).coerceAtMost(Player.duration.value)).let { true }
                    e.key == Key.DirectionLeft -> Player.seekTo((Player.position.value - seekStep).coerceAtLeast(0)).let { true }
                    else -> false
                }
            },
        ) {
            window.minimumSize = java.awt.Dimension(960, 640)
            MetrolistTheme {
                val surface = androidx.compose.material3.MaterialTheme.colorScheme.surface
                androidx.compose.runtime.LaunchedEffect(surface) { com.metrolist.desktop.ui.TitleBar.apply(window, surface) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    com.metrolist.desktop.integrations.MediaControls.start(window.windowHandle)
                    com.metrolist.desktop.ui.WindowFrame.install(window)
                }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    for (ping in showRequests) {
                        show()
                        window.toFront()
                    }
                }
                App(appState)
            }
        }
    }
}

private interface CRuntime : com.sun.jna.Library {
    @Suppress("FunctionName")
    fun _exit(code: Int)
}

/**
 * The JVM writes the startup class archive (AppCDS, see build.gradle.kts) on any normal exit, even halt(),
 * and then keeps it. A second launch that only wakes the first copy would leave an archive of ~3 classes,
 * so it exits natively instead.
 */
private fun exitWithoutClassArchive(): Nothing {
    val lib = if (System.getProperty("os.name").startsWith("Windows")) "msvcrt" else "c"
    runCatching { com.sun.jna.Native.load(lib, CRuntime::class.java)._exit(0) }
    kotlin.system.exitProcess(0)
}

private val showRequests =kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
private const val HELLO = "metrolist"

/**
 * One running copy per user: a second launch asks the first to show its window, then exits.
 * shortcut: two launches in the same instant can both start, add a file lock if that shows up.
 */
private fun alreadyRunning(): Boolean {
    val portFile = AppDirs.file("instance.port")
    val loopback = java.net.InetAddress.getLoopbackAddress()
    val answered =
        runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(loopback, portFile.readText().trim().toInt()), 1000)
                s.soTimeout = 1000
                // Only our own app answers with HELLO; a stale port now owned by something else doesn't count.
                s.getInputStream().readNBytes(HELLO.length).decodeToString() == HELLO
            }
        }.getOrDefault(false)
    if (answered) return true
    val server = runCatching { java.net.ServerSocket(0, 10, loopback) }.getOrNull() ?: return false
    portFile.writeText(server.localPort.toString())
    Thread({
        while (true) {
            runCatching {
                server.accept().use { it.getOutputStream().write(HELLO.toByteArray()) }
                showRequests.trySend(Unit)
            }
        }
    }, "single-instance").apply { isDaemon = true }.start()
    return false
}
