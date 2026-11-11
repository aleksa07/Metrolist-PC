package com.metrolist.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.data.Settings
import com.metrolist.desktop.playback.Player
import com.metrolist.desktop.ui.screens.AddToPlaylistDialog
import com.metrolist.desktop.ui.screens.AlbumScreen
import com.metrolist.desktop.ui.screens.ArtistItemsScreen
import com.metrolist.desktop.ui.screens.ArtistScreen
import com.metrolist.desktop.ui.screens.BrowseScreen
import com.metrolist.desktop.ui.screens.ChartsScreen
import com.metrolist.desktop.ui.screens.ExploreScreen
import com.metrolist.desktop.ui.screens.HomeScreen
import com.metrolist.desktop.ui.screens.LibraryScreen
import com.metrolist.desktop.ui.screens.LocalPlaylistScreen
import com.metrolist.desktop.ui.screens.PlaylistScreen
import com.metrolist.desktop.ui.screens.SearchBox
import com.metrolist.desktop.ui.screens.SearchScreen
import com.metrolist.desktop.ui.screens.SettingsScreen
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.launch
import com.metrolist.innertube.models.YouTubeLocale

/** Pushes the saved account cookie and content region into the shared YouTube client. */
fun applyAccountSettings() {
    val s = Settings.value
    YouTube.locale = YouTubeLocale(gl = s.contentCountry, hl = s.contentLanguage)
    YouTube.cookie = s.cookie.ifBlank { null }
    YouTube.useLoginForBrowse = s.cookie.isNotBlank()
}

private data class Tab(val screen: Screen, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs =
    listOf(
        Tab(Screen.Home, "Home", Icons.Outlined.Home, Icons.Filled.Home),
        Tab(Screen.Explore, "Explore", Icons.Outlined.Explore, Icons.Filled.Explore),
        Tab(Screen.Library, "Library", Icons.Outlined.LibraryMusic, Icons.Filled.LibraryMusic),
        Tab(Screen.Together, "Together", Icons.Outlined.Groups, Icons.Filled.Groups),
    )

class AppState {
    val nav = Navigator()
    val searchFocus = FocusRequester()
    var searchFocused = false
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun App(state: AppState) {
    val nav = state.nav
    val settings by Settings.state.collectAsState()
    val current by Player.current.collectAsState()
    val error by Player.error.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { Ui.messageFlow.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            Player.clearError()
        }
    }
    CompositionLocalProvider(LocalNavigator provides nav) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press) { e -> if (e.button == PointerButton.Back) nav.pop() }) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f)) {
                        NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, header = {
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.foundation.Image(
                                painterResource("icon.png"),
                                "Metrolist",
                                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                            )
                            Spacer(Modifier.height(16.dp))
                        }) {
                            tabs.forEach { tab ->
                                val selected = nav.rootScreen == tab.screen
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { nav.root(tab.screen) },
                                    icon = { Icon(if (selected) tab.selectedIcon else tab.icon, tab.label) },
                                    label = { Text(tab.label) },
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            val settingsSelected = nav.rootScreen == Screen.Settings
                            NavigationRailItem(
                                selected = settingsSelected,
                                onClick = { nav.root(Screen.Settings) },
                                icon = { Icon(if (settingsSelected) Icons.Filled.Settings else Icons.Outlined.Settings, "Settings") },
                                label = { Text("Settings") },
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = nav::pop, enabled = nav.canGoBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                                Spacer(Modifier.size(8.dp))
                                SearchBox(state.searchFocus, { state.searchFocused = it }, Modifier.widthIn(max = 640.dp).weight(1f, fill = false))
                            }
                            ScreenHost(nav)
                        }
                        if (settings.showQueuePanel && current != null) QueuePanel(nav)
                    }
                    PlayerBar(nav)
                }
                AnimatedVisibility(Ui.expandedPlayer && current != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                    ExpandedPlayer(nav)
                }
                com.metrolist.desktop.integrations.Updater.available?.takeIf { !settings.autoInstallUpdates }?.let { r -> UpdateBanner(r.version) }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)) { Snackbar(it) }
                AddToPlaylistDialog()
            }
        }
    }
}

@Composable
private fun ScreenHost(nav: Navigator) {
    val holder = rememberSaveableStateHolder()
    // Each crossfade layer must render its own target, keyed by stack depth so revisits get fresh state.
    Crossfade(nav.stack.size to nav.current, label = "screen") { (depth, screen) ->
        holder.SaveableStateProvider("$depth:$screen") {
            Box(Modifier.fillMaxSize()) {
                when (screen) {
                    Screen.Home -> HomeScreen()
                    Screen.Explore -> ExploreScreen()
                    Screen.Library -> LibraryScreen()
                    Screen.Settings -> SettingsScreen()
                    Screen.Together -> com.metrolist.desktop.ui.screens.ListenTogetherScreen()
                    Screen.Charts -> ChartsScreen()
                    is Screen.Search -> SearchScreen(screen)
                    is Screen.Album -> AlbumScreen(screen)
                    is Screen.Artist -> ArtistScreen(screen)
                    is Screen.Playlist -> PlaylistScreen(screen)
                    is Screen.LocalPlaylist -> LocalPlaylistScreen(screen)
                    is Screen.Browse -> BrowseScreen(screen)
                    is Screen.ArtistItems -> ArtistItemsScreen(screen)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.UpdateBanner(version: String) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var dismissed by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (dismissed) return
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 6.dp,
        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Metrolist PC $version is available")
            Spacer(Modifier.size(12.dp))
            androidx.compose.material3.TextButton(onClick = { dismissed = true }) { Text("Later") }
            androidx.compose.material3.Button(onClick = { scope.launch { com.metrolist.desktop.integrations.Updater.install() } }) { Text("Install") }
        }
    }
}
