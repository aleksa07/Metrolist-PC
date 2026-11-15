package com.metrolist.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Windows only: hides the native caption so the app's own top bar sits in the title bar, while keeping the
 * native frame (resize edges, shadow, Aero Snap, double-click to maximise). Other platforms keep their title bar.
 */
object WindowFrame {
    val supported = System.getProperty("os.name").startsWith("Windows")

    /** Height of the app's top bar in window pixels; points in it that aren't [interactive] drag the window. */
    @Volatile var captionHeightPx = 0

    /** True once the native caption is hidden and the app must draw its own window buttons. */
    var active by mutableStateOf(false)
        private set

    private val interactive = ConcurrentHashMap<Any, Rect>()

    @Suppress("FunctionName")
    private interface User32 : StdCallLibrary {
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, proc: WndProc): Pointer
        fun CallWindowProcW(prev: Pointer, hwnd: Pointer, msg: Int, w: Pointer?, l: Pointer?): Pointer?
        fun ScreenToClient(hwnd: Pointer, point: Point): Boolean
        fun IsZoomed(hwnd: Pointer): Boolean
        fun GetDpiForWindow(hwnd: Pointer): Int
        fun GetSystemMetricsForDpi(index: Int, dpi: Int): Int
        fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun EnumChildWindows(parent: Pointer, cb: EnumProc, l: Pointer?): Boolean
    }

    private interface WndProc : StdCallLibrary.StdCallCallback {
        fun callback(hwnd: Pointer, msg: Int, w: Pointer?, l: Pointer?): Pointer?
    }

    private interface EnumProc : StdCallLibrary.StdCallCallback {
        fun callback(hwnd: Pointer, l: Pointer?): Boolean
    }

    @Structure.FieldOrder("x", "y")
    class Point(@JvmField var x: Int = 0, @JvmField var y: Int = 0) : Structure()

    private val user32 by lazy { runCatching { Native.load("user32", User32::class.java) }.getOrNull() }
    private var proc: WndProc? = null // strong reference: the native side only holds a pointer
    private val childProcs = ArrayList<WndProc>()

    fun install(window: ComposeWindow) {
        if (!supported || proc != null) return
        val u = user32 ?: return
        val hwnd = Pointer(window.windowHandle)
        var prev: Pointer? = null
        val p =
            object : WndProc {
                override fun callback(hwnd: Pointer, msg: Int, w: Pointer?, l: Pointer?): Pointer? =
                    runCatching { handle(u, prev!!, hwnd, msg, w, l) }.getOrElse { u.CallWindowProcW(prev!!, hwnd, msg, w, l) }
            }
        proc = p
        prev = u.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, p)
        // Compose renders into child windows that cover the frame, so they get the hit-test first.
        // Over the drag area they answer "transparent", which hands the test to the frame above.
        u.EnumChildWindows(hwnd, object : EnumProc {
            override fun callback(hwnd: Pointer, l: Pointer?): Boolean {
                var childPrev: Pointer? = null
                val cp =
                    object : WndProc {
                        override fun callback(child: Pointer, msg: Int, w: Pointer?, l: Pointer?): Pointer? {
                            if (msg == WM_NCHITTEST && l != null) {
                                val hit = runCatching { screenHit(u, Pointer(window.windowHandle), l) }.getOrDefault(HTCLIENT)
                                if (hit != HTCLIENT) return Pointer(HTTRANSPARENT.toLong())
                            }
                            return u.CallWindowProcW(childPrev!!, child, msg, w, l)
                        }
                    }
                childProcs += cp
                childPrev = u.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, cp)
                return true
            }
        }, null)
        // SWP_NOSIZE|SWP_NOMOVE|SWP_NOZORDER|SWP_NOACTIVATE|SWP_FRAMECHANGED: recompute the frame without the caption.
        u.SetWindowPos(hwnd, null, 0, 0, 0, 0, 0x0001 or 0x0002 or 0x0004 or 0x0010 or 0x0020)
        active = true
        Timber.tag("WindowFrame").d("installed")
    }

    private fun handle(u: User32, prev: Pointer, hwnd: Pointer, msg: Int, w: Pointer?, l: Pointer?): Pointer? {
        when (msg) {
            WM_NCCALCSIZE -> if (w != null && l != null) {
                // Let Windows size the frame, then give the caption strip back to the client area.
                val top = l.getInt(4)
                u.CallWindowProcW(prev, hwnd, msg, w, l)
                // A maximised window hangs its frame off-screen; keep that part out of the client area.
                l.setInt(4, top + if (u.IsZoomed(hwnd)) border(u, hwnd) else 0)
                return null
            }
            WM_NCHITTEST -> {
                val native = u.CallWindowProcW(prev, hwnd, msg, w, l)
                val hit = native?.let { Pointer.nativeValue(it).toInt() } ?: 0
                if (hit in HTLEFT..HTBOTTOMRIGHT || l == null) return native
                return Pointer(screenHit(u, hwnd, l).toLong())
            }
        }
        return u.CallWindowProcW(prev, hwnd, msg, w, l)
    }

    /** Hit-test for a WM_NCHITTEST lParam (screen coordinates), in the frame's client coordinates. */
    private fun screenHit(u: User32, hwnd: Pointer, l: Pointer): Int {
        val lp = Pointer.nativeValue(l)
        val pt = Point((lp and 0xFFFF).toShort().toInt(), (lp shr 16 and 0xFFFF).toShort().toInt())
        u.ScreenToClient(hwnd, pt)
        return hitTest(u, hwnd, pt.x, pt.y)
    }

    private fun hitTest(u: User32, hwnd: Pointer, x: Int, y: Int): Int {
        if (!u.IsZoomed(hwnd) && y in 0 until border(u, hwnd)) return HTTOP
        if (y !in 0 until captionHeightPx) return HTCLIENT
        val p = Offset(x.toFloat(), y.toFloat())
        return if (interactive.values.any { it.contains(p) }) HTCLIENT else HTCAPTION
    }

    private fun border(u: User32, hwnd: Pointer): Int {
        val dpi = u.GetDpiForWindow(hwnd).takeIf { it > 0 } ?: 96
        return u.GetSystemMetricsForDpi(SM_CYFRAME, dpi) + u.GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi)
    }

    /** Marks a control inside the top bar as clickable, so it isn't treated as the window's drag area. */
    @Composable
    fun Modifier.titleBarControl(): Modifier {
        val key = remember { Any() }
        DisposableEffect(key) { onDispose { interactive.remove(key) } }
        return onGloballyPositioned { interactive[key] = it.boundsInWindow() }
    }

    private const val GWLP_WNDPROC = -4
    private const val WM_NCCALCSIZE = 0x0083
    private const val WM_NCHITTEST = 0x0084
    private const val HTTRANSPARENT = -1
    private const val HTCLIENT = 1
    private const val HTCAPTION = 2
    private const val HTLEFT = 10
    private const val HTTOP = 12
    private const val HTBOTTOMRIGHT = 17
    private const val SM_CYFRAME = 33
    private const val SM_CXPADDEDBORDER = 92
}

