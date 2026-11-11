package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.data.AppDirs
import com.metrolist.desktop.data.Library
import com.metrolist.desktop.data.Settings
import com.metrolist.desktop.data.ThemeMode
import com.metrolist.desktop.playback.AudioQuality
import com.metrolist.desktop.ui.Ui
import com.metrolist.desktop.ui.applyAccountSettings
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AccountInfo
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(vertical = 4.dp)) { content() } }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            options.forEachIndexed { i, (label, value) ->
                SegmentedButton(selected = value == selected, onClick = { onSelect(value) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val s by Settings.state.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 16.dp).widthIn(max = 860.dp)) {
        Text("Settings", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)

        Group("Appearance") {
            ChoiceRow("Theme", listOf("System" to ThemeMode.SYSTEM, "Dark" to ThemeMode.DARK, "Light" to ThemeMode.LIGHT), s.theme) { v -> Settings.update { it.copy(theme = v) } }
            HorizontalDivider()
            SwitchRow("Dynamic colour", "Tint the app with colours from the current artwork", s.dynamicColor) { v -> Settings.update { it.copy(dynamicColor = v) } }
            SwitchRow("Pure black", "Use a black background in dark theme", s.pureBlack) { v -> Settings.update { it.copy(pureBlack = v) } }
            SwitchRow("Visualizer", "Animated spectrum in the now-playing view", s.showVisualizer) { v -> Settings.update { it.copy(showVisualizer = v) } }
        }

        Group("Player & audio") {
            ChoiceRow("Audio quality", listOf("Auto" to AudioQuality.AUTO, "High" to AudioQuality.HIGH, "Low" to AudioQuality.LOW), s.audioQuality) { v -> Settings.update { it.copy(audioQuality = v) } }
            HorizontalDivider()
            SwitchRow("Normalise volume", "Use YouTube loudness data to level tracks", s.normalizeVolume) { v -> Settings.update { it.copy(normalizeVolume = v) } }
            SwitchRow("Close to system tray", "The X button hides the window and music keeps playing; quit from the tray icon", s.closeToTray) { v -> Settings.update { it.copy(closeToTray = v) } }
            SwitchRow("Autoplay radio", "Fill the queue with similar songs when you play a single track", s.autoRadio) { v -> Settings.update { it.copy(autoRadio = v) } }
        }

        Group("Content") {
            var lang by remember { mutableStateOf(s.contentLanguage) }
            var country by remember { mutableStateOf(s.contentCountry) }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(lang, { lang = it.take(8) }, label = { Text("Language (hl)") }, singleLine = true, modifier = Modifier.width(160.dp))
                OutlinedTextField(country, { country = it.take(2).uppercase() }, label = { Text("Country (gl)") }, singleLine = true, modifier = Modifier.width(160.dp))
                Button(onClick = {
                    Settings.update { it.copy(contentLanguage = lang.ifBlank { "en" }, contentCountry = country.ifBlank { "US" }) }
                    applyAccountSettings()
                    Ui.toast("Content region updated")
                }) { Text("Apply") }
            }
        }

        Group("Privacy") {
            SwitchRow("Keep listening history", "Used for the Listen again shelf and History tab", s.recordHistory) { v -> Settings.update { it.copy(recordHistory = v) } }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { Library.clearHistory(); Ui.toast("Listening history cleared") }) { Text("Clear listening history") }
                OutlinedButton(onClick = { Library.update { it.copy(searchHistory = emptyList()) }; Ui.toast("Search history cleared") }) { Text("Clear search history") }
            }
        }

        Group("Last.fm") { LastFmSection(s) }

        Group("Discord") { DiscordSection(s) }

        Group("Account") {
            AccountSection(s.cookie)
        }

        Group("Updates") {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val updater = com.metrolist.desktop.integrations.Updater
            SwitchRow("Check for updates", "Look for a new version when the app starts", s.checkUpdates) { v -> Settings.update { it.copy(checkUpdates = v) } }
            SwitchRow("Install updates automatically", "Download and install new versions without asking", s.autoInstallUpdates) { v -> Settings.update { it.copy(autoInstallUpdates = v) } }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { scope.launch { updater.check(userInitiated = true) } }) { Text("Check now") }
                updater.available?.let { r -> Button(onClick = { scope.launch { updater.install() } }) { Text("Install ${r.version}") } }
                Text(updater.status ?: "Version ${updater.currentVersion ?: "dev"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Group("About") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Metrolist for desktop", style = MaterialTheme.typography.titleMedium)
                Text("A desktop port of Metrolist, the open-source YouTube Music client for Android. It shares Metrolist's InnerTube API layer and InnerTubeX stream extraction.", style = MaterialTheme.typography.bodyMedium)
                Text("Licensed under the GNU GPL v3. Not affiliated with Google or YouTube.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Data folder: ${AppDirs.data.absolutePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.padding(4.dp))
                OutlinedButton(onClick = { Ui.copy("https://github.com/MetrolistGroup/Metrolist") }) { Text("Copy project link") }
            }
        }
    }
}

