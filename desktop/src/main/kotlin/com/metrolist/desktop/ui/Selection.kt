package com.metrolist.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.data.Library
import com.metrolist.desktop.data.Track
import com.metrolist.desktop.playback.Player

/** Songs picked with Ctrl/Shift-click in one song list. */
object Selection {
    val tracks = mutableStateListOf<Track>()
    private var anchorId: String? = null
    private var source: List<Track>? = null

    fun isSelected(t: Track) = tracks.any { it.id == t.id }

    fun clear() {
        tracks.clear()
        anchorId = null
        source = null
    }

    /** Ctrl toggles one song, Shift selects from the last clicked song to this one. */
    fun click(list: List<Track>, track: Track, shift: Boolean) {
        if (source != list) clear()
        source = list
        val anchor = anchorId
        if (shift && anchor != null) {
            val range = range(list, anchor, track.id)
            tracks.clear()
            tracks.addAll(range)
        } else {
            if (isSelected(track)) tracks.removeAll { it.id == track.id } else tracks.add(track)
            anchorId = track.id
        }
    }

    fun range(list: List<Track>, fromId: String, toId: String): List<Track> {
        val a = list.indexOfFirst { it.id == fromId }
        val b = list.indexOfFirst { it.id == toId }
        if (b < 0) return emptyList()
        if (a < 0) return listOf(list[b])
        return list.subList(minOf(a, b), maxOf(a, b) + 1).toList()
    }

    fun menu(): List<MenuEntry> {
        val picked = tracks.toList()
        return listOf(
            MenuEntry("Play next", Icons.AutoMirrored.Filled.PlaylistPlay) { Player.playNext(picked); Ui.toast("${picked.size} songs will play next"); clear() },
            MenuEntry("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) { Player.addToQueue(picked); Ui.toast("Added ${picked.size} songs to the queue"); clear() },
            MenuEntry("Add to playlist…", Icons.AutoMirrored.Filled.PlaylistAdd) { Ui.addToPlaylist = picked; clear() },
            MenuEntry("Like", Icons.Default.Favorite) { picked.filterNot { Library.isLiked(it.id) }.forEach(Actions::toggleLike); clear() },
            MenuEntry("Download", Icons.Default.Download) { Actions.download(picked); clear() },
        )
    }

    fun selfCheck() {
        fun t(id: String) = Track(id, id)
        val list = listOf(t("a"), t("b"), t("c"), t("d"))
        check(range(list, "c", "a").map { it.id } == listOf("a", "b", "c")) { "range backwards" }
        check(range(list, "gone", "b").map { it.id } == listOf("b")) { "range without anchor" }
        click(list, list[1], shift = false)
        click(list, list[3], shift = true)
        check(tracks.map { it.id } == listOf("b", "c", "d")) { "shift-click range" }
        click(list, list[2], shift = false)
        check(tracks.map { it.id } == listOf("b", "d")) { "ctrl-click toggles" }
        click(listOf(t("x")), t("x"), shift = false)
        check(tracks.map { it.id } == listOf("x")) { "another list starts over" }
        clear()
    }
}

/** Shown while songs are selected: what can be done with all of them at once. */
@Composable
fun SelectionBar(modifier: Modifier = Modifier) {
    if (Selection.tracks.isEmpty()) return
    Surface(modifier, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer, shadowElevation = 8.dp) {
        Row(Modifier.padding(start = 20.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${Selection.tracks.size} selected", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(12.dp))
            Selection.menu().forEach { e -> IconButton(onClick = e.action) { Icon(e.icon, e.label) } }
            IconButton(onClick = Selection::clear) { Icon(Icons.Default.Close, "Clear selection") }
        }
    }
}