/** Minimise / maximise / close in the Windows 10 caption style, for the app's own top bar. */
@Composable
fun WindowButtons(maximized: Boolean, onMinimize: () -> Unit, onToggleMaximize: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val fg = MaterialTheme.colorScheme.onSurface
    Row(modifier) {
        CaptionButton("Minimize", fg, Color.Unspecified, onMinimize) { c ->
            drawLine(c, Offset(size.width / 2 - 5.dp.toPx(), size.height / 2), Offset(size.width / 2 + 5.dp.toPx(), size.height / 2), 1.dp.toPx())
        }
        CaptionButton(if (maximized) "Restore" else "Maximize", fg, Color.Unspecified, onToggleMaximize) { c ->
            val s = 10.dp.toPx()
            val stroke = Stroke(1.dp.toPx())
            if (maximized) {
                val o = 2.dp.toPx()
                drawRect(c, Offset(size.width / 2 - s / 2 + o, size.height / 2 - s / 2 - o), androidx.compose.ui.geometry.Size(s - o, s - o), style = stroke)
                drawRect(c, Offset(size.width / 2 - s / 2, size.height / 2 - s / 2 + o), androidx.compose.ui.geometry.Size(s - o, s - o), style = stroke)
            } else {
                drawRect(c, Offset(size.width / 2 - s / 2, size.height / 2 - s / 2), androidx.compose.ui.geometry.Size(s, s), style = stroke)
            }
        }
        CaptionButton("Close", fg, Color(0xFFE81123), onClose) { c ->
            val h = 5.dp.toPx()
            val cx = size.width / 2
            val cy = size.height / 2
            drawLine(c, Offset(cx - h, cy - h), Offset(cx + h, cy + h), 1.dp.toPx())
            drawLine(c, Offset(cx - h, cy + h), Offset(cx + h, cy - h), 1.dp.toPx())
        }
    }
}

@Composable
private fun CaptionButton(label: String, fg: Color, hoverBg: Color, onClick: () -> Unit, glyph: DrawScope.(Color) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val danger = hoverBg != Color.Unspecified
    val bg = when {
        !hovered -> Color.Transparent
        danger -> hoverBg
        else -> fg.copy(alpha = 0.1f)
    }
    val color = if (hovered && danger) Color.White else fg
    with(WindowFrame) {
        Box(
            Modifier.titleBarControl().size(46.dp, 32.dp).background(bg).hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(46.dp, 32.dp)) { glyph(color) }
        }
    }
}