@Composable
private fun AccountSection(savedCookie: String) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var account by remember { mutableStateOf<AccountInfo?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var token by remember(savedCookie) { mutableStateOf(savedCookie) }
    LaunchedEffect(savedCookie) {
        account = null
        if (savedCookie.isNotBlank()) {
            YouTube.accountInfo().onSuccess { account = it; status = null }.onFailure { status = "Couldn't load your account: ${it.message}" }
        }
    }
    fun saveCookie(cookie: String) {
        Settings.update { it.copy(cookie = cookie) }
        applyAccountSettings()
        com.metrolist.desktop.ui.invalidateLoad("home:null")
        com.metrolist.desktop.ui.invalidateLoad("ytlibrary")
        // A different account must not "unlike" songs based on the previous account's snapshot.
        com.metrolist.desktop.data.Library.update { it.copy(likedSnapshot = emptyList()) }
        com.metrolist.desktop.data.LikeSync.syncInBackground()
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val a = account
        if (savedCookie.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (a != null) "Signed in as ${a.name}${a.email?.let { " ($it)" }.orEmpty()}" else "Signed in",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        status = com.metrolist.desktop.data.LikeSync.sync().fold({ "Liked songs synced ($it on your account)" }, { "Sync failed: ${it.message}" })
                        busy = false
                    }
                }) { Text("Sync liked songs") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    com.metrolist.desktop.integrations.GoogleLogin.signOut()
                    saveCookie("")
                }) { Text("Sign out") }
            }
        } else {
            Text(
                "Sign in to get your personalised home feed, your playlists and your YouTube Music library.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(enabled = !busy, onClick = {
                busy = true
                status = null
                scope.launch {
                    runCatching { com.metrolist.desktop.integrations.GoogleLogin.signIn { msg -> status = msg } }
                        .onSuccess { cookie -> if (cookie != null) saveCookie(cookie) else status = "Sign-in cancelled" }
                        .onFailure { status = "Sign-in failed: ${it.message}" }
                    busy = false
                }
            }) { Text(if (busy) "Signing in…" else "Sign in with Google") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        androidx.compose.material3.TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced: sign in with token") }
        if (advanced) {
            Text(
                "The token is the Cookie header of music.youtube.com. It gives full access to your Google account, so never share it. " +
                    "You can paste one copied from your browser's developer tools instead of signing in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedTextField(
                token,
                { token = it.replace("\n", "").trim() },
                label = { Text("Token") },
                singleLine = true,
                visualTransformation = if (showToken) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { androidx.compose.material3.TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = token != savedCookie, onClick = {
                    if (token.isNotBlank() && !token.contains("SAPISID")) {
                        status = "That doesn't look like a signed-in YouTube Music token (no SAPISID)."
                    } else {
                        saveCookie(token)
                    }
                }) { Text("Save token") }
                if (savedCookie.isNotBlank()) OutlinedButton(onClick = { Ui.copy(savedCookie) }) { Text("Copy token") }
            }
        }
    }
}

@Composable
private fun SliderRow(title: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        androidx.compose.material3.Slider(value, onChange, valueRange = range, steps = steps, modifier = Modifier.width(220.dp))
        Text(valueText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp).padding(start = 12.dp))
    }
}

