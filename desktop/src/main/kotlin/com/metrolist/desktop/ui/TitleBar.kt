package com.metrolist.desktop.ui

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import timber.log.Timber

/** Colours the native Windows title bar to match the app theme (no-op on other platforms). */
object TitleBar {
    @Suppress("FunctionName")
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attr: Int, value: IntByReference, size: Int): Int
    }

    @Suppress("FunctionName")
    private interface User32 : Library {
        fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
    }

    private val isWindows = System.getProperty("os.name").startsWith("Windows")
    private val dwm by lazy { runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull() }
    private val user32 by lazy { runCatching { Native.load("user32", User32::class.java) }.getOrNull() }

    fun apply(window: ComposeWindow, background: Color) {
        if (!isWindows) return
        val dwm = dwm ?: return
        val hwnd = Pointer(window.windowHandle)
        val dark = if (background.luminance() < 0.5f) 1 else 0
        // 20 = DWMWA_USE_IMMERSIVE_DARK_MODE (Win10 20H1+); 19 is the same flag on older Win10 builds.
        val hr = dwm.DwmSetWindowAttribute(hwnd, 20, IntByReference(dark), 4)
        if (hr != 0) dwm.DwmSetWindowAttribute(hwnd, 19, IntByReference(dark), 4)
        // 35 = DWMWA_CAPTION_COLOR (Win11 only, fails harmlessly on Win10): exact app surface colour, as COLORREF 0x00BBGGRR.
        val argb = background.toArgb()
        val colorRef = (argb and 0xFF shl 16) or (argb and 0xFF00) or (argb shr 16 and 0xFF)
        dwm.DwmSetWindowAttribute(hwnd, 35, IntByReference(colorRef), 4)
        // SWP_NOSIZE|SWP_NOMOVE|SWP_NOZORDER|SWP_NOACTIVATE|SWP_FRAMECHANGED: repaint the frame now, not on next focus change.
        user32?.SetWindowPos(hwnd, null, 0, 0, 0, 0, 0x0001 or 0x0002 or 0x0004 or 0x0010 or 0x0020)
        Timber.tag("TitleBar").d("dark=$dark hr=$hr")
    }
}
