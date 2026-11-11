package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.desktop.data.Settings
import com.metrolist.desktop.integrations.ListenTogether
import com.metrolist.desktop.integrations.ListenTogether.Role
import com.metrolist.desktop.playback.Player
import com.metrolist.desktop.ui.Artwork
import com.metrolist.desktop.ui.Ui

@Composable
fun ListenTogetherScreen() {
    val state by ListenTogether.state.collectAsState()
    LaunchedEffect(state.message) {
        state.message?.let {
            Ui.toast(it)
            ListenTogether.dismissMessage()
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 16.dp).widthIn(max = 760.dp)) {
        Text("Listen Together", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            "Listen in sync with friends on Metrolist, on PC or Android. The host controls playback; everyone else follows.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(24.dp))
        if (state.inRoom) RoomView(state) else Lobby(state.connecting)
    }
}

@Composable
private fun Lobby(connecting: Boolean) {
    val settings by Settings.state.collectAsState()
    var name by remember { mutableStateOf(settings.listenTogetherName) }
    var code by remember { mutableStateOf("") }
    fun saveName() = Settings.update { it.copy(listenTogetherName = name.trim()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(name, { name = it.take(24) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !connecting && name.isNotBlank(), onClick = {
                    saveName()
                    ListenTogether.createRoom(name.trim())
                }) {
                    Icon(Icons.Default.Groups, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Create a room")
                }
                Text("and share the code with your friends", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(code, { code = it.filter(Char::isLetterOrDigit).take(8).uppercase() }, label = { Text("Room code") }, singleLine = true, modifier = Modifier.width(200.dp))
                FilledTonalButton(enabled = !connecting && name.isNotBlank() && code.length >= 4, onClick = {
                    saveName()
                    ListenTogether.joinRoom(code, name.trim())
                }) { Text("Join") }
                if (connecting) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            Text("Server: ${ListenTogether.serverUrl.removePrefix("wss://").substringBefore('/')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RoomView(state: ListenTogether.State) {
    val room = state.room ?: return
    val isHost = state.role == Role.HOST
    val current by Player.current.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (isHost) "You're hosting" else "You're listening along", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(room.roomCode, fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 6.sp)
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = { Ui.copy(room.roomCode) }) { Icon(Icons.Default.ContentCopy, "Copy code") }
                OutlinedButton(onClick = { Ui.copy("https://metrolist.cc/listen?code=${room.roomCode}") }) { Text("Copy invite link") }
            }
            if (state.waitingFor.isNotEmpty()) {
                Text("Waiting for ${state.waitingFor.size} listener(s) to load the song…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            current?.let { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(t.thumbnail, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(t.title, style = MaterialTheme.typography.bodyLarge)
                        Text(t.artistText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } ?: Text(if (isHost) "Play something — everyone in the room will hear it." else "Waiting for the host to play something.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isHost) FilledTonalButton(onClick = ListenTogether::requestSync) { Text("Resync") }
                OutlinedButton(onClick = ListenTogether::leave) { Text("Leave room") }
            }
        }
    }

    if (isHost && state.joinRequests.isNotEmpty()) {
        Text("Wants to join", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        Card(Modifier.fillMaxWidth()) {
            state.joinRequests.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Person, null)
                    Spacer(Modifier.width(12.dp))
                    Text(r.username, Modifier.weight(1f))
                    OutlinedButton(onClick = { ListenTogether.reject(r.userId) }) { Text("Decline") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { ListenTogether.approve(r.userId) }) { Text("Let in") }
                }
            }
        }
    }

    Text("In the room (${room.usersCount})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
    Card(Modifier.fillMaxWidth()) {
        room.usersList.forEach { u ->
            val me = u.userId == state.userId
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (u.isHost) Icons.Default.Star else Icons.Default.Person, null, tint = if (u.isHost) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(
                    (if (me) "${Settings.value.listenTogetherName} (you)" else u.username) +
                        (if (u.isHost) " · host" else "") + (if (!u.isConnected) " · reconnecting" else ""),
                    Modifier.weight(1f),
                )
                if (isHost && !me) IconButton(onClick = { ListenTogether.kick(u.userId) }) { Icon(Icons.Default.PersonRemove, "Remove ${u.username}") }
            }
        }
    }
}