@Composable
private fun LastFmSection(s: com.metrolist.desktop.data.AppSettings) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var key by remember { mutableStateOf(s.lastfmApiKey) }
    var secret by remember { mutableStateOf(s.lastfmSecret) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.lastfmSession.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Connected as ${s.lastfmUser}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { Settings.update { it.copy(lastfmSession = "", lastfmUser = "") } }) { Text("Disconnect") }
            }
        } else {
            Text(
                "Scrobble what you play to Last.fm. Create a free API account at last.fm/api/account/create, paste its API key and shared secret here, then click Connect and approve Metrolist in your browser.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(key, { key = it.trim() }, label = { Text("API key") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(secret, { secret = it.trim() }, label = { Text("Shared secret") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
                Button(enabled = !busy && key.isNotBlank() && secret.isNotBlank(), onClick = {
                    Settings.update { it.copy(lastfmApiKey = key, lastfmSecret = secret) }
                    busy = true
                    status = "Waiting for you to approve Metrolist in your browser…"
                    scope.launch {
                        status = com.metrolist.desktop.integrations.Integrations.connectLastFm().fold({ null }, { it.message })
                        busy = false
                    }
                }) { Text(if (busy) "Waiting…" else "Connect") }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    HorizontalDivider()
    SwitchRow("Scrobbling", "Send played songs to your Last.fm profile", s.scrobbleEnabled) { v -> Settings.update { it.copy(scrobbleEnabled = v) } }
    SwitchRow("Now playing", "Show the current song on your profile while it plays", s.lastfmNowPlaying) { v -> Settings.update { it.copy(lastfmNowPlaying = v) } }
    SwitchRow("Send likes", "Love tracks on Last.fm when you like them here", s.lastfmSendLikes) { v -> Settings.update { it.copy(lastfmSendLikes = v) } }
    SliderRow("Scrobble after", "${(s.scrobblePercent * 100).toInt()}%", s.scrobblePercent, 0.3f..0.9f, 11) { v -> Settings.update { it.copy(scrobblePercent = (v * 20).roundToInt() / 20f) } }
    SliderRow("…or after", "${s.scrobbleMinutes} min", s.scrobbleMinutes.toFloat(), 1f..10f, 8) { v -> Settings.update { it.copy(scrobbleMinutes = v.roundToInt()) } }
    SliderRow("Minimum track length", "${s.scrobbleMinTrackSec} s", s.scrobbleMinTrackSec.toFloat(), 0f..120f, 11) { v -> Settings.update { it.copy(scrobbleMinTrackSec = (v / 10).roundToInt() * 10) } }
}

@Composable
private fun DiscordSection(s: com.metrolist.desktop.data.AppSettings) {
    SwitchRow("Rich Presence", "Show what you're listening to on your Discord profile (needs the Discord desktop app running)", s.discordEnabled) { v -> Settings.update { it.copy(discordEnabled = v) } }
    if (!s.discordEnabled) return
    HorizontalDivider()
    ChoiceRow("Activity type", listOf("Listening" to 2, "Playing" to 0, "Watching" to 3), s.discordActivityType) { v -> Settings.update { it.copy(discordActivityType = v) } }
    var details by remember { mutableStateOf(s.discordDetails) }
    var state by remember { mutableStateOf(s.discordState) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(details, { details = it; Settings.update { st -> st.copy(discordDetails = it) } }, label = { Text("First line") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(state, { state = it; Settings.update { st -> st.copy(discordState = it) } }, label = { Text("Second line") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Text("Placeholders: {song.name}, {artist.name}, {album.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SwitchRow("\"Listen on YouTube Music\" button", null, s.discordListenButton) { v -> Settings.update { it.copy(discordListenButton = v) } }
    SwitchRow("\"Visit Metrolist\" button", null, s.discordProjectButton) { v -> Settings.update { it.copy(discordProjectButton = v) } }
    SwitchRow("Show when paused", "Keep the status visible while playback is paused", s.discordShowPaused) { v -> Settings.update { it.copy(discordShowPaused = v) } }
}
