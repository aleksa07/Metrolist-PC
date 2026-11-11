package com.metrolist.desktop.integrations

import com.metrolist.desktop.data.AlbumRef
import com.metrolist.desktop.data.ArtistRef
import com.metrolist.desktop.data.Track
import com.metrolist.desktop.playback.Player
import com.metrolist.music.listentogether.BufferCompletePayload
import com.metrolist.music.listentogether.BufferReadyPayload
import com.metrolist.music.listentogether.CreateRoomPayload
import com.metrolist.music.listentogether.ErrorPayload
import com.metrolist.music.listentogether.HostChangedPayload
import com.metrolist.music.listentogether.JoinApprovedPayload
import com.metrolist.music.listentogether.JoinRejectedPayload
import com.metrolist.music.listentogether.JoinRequestPayload
import com.metrolist.music.listentogether.JoinRoomPayload
import com.metrolist.music.listentogether.KickUserPayload
import com.metrolist.music.listentogether.KickedPayload
import com.metrolist.music.listentogether.ListenTogetherServers
import com.metrolist.music.listentogether.MessageCodec
import com.metrolist.music.listentogether.MessageTypes
import com.metrolist.music.listentogether.PingPayload
import com.metrolist.music.listentogether.PlaybackActionPayload
import com.metrolist.music.listentogether.PlaybackActions
import com.metrolist.music.listentogether.PongPayload
import com.metrolist.music.listentogether.RejectJoinPayload
import com.metrolist.music.listentogether.ApproveJoinPayload
import com.metrolist.music.listentogether.RoomCreatedPayload
import com.metrolist.music.listentogether.RoomState
import com.metrolist.music.listentogether.ServerClock
import com.metrolist.music.listentogether.SyncStatePayload
import com.metrolist.music.listentogether.TrackInfo
import com.metrolist.music.listentogether.UserInfo
import com.metrolist.music.listentogether.UserJoinedPayload
import com.metrolist.music.listentogether.UserLeftPayload
import com.metrolist.music.listentogether.copy
import com.metrolist.music.listentogether.capturedAtServerTimeOrNull
import com.metrolist.music.listentogether.currentTrackOrNull
import com.metrolist.music.listentogether.positionOrNull
import com.metrolist.music.listentogether.queueTitleOrNull
import com.metrolist.music.listentogether.serverTimeOrNull
import com.metrolist.music.listentogether.trackIdOrNull
import com.metrolist.music.listentogether.trackInfoOrNull
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicLong

/**
 * Metrolist Listen Together for the desktop: the same rooms, servers and protobuf protocol as the
 * Android app, so PCs and phones can share a room. The host broadcasts what it plays; guests follow.
 */
object ListenTogether {
    enum class Role { NONE, HOST, GUEST }

