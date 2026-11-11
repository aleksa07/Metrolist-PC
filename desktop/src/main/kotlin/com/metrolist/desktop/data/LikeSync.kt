package com.metrolist.desktop.data

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.utils.completed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Keeps liked songs in step with the signed-in YouTube Music account, like Metrolist's SyncUtils:
 * likes made here are sent immediately, and syncing pulls the account's "Liked music" playlist.
 */
object LikeSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val signedIn get() = Settings.value.cookie.isNotBlank()

    fun push(track: Track, liked: Boolean) {
        if (!signedIn) return
        scope.launch {
            YouTube.likeVideo(track.id, liked).onFailure { Timber.tag("LikeSync").w(it, "couldn't update like on YouTube Music") }
        }
    }

    fun syncInBackground() {
        if (signedIn) scope.launch { sync() }
    }

    /** Returns how many liked songs the account has, or an error. */
    suspend fun sync(): Result<Int> =
        mutex.withLock {
            if (!signedIn) return Result.failure(IllegalStateException("Not signed in"))
            YouTube.playlist("LM").completed().map { page ->
                val remote = page.songs.map { it.toTrack() }
                val remoteIds = remote.map { it.id }.toSet()
                val advertised = page.playlist.songCountText?.filter(Char::isDigit)?.toIntOrNull()
                // Only trust removals when the whole playlist came back (a partial fetch isn't an unlike).
                val complete = remote.isNotEmpty() && (advertised == null || remote.size >= advertised)
                Library.update { lib ->
                    val snapshot = lib.likedSnapshot.toSet()
                    val removed = if (complete) lib.liked.map { it.id }.filter { it in snapshot && it !in remoteIds }.toSet() else emptySet()
                    val local = lib.liked.associateBy { it.id }
                    val localOnly = lib.liked.filter { it.id !in remoteIds && it.id !in removed }
                    lib.copy(
                        liked = localOnly + remote.map { local[it.id] ?: it },
                        likedSnapshot = if (complete) remoteIds.toList() else lib.likedSnapshot,
                    )
                }
                remote.size
            }
        }
}
