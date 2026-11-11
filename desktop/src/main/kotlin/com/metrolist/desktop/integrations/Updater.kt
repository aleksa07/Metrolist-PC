package com.metrolist.desktop.integrations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.desktop.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/** Checks GitHub releases for a newer Metrolist PC and installs it (MSI on Windows). */
object Updater {
    private const val REPO = "aleksa07/Metrolist-PC"
    private const val WINDOWS_ASSET = "Metrolist-PC-Windows.msi"

    /** Set by jpackage in installed builds; null when running from source, where updates are skipped. */
    val currentVersion: String? = System.getProperty("jpackage.app-version")

    @Serializable
    data class Asset(val name: String, val browser_download_url: String, val size: Long = 0, val digest: String? = null)

    @Serializable
    data class Release(val tag_name: String, val html_url: String, val assets: List<Asset> = emptyList(), val draft: Boolean = false, val prerelease: Boolean = false) {
        val version get() = tag_name.removePrefix("v")
    }

    /** A newer release the user hasn't installed yet (drives the update banner). */
    var available by mutableStateOf<Release?>(null)
        private set
    var status by mutableStateOf<String?>(null)
        private set

    /** Closes the app cleanly before the installer replaces it; set by Main. */
    var quitForInstall: () -> Unit = {}

    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.split('.', '-').mapNotNull { it.toIntOrNull() }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Checks for an update; installs it right away when automatic install is on. */
    suspend fun check(userInitiated: Boolean = false) {
        val current = currentVersion
        if (current == null) {
            if (userInitiated) status = "Updates are only checked in installed builds"
            return
        }
        val release =
            runCatching {
                withContext(Dispatchers.IO) {
                    val req = HttpRequest.newBuilder(URI("https://api.github.com/repos/$REPO/releases/latest"))
                        .header("Accept", "application/vnd.github+json").header("User-Agent", "Metrolist-PC").timeout(Duration.ofSeconds(20)).build()
                    val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                    check(resp.statusCode() == 200) { "GitHub returned HTTP ${resp.statusCode()}" }
                    json.decodeFromString<Release>(resp.body())
                }
            }.getOrElse {
                if (userInitiated) status = "Couldn't check for updates: ${it.message}"
                return
            }
        if (release.draft || release.prerelease || !isNewer(release.version, current)) {
            available = null
            if (userInitiated) status = "You're on the latest version ($current)"
            return
        }
        available = release
        status = "Version ${release.version} is available"
        if (Settings.value.autoInstallUpdates && Settings.value.autoInstallTried != release.version) {
            // Remembered before installing: if the installer fails, the next start only shows the banner.
            Settings.update { it.copy(autoInstallTried = release.version) }
            install()
        }
    }

    /** Downloads and runs the new installer, then quits so it can replace the app. */
    suspend fun install() {
        val release = available ?: return
        val windows = System.getProperty("os.name").startsWith("Windows")
        val asset = release.assets.firstOrNull { it.name == WINDOWS_ASSET }
        if (!windows || asset == null) {
            runCatching { Desktop.getDesktop().browse(URI(release.html_url)) }
            status = "Download version ${release.version} from the page that opened"
            return
        }
        status = "Downloading version ${release.version}…"
        val msi =
            runCatching {
                withContext(Dispatchers.IO) {
                    val file = File(System.getProperty("java.io.tmpdir"), "MetrolistPC-${release.version}.msi")
                    val req = HttpRequest.newBuilder(URI(asset.browser_download_url)).header("User-Agent", "Metrolist-PC").build()
                    val resp = http.send(req, HttpResponse.BodyHandlers.ofFile(file.toPath()))
                    check(resp.statusCode() == 200) { "download failed (HTTP ${resp.statusCode()})" }
                    // GitHub publishes a SHA-256 for every release asset; refuse to run anything that doesn't match.
                    asset.digest?.takeIf { it.startsWith("sha256:") }?.let { expected ->
                        val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                        check(actual.equals(expected.removePrefix("sha256:"), true)) {
                            file.delete()
                            "the download didn't match its checksum"
                        }
                    }
                    file
                }
            }.getOrElse {
                status = "Update failed: ${it.message}"
                return
            }
        status = "Installing version ${release.version}…"
        val relaunch = ProcessHandle.current().info().command().orElse(null)?.takeIf { it.endsWith(".exe", true) && !it.endsWith("java.exe", true) }
        // msiexec replaces the installed app after we exit, then the app is started again.
        val script = buildString {
            append("timeout /t 2 /nobreak >nul & msiexec /i \"${msi.absolutePath}\" /passive")
            if (relaunch != null) append(" & start \"\" \"$relaunch\"")
        }
        ProcessBuilder("cmd", "/c", script).start()
        quitForInstall()
    }
}