    data class State(
        val connecting: Boolean = false,
        val role: Role = Role.NONE,
        val userId: String? = null,
        val room: RoomState? = null,
        val joinRequests: List<JoinRequestPayload> = emptyList(),
        val waitingFor: List<String> = emptyList(),
        val message: String? = null,
    ) {
        val inRoom get() = role != Role.NONE && room != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val codec = MessageCodec(compressionEnabled = true)
    private val clock = ServerClock { System.nanoTime() / 1_000_000 }
    private val pingSeq = AtomicLong()
    private var socket: WebSocket? = null
    private var pingJob: Job? = null
    private var hostJob: Job? = null
    private var revision = 0L

    // Guest: the seek/play to apply once the server says every member has buffered the track.
    private var pendingSync: Triple<String, Long, Boolean>? = null
    private var bufferCompleteFor: String? = null

    val serverUrl: String get() = ListenTogetherServers.defaultServerUrl

    fun createRoom(username: String) = connectThen { send(MessageTypes.CREATE_ROOM, CreateRoomPayload(username)) }

    fun joinRoom(code: String, username: String) =
        connectThen { send(MessageTypes.JOIN_ROOM, JoinRoomPayload(code.trim().uppercase(), username)) }

    fun leave() {
        send(MessageTypes.LEAVE_ROOM, null)
        disconnect(null)
    }

    fun approve(userId: String) {
        send(MessageTypes.APPROVE_JOIN, ApproveJoinPayload(userId))
        _state.update { s -> s.copy(joinRequests = s.joinRequests.filter { it.userId != userId }) }
    }

    fun reject(userId: String) {
        send(MessageTypes.REJECT_JOIN, RejectJoinPayload(userId, "Rejected by host"))
        _state.update { s -> s.copy(joinRequests = s.joinRequests.filter { it.userId != userId }) }
    }

    fun kick(userId: String) = send(MessageTypes.KICK_USER, KickUserPayload(userId, "Removed by host"))

    fun requestSync() = send(MessageTypes.REQUEST_SYNC, null)

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ---- connection ----

    private fun connectThen(action: () -> Unit) {
        if (socket != null) return action()
        _state.update { it.copy(connecting = true, message = null) }
        clock.reset()
        HttpClient.newHttpClient().newWebSocketBuilder()
            .header("User-Agent", "Metrolist PC")
            .buildAsync(URI(serverUrl), Listener())
            .whenComplete { ws, error ->
                scope.launch {
                    if (error != null) {
                        _state.update { it.copy(connecting = false, message = "Couldn't reach the Listen Together server") }
                        return@launch
                    }
                    socket = ws
                    _state.update { it.copy(connecting = false) }
                    startPings()
                    action()
                }
            }
    }

    private fun disconnect(message: String?) {
        pingJob?.cancel()
        hostJob?.cancel()
        socket?.abort()
        socket = null
        pendingSync = null
        _state.value = State(message = message)
    }

    private fun startPings() {
        pingJob?.cancel()
        pingJob =
            scope.launch {
                repeat(3) {
                    ping()
                    delay(250)
                }
                while (true) {
                    delay(25_000)
                    ping()
                }
            }
    }

    private fun ping() = send(MessageTypes.PING, PingPayload(clientTime = System.nanoTime() / 1_000_000, sequence = pingSeq.incrementAndGet()))

    private fun send(type: String, payload: MessageLite?) {
        val ws = socket ?: return
        runCatching { ws.sendBinary(ByteBuffer.wrap(codec.encode(type, payload)), true) }
            .onFailure { Timber.tag("ListenTogether").w(it, "send $type failed") }
    }

    private class Listener : WebSocket.Listener {
        private val buffer = ByteArrayOutputStream()

        override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            val bytes = ByteArray(data.remaining()).also(data::get)
            buffer.write(bytes)
            if (last) {
                val message = buffer.toByteArray()
                buffer.reset()
                scope.launch { runCatching { handle(message) }.onFailure { Timber.tag("ListenTogether").w(it, "bad message") } }
            }
            ws.request(1)
            return null
        }

        override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            scope.launch { if (socket === ws) disconnect(if (_state.value.inRoom) "Disconnected from the room" else null) }
            return null
        }

