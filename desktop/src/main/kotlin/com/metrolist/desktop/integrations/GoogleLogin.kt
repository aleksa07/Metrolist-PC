package com.metrolist.desktop.integrations

import com.metrolist.desktop.data.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.EnumProgress
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefCookieManager
import java.awt.BorderLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.WindowConstants
import kotlin.coroutines.resume

/**
 * Google sign-in in an embedded Chromium window, like the Android app's WebView login:
 * once the browser lands on music.youtube.com signed in, its cookies become the account token.
 */
object GoogleLogin {
    private const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
    private var app: CefApp? = null

    private fun progressText(state: EnumProgress, percent: Float) =
        when (state) {
            EnumProgress.DOWNLOADING -> "Downloading sign-in browser (one time)… ${if (percent >= 0) "${percent.toInt()}%" else ""}"
            EnumProgress.EXTRACTING -> "Unpacking sign-in browser…"
            else -> "Starting sign-in browser…"
        }

    /** Returns the music.youtube.com cookie header, or null if the user closed the window. */
    suspend fun signIn(onProgress: (String) -> Unit): String? {
        val cef =
            app ?: withContext(Dispatchers.IO) {
                CefAppBuilder().run {
                    setInstallDir(AppDirs.file("jcef"))
                    setProgressHandler { state, percent -> onProgress(progressText(state, percent)) }
                    // No cache_path: an in-memory profile, so nothing but the extracted token outlives the window.
                    cefSettings.windowless_rendering_enabled = false
                    setAppHandler(object : MavenCefAppHandlerAdapter() {})
                    build()
                }
            }.also { app = it }
        onProgress("Sign in to your Google account in the window that opened")
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val client = cef.createClient()
                val browser = client.createBrowser(LOGIN_URL, false, false)
                val frame = JFrame("Sign in to YouTube Music")
                fun finish(cookie: String?) {
                    if (!cont.isActive) return
                    cont.resume(cookie)
                    javax.swing.SwingUtilities.invokeLater {
                        frame.dispose()
                        browser.close(true)
                        client.dispose()
                    }
                }
                client.addLoadHandler(
                    object : CefLoadHandlerAdapter() {
                        override fun onLoadEnd(b: CefBrowser, f: CefFrame, status: Int) {
                            if (!f.isMain || !b.url.startsWith("https://music.youtube.com")) return
                            val parts = mutableListOf<String>()
                            CefCookieManager.getGlobalManager().visitUrlCookies("https://music.youtube.com", true) { c, count, total, _ ->
                                parts += "${c.name}=${c.value}"
                                if (count == total - 1) {
                                    val header = parts.joinToString("; ")
                                    if ("SAPISID=" in header) finish(header)
                                }
                                true
                            }
                        }
                    },
                )
                frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
                frame.addWindowListener(object : WindowAdapter() {
                    override fun windowClosed(e: WindowEvent) = finish(null)
                })
                frame.contentPane.add(browser.uiComponent, BorderLayout.CENTER)
                frame.setSize(520, 720)
                frame.setLocationRelativeTo(null)
                frame.isVisible = true
                cont.invokeOnCancellation { finish(null) }
            }
        }
    }

    /** Forget the Google session of this run so the next sign-in starts clean. */
    fun signOut() {
        if (app != null) CefCookieManager.getGlobalManager().deleteCookies("", "")
    }

    fun dispose() {
        app?.dispose()
        app = null
    }
}
