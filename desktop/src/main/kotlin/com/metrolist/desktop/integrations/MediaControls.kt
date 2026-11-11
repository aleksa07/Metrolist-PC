package com.metrolist.desktop.integrations

import com.metrolist.desktop.data.Track
import com.metrolist.desktop.data.resized
import com.metrolist.desktop.playback.Player
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import dev.toastbits.mediasession.MediaSession
import dev.toastbits.mediasession.MediaSessionMetadata
import dev.toastbits.mediasession.MediaSessionPlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * Connects the player to the OS media controls: the Windows media overlay / lock screen (SMTC)
 * and Linux desktop media widgets (MPRIS). Media keys and those controls drive the player.
 */
object MediaControls {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private interface Backend {
        fun update(track: Track?, playing: Boolean)
    }

    fun start(hwnd: Long) {
        val os = System.getProperty("os.name").lowercase()
        val backend: Backend =
            runCatching {
                when {
                    os.startsWith("windows") -> WindowsSmtc(hwnd)
                    os.contains("linux") -> LinuxMpris()
                    else -> null // shortcut: macOS Now Playing needs an Objective-C bridge; add when it can be tested on a Mac.
                }
            }.onFailure { Timber.tag("MediaControls").w(it, "media controls unavailable") }.getOrNull() ?: return
        scope.launch {
            combine(Player.current, Player.isPlaying) { t, p -> t to p }.distinctUntilChanged().collect { (t, p) ->
                runCatching { backend.update(t, p) }.onFailure { Timber.tag("MediaControls").w(it, "update failed") }
            }
        }
    }

    private fun onButton(action: () -> Unit) = scope.launch { action() }

    /** Linux: MPRIS over D-Bus, via mediasession-kt. */
    private class LinuxMpris : Backend {
        private val session = MediaSession.create(getPositionMs = { Player.position.value }) ?: error("MPRIS unavailable")

        init {
            session.setIdentity("Metrolist PC")
            session.onPlay = { onButton(Player::play) }
            session.onPause = { onButton(Player::pause) }
            session.onPlayPause = { onButton(Player::togglePlay) }
            session.onNext = { onButton(Player::next) }
            session.onPrevious = { onButton(Player::previous) }
            session.onSetPosition = { ms -> onButton { Player.seekTo(ms) } }
            session.onSeek = { by -> onButton { Player.seekTo(Player.position.value + by) } }
            session.setEnabled(true)
        }

        override fun update(track: Track?, playing: Boolean) {
            track?.let {
                session.setMetadata(
                    MediaSessionMetadata(
                        track_id = "/com/metrolist/track/${it.id.filter(Char::isLetterOrDigit)}",
                        title = it.title,
                        artist = it.artistText,
                        album = it.album?.name,
                        art_url = it.thumbnail.resized(544),
                        length_ms = it.durationSec?.times(1000L),
                    ),
                )
            }
            session.setPlaybackStatus(
                when {
                    track == null -> MediaSessionPlaybackStatus.STOPPED
                    playing -> MediaSessionPlaybackStatus.PLAYING
                    else -> MediaSessionPlaybackStatus.PAUSED
                },
            )
        }
    }

    /**
     * Windows: SystemMediaTransportControls through WinRT's COM ABI. Method slots and IIDs come from
     * the Windows SDK headers (windows.media.h, systemmediatransportcontrolsinterop.h).
     */
    private class WindowsSmtc(hwnd: Long) : Backend {
        @Suppress("FunctionName")
        private interface Combase : Library {
            fun RoInitialize(initType: Int): Int
            fun RoGetActivationFactory(classId: Pointer, iid: Pointer, factory: PointerByReference): Int
            fun WindowsCreateString(source: WString, length: Int, string: PointerByReference): Int
            fun WindowsDeleteString(string: Pointer?): Int
        }

        private val combase = Native.load("combase", Combase::class.java)

        // All COM work happens on one MTA thread, so no window message pump is needed for callbacks.
        private val com = Executors.newSingleThreadExecutor { Thread(it, "smtc").apply { isDaemon = true } }
        private lateinit var smtc: Pointer
        private lateinit var display: Pointer
        private lateinit var uriFactory: Pointer
        private lateinit var streamRefStatics: Pointer

        // The ButtonPressed handler is a tiny COM object; its callbacks must stay referenced while registered.
        private val handlerCallbacks = mutableListOf<Callback>()
        private val handler = Memory(Native.POINTER_SIZE.toLong())
        private val handlerVtable = Memory(4L * Native.POINTER_SIZE)

        init {
            com.submit {
                combase.RoInitialize(1)
                val interop = factory("Windows.Media.SystemMediaTransportControls", IID_INTEROP)
                smtc = out { call(interop, 6, Pointer(hwnd), guid(IID_SMTC), it) }
                listOf(11, 13, 17, 25, 27).forEach { slot -> check(call(smtc, slot, 1), "enable controls") } // IsEnabled, Play, Pause, Previous, Next
                display = out { call(smtc, 8, it) }
                check(call(display, 7, 1), "set music type") // MediaPlaybackType_Music
                uriFactory = factory("Windows.Foundation.Uri", IID_URI_FACTORY)
                streamRefStatics = factory("Windows.Storage.Streams.RandomAccessStreamReference", IID_STREAM_REF_STATICS)
                registerButtons()
            }.get()
        }