        override fun onError(ws: WebSocket, error: Throwable) {
            scope.launch { if (socket === ws) disconnect("Connection lost: ${error.message}") }
        }
    }

    // ---- incoming messages ----

    private fun handle(data: ByteArray) {
        val (type, bytes) = codec.decode(data)
        val payload = codec.decodePayload(type, bytes)
        when (type) {
            MessageTypes.ROOM_CREATED -> {
                val p = payload as RoomCreatedPayload
                val me = UserInfo(p.userId, "You", true)
                _state.update {
                    it.copy(role = Role.HOST, userId = p.userId, room = RoomState(p.roomCode, p.userId, listOf(me), isPlaying = false, position = 0, lastUpdate = 0))
                }
                startHosting()
            }
            MessageTypes.JOIN_REQUEST -> {
                val p = payload as JoinRequestPayload
                _state.update { s -> s.copy(joinRequests = s.joinRequests.filter { it.userId != p.userId } + p) }
            }
            MessageTypes.JOIN_APPROVED -> {
                val p = payload as JoinApprovedPayload
                _state.update { it.copy(role = Role.GUEST, userId = p.userId, room = p.state) }
                revision = p.state.revision
                applyState(p.state.currentTrackOrNull, p.state.isPlaying, p.state.position, p.state.lastUpdate, p.state.queueList, null)
            }
            MessageTypes.JOIN_REJECTED -> disconnect("The host declined your request${(payload as? JoinRejectedPayload)?.reason?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
            MessageTypes.USER_JOINED -> {
                val p = payload as UserJoinedPayload
                updateRoom { r -> r.copy(users = r.usersList.filter { it.userId != p.userId } + UserInfo(p.userId, p.username, false)) }
                if (_state.value.role == Role.HOST) broadcastCurrent()
            }
            MessageTypes.USER_LEFT -> {
                val p = payload as UserLeftPayload
                updateRoom { r -> r.copy(users = r.usersList.filter { it.userId != p.userId }) }
            }
            MessageTypes.HOST_CHANGED -> {
                val p = payload as HostChangedPayload
                updateRoom { r -> r.copy(hostId = p.newHostId, users = r.usersList.map { it.copy(isHost = it.userId == p.newHostId) }) }
                val nowHost = p.newHostId == _state.value.userId
                _state.update { it.copy(role = if (nowHost) Role.HOST else Role.GUEST) }
                if (nowHost) startHosting() else hostJob?.cancel()
            }
            MessageTypes.KICKED -> disconnect("You were removed from the room${(payload as? KickedPayload)?.reason?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
            MessageTypes.SYNC_PLAYBACK -> if (_state.value.role == Role.GUEST) applyAction(payload as PlaybackActionPayload)
            MessageTypes.SYNC_STATE -> {
                val p = payload as SyncStatePayload
                if (_state.value.role == Role.GUEST && acceptRevision(p.revision)) {
                    applyState(p.currentTrackOrNull, p.isPlaying, p.position, p.lastUpdate, p.queueList, null)
                }
            }
            MessageTypes.BUFFER_WAIT -> _state.update { it.copy(waitingFor = (payload as com.metrolist.music.listentogether.BufferWaitPayload).waitingForList) }
            MessageTypes.BUFFER_COMPLETE -> {
                _state.update { it.copy(waitingFor = emptyList()) }
                bufferCompleteFor = (payload as BufferCompletePayload).trackId
                applyPendingIfReady()
            }
            MessageTypes.PONG -> {
                val p = payload as PongPayload
                val first = clock.recordPong(p.clientTime, p.serverReceiveTime, p.serverSendTime)
                if (first && _state.value.role == Role.GUEST) requestSync()
            }
            MessageTypes.ERROR -> _state.update { it.copy(message = (payload as? ErrorPayload)?.message ?: "Server error") }
        }
    }

    private fun updateRoom(transform: (RoomState) -> RoomState) = _state.update { s -> s.copy(room = s.room?.let(transform)) }

    private fun acceptRevision(r: Long): Boolean {
        if (r <= 0L) return true
        if (r < revision) return false
        revision = r
        return true
    }

    // ---- guest: follow the host ----

    private fun TrackInfo.toTrack() =
        Track(
            id = id,
            title = title,
            artists = artist.split(", ").filter { it.isNotBlank() }.map { ArtistRef(it) },
            album = album.takeIf { it.isNotBlank() }?.let { AlbumRef(it, "") },
            durationSec = (duration / 1000).toInt().takeIf { it > 0 },
            thumbnail = thumbnail.takeIf { it.isNotBlank() },
        )

    private fun applyAction(a: PlaybackActionPayload) {
        if (!acceptRevision(a.revision)) return
        val at = a.capturedAtServerTimeOrNull ?: a.serverTimeOrNull
        when (a.action) {
            PlaybackActions.CHANGE_TRACK -> a.trackInfoOrNull?.let { applyState(it, false, 0, at, a.queueList, a.queueTitleOrNull) }
            PlaybackActions.PLAY -> {
                val pos = clock.positionAt(a.positionOrNull ?: Player.position.value, at, isPlaying = true)
                if (!followPending(a.trackIdOrNull, pos, true)) {
                    Player.seekTo(pos)
                    Player.play()
                }
            }
            PlaybackActions.PAUSE -> {
                val pos = a.positionOrNull ?: Player.position.value
                if (!followPending(a.trackIdOrNull, pos, false)) {
                    Player.pause()
                    Player.seekTo(pos)
                }
            }
            PlaybackActions.SEEK -> Player.seekTo(clock.positionAt(a.positionOrNull ?: 0, at, Player.isPlaying.value))
            PlaybackActions.SYNC_QUEUE -> {
                val current = Player.current.value ?: return
                Player.replaceUpcoming(current, a.queueList.map { it.toTrack() })
            }
        }
    }

    /** While the guest is still buffering a new track, play/pause updates the deferred start instead. */
    private fun followPending(trackId: String?, position: Long, play: Boolean): Boolean {
        val pending = pendingSync ?: return false
        if (trackId != null && trackId != pending.first) return false
        pendingSync = Triple(pending.first, position, play)
        applyPendingIfReady()
        return true
    }

    private fun applyState(track: TrackInfo?, playing: Boolean, position: Long, at: Long?, queue: List<TrackInfo>, title: String?) {
        track ?: return
        val target = clock.positionAt(position, at, playing)
        if (Player.current.value?.id == track.id) {
            Player.seekTo(target)
            if (playing) Player.play() else Player.pause()
            return
        }
        val upcoming = queue.map { it.toTrack() }.filter { it.id != track.id }
        Player.playQueue(listOf(track.toTrack()) + upcoming, 0, title ?: "Listen Together", autoPlay = false)
        pendingSync = Triple(track.id, target, playing)
        bufferCompleteFor = null
        scope.launch {
            withTimeoutOrNull(20_000) { Player.readyTrackId.first { it == track.id } }
            if (pendingSync?.first == track.id) send(MessageTypes.BUFFER_READY, BufferReadyPayload(track.id))
        }
    }

    private fun applyPendingIfReady() {
        val (id, position, play) = pendingSync ?: return
        if (bufferCompleteFor != id || Player.readyTrackId.value != id) return
        pendingSync = null
        Player.seekTo(position)
        if (play) Player.play() else Player.pause()
    }

    // ---- host: broadcast what this PC plays ----

    private fun Track.toInfo() =
        TrackInfo(
            id = id,
            title = title,
            artist = artistText,
            album = album?.name,
            duration = (durationSec ?: 180) * 1000L,
            thumbnail = thumbnail,
        )

    private fun action(name: String, track: Track? = null, position: Long? = null, queue: List<Track>? = null) =
        send(
            MessageTypes.PLAYBACK_ACTION,
            PlaybackActionPayload(
                name,
                trackId = track?.id,
                position = position,
                trackInfo = if (name == PlaybackActions.CHANGE_TRACK) track?.toInfo() else null,
                queue = queue?.map { it.toInfo() },
                queueTitle = if (name == PlaybackActions.CHANGE_TRACK) Player.queueTitle.value else null,
                capturedAtServerTime = clock.now().takeIf { position != null },
            ),
        )

    private fun upcoming(): List<Track> = Player.queue.value.drop(Player.index.value + 1).take(50)

    private fun broadcastCurrent() {
        val t = Player.current.value ?: return
        action(PlaybackActions.CHANGE_TRACK, t, queue = upcoming())
        action(if (Player.isPlaying.value) PlaybackActions.PLAY else PlaybackActions.PAUSE, t, Player.position.value)
    }

    private fun startHosting() {
        hostJob?.cancel()
        hostJob =
            scope.launch {
                var lastId: String? = null
                var lastPlaying = false
                var lastPos = 0L
                var lastTick = System.currentTimeMillis()
                var lastQueue: List<String> = emptyList()
                while (true) {
                    val t = Player.current.value
                    val playing = Player.isPlaying.value
                    val pos = Player.position.value
                    val now = System.currentTimeMillis()
                    val queueIds = upcoming().map { it.id }
                    if (t != null) {
                        when {
                            t.id != lastId -> {
                                action(PlaybackActions.CHANGE_TRACK, t, queue = upcoming())
                                if (playing) action(PlaybackActions.PLAY, t, pos)
                                lastQueue = queueIds
                            }
                            playing != lastPlaying -> action(if (playing) PlaybackActions.PLAY else PlaybackActions.PAUSE, t, pos)
                            // A jump bigger than normal playback progress means the host seeked.
                            kotlin.math.abs(pos - (lastPos + if (playing) now - lastTick else 0)) > 2500 -> action(PlaybackActions.SEEK, t, pos)
                            queueIds != lastQueue -> {
                                action(PlaybackActions.SYNC_QUEUE, queue = upcoming())
                                lastQueue = queueIds
                            }
                        }
                    }
                    lastId = t?.id
                    lastPlaying = playing
                    lastPos = pos
                    lastTick = now
                    delay(400)
                }
            }
    }
}
