package com.metrolist.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.metrolist.innertube.models.BrowseEndpoint
import java.util.Collections

sealed interface Screen {
    data object Home : Screen
    data object Explore : Screen
    data object Library : Screen
    data object Settings : Screen
    data object Together : Screen
    data class Search(val query: String) : Screen
    data class Album(val browseId: String) : Screen
    data class Artist(val browseId: String) : Screen
    data class Playlist(val playlistId: String) : Screen
    data class LocalPlaylist(val id: String) : Screen
    data class Browse(val browseId: String, val params: String?, val title: String?) : Screen
    data class ArtistItems(val endpoint: BrowseEndpoint, val title: String) : Screen
    data object Charts : Screen
}

class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun push(screen: Screen) {
        if (stack.last() != screen) stack.add(screen)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Top-level destinations reset the stack, like tapping a bottom-nav tab on Android. */
    fun root(screen: Screen) {
        stack.clear()
        stack.add(screen)
    }

    val rootScreen: Screen get() = stack.first()
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("no navigator") }

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

/** In-memory cache so that navigating back renders instantly with the previous data. */
private val loadCache: MutableMap<Any, Any> =
    Collections.synchronizedMap(object : LinkedHashMap<Any, Any>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Any>?) = size > 60
    })

class Loader<T>(val state: Load<T>, val reload: () -> Unit)

@Suppress("UNCHECKED_CAST")
@Composable
fun <T : Any> rememberLoad(key: Any, fetch: suspend () -> Result<T>): Loader<T> {
    var attempt by remember(key) { mutableStateOf(0) }
    var state by remember(key) { mutableStateOf<Load<T>>(loadCache[key]?.let { Load.Ok(it as T) } ?: Load.Loading) }
    LaunchedEffect(key, attempt) {
        if (attempt == 0 && state is Load.Ok) return@LaunchedEffect
        if (state !is Load.Ok) state = Load.Loading
        state =
            fetch().fold(
                onSuccess = {
                    loadCache[key] = it
                    Load.Ok(it)
                },
                onFailure = { Load.Err(it.message ?: it::class.simpleName ?: "Unknown error") },
            )
    }
    return Loader(state) { attempt++ }
}

fun invalidateLoad(key: Any) {
    loadCache.remove(key)
}