        private fun registerButtons() {
            val qi = object : Callback {
                @Suppress("unused")
                fun invoke(self: Pointer, riid: Pointer, out: Pointer): Int {
                    // Only IUnknown, IAgileObject and the ButtonPressed handler interface; anything else is E_NOINTERFACE.
                    val requested = riid.getByteArray(0, 16)
                    if (handlerIids.none { it.contentEquals(requested) }) {
                        out.setPointer(0, null)
                        return 0x80004002.toInt()
                    }
                    out.setPointer(0, self)
                    return 0
                }
            }
            val refCount = object : Callback {
                @Suppress("unused")
                fun invoke(self: Pointer): Int = 1
            }
            val invoke = object : Callback {
                @Suppress("unused")
                fun invoke(self: Pointer, sender: Pointer, args: Pointer): Int {
                    val button = IntByReference()
                    val hr = call(args, 6, button)
                    Timber.tag("MediaControls").d("SMTC button ${button.value} hr=$hr")
                    if (hr == 0) {
                        when (button.value) {
                            0 -> onButton(Player::play)
                            1 -> onButton(Player::pause)
                            6 -> onButton(Player::next)
                            7 -> onButton(Player::previous)
                        }
                    }
                    return 0
                }
            }
            handlerCallbacks += listOf(qi, refCount, invoke)
            listOf(qi, refCount, refCount, invoke).forEachIndexed { i, cb ->
                handlerVtable.setPointer(i.toLong() * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(cb))
            }
            handler.setPointer(0, handlerVtable)
            check(call(smtc, 32, handler, Memory(8)), "register buttons") // add_ButtonPressed
        }

        override fun update(track: Track?, playing: Boolean) {
            com.submit {
                if (track != null) {
                    val music = out { call(display, 12, it) }
                    withHString(track.title) { call(music, 7, it) }
                    withHString(track.artistText) { call(music, 11, it) }
                    release(music)
                    track.thumbnail.resized(544)?.let { url ->
                        val uri = withHString(url) { h -> out { call(uriFactory, 6, h, it) } }
                        val ref = out { call(streamRefStatics, 7, uri, it) }
                        call(display, 11, ref)
                        release(ref)
                        release(uri)
                    }
                    check(call(display, 17), "update display")
                }
                call(smtc, 7, if (track == null) 2 else if (playing) 3 else 4) // Stopped / Playing / Paused
            }
        }

        private fun factory(className: String, iid: String): Pointer =
            withHString(className) { h -> out { combase.RoGetActivationFactory(h, guid(iid), it) } }

        private fun <T> withHString(s: String, block: (Pointer) -> T): T {
            val ref = PointerByReference()
            combase.WindowsCreateString(WString(s), s.length, ref)
            return try {
                block(ref.value)
            } finally {
                combase.WindowsDeleteString(ref.value)
            }
        }

        private fun out(call: (PointerByReference) -> Int): Pointer {
            val ref = PointerByReference()
            check(call(ref), "WinRT call")
            return ref.value
        }

        private fun check(hr: Int, what: String) {
            if (hr < 0) error("$what failed: HRESULT 0x%08x".format(hr))
        }

        private fun release(obj: Pointer) = call(obj, 2)

        companion object {
            const val IID_INTEROP = "ddb0472d-c911-4a1f-86d9-dc3d71a95f5a"
            const val IID_SMTC = "99fa3ff4-1742-42a6-902e-087d41f965ec"
            const val IID_URI_FACTORY = "44a9796f-723e-4fdf-a218-033e75b0c084"
            const val IID_STREAM_REF_STATICS = "857309dc-3fbf-4e7d-986f-ef3b1a07a964"
            private val handlerIids =
                listOf(
                    "00000000-0000-0000-c000-000000000046", // IUnknown
                    "94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90", // IAgileObject (callbacks may arrive on any thread)
                    "0557e996-7b23-5bae-aa81-ea0d671143a4", // TypedEventHandler<SMTC, ButtonPressedEventArgs>
                ).map { guid(it).getByteArray(0, 16) }

            /** Calls slot [index] of a COM object's vtable. */
            fun call(obj: Pointer, index: Int, vararg args: Any): Int =
                Function.getFunction(obj.getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE)).invokeInt(arrayOf(obj, *args))

            /** GUID in its in-memory layout (first three groups little-endian). */
            fun guid(s: String): Memory {
                val hex = s.replace("-", "")
                val bytes = ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                fun swap(from: Int, len: Int) = bytes.copyOfRange(from, from + len).reversedArray().copyInto(bytes, from)
                swap(0, 4)
                swap(4, 2)
                swap(6, 2)
                return Memory(16).apply { write(0, bytes, 0, 16) }
            }
        }
    }
}
