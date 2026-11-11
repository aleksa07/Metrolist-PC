package com.metrolist.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.metrolist.desktop.data.Library
import com.metrolist.desktop.data.Lyrics
import com.metrolist.desktop.data.LyricsRepository
import com.metrolist.desktop.data.Settings
import com.metrolist.desktop.data.Track
import com.metrolist.desktop.data.formatDuration
import com.metrolist.desktop.data.resized
import com.metrolist.desktop.playback.Player
import com.metrolist.desktop.playback.RepeatMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(modifier: Modifier = Modifier, light: Boolean = false) {
    val position by Player.position.collectAsState()
    val duration by Player.duration.collectAsState()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val total = duration.coerceAtLeast(1)
    val shown = if (dragging) dragValue else (position.toFloat() / total).coerceIn(0f, 1f)
    val textColor = if (light) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    val colors =
        if (light) {
            SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.25f))
        } else {
            SliderDefaults.colors()
        }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(formatDuration(((if (dragging) dragValue * total else position.toFloat()) / 1000).toLong()), style = MaterialTheme.typography.labelMedium, color = textColor, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
        Slider(
            value = shown,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                Player.seekTo((dragValue * total).toLong())
                dragging = false
            },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(24.dp),
            colors = colors,
            thumb = { DotThumb(colors) },
            track = { GaplessTrack(it, colors) },
        )
        Text(if (duration > 0) formatDuration(duration / 1000) else "--:--", style = MaterialTheme.typography.labelMedium, color = textColor, modifier = Modifier.width(48.dp))
    }
}

@Composable
private fun TransportButtons(big: Boolean = false, light: Boolean = false) {
    val playing by Player.isPlaying.collectAsState()
    val buffering by Player.buffering.collectAsState()
    val repeat by Player.repeat.collectAsState()
    val shuffle by Player.shuffle.collectAsState()
    val tint = if (light) Color.White else MaterialTheme.colorScheme.onSurface
    val activeTint = if (light) Color.White else MaterialTheme.colorScheme.primary
    val dim = if (light) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant
    val playSize = if (big) 64.dp else 44.dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (big) 16.dp else 4.dp)) {
        IconButton(onClick = Player::toggleShuffle) { Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffle) activeTint else dim) }
        IconButton(onClick = Player::previous) { Icon(Icons.Default.SkipPrevious, "Previous", tint = tint, modifier = Modifier.size(if (big) 36.dp else 28.dp)) }
        FilledIconButton(
            onClick = Player::togglePlay,
            modifier = Modifier.size(playSize),
            colors =
                if (light) {
                    IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)
                } else {
                    IconButtonDefaults.filledIconButtonColors()
                },
        ) {
            if (buffering && playing) {
                CircularProgressIndicator(Modifier.size(playSize / 2), strokeWidth = 2.dp, color = if (light) Color.Black else MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(playSize / 2 + 6.dp))
            }
        }
        IconButton(onClick = Player::next) { Icon(Icons.Default.SkipNext, "Next", tint = tint, modifier = Modifier.size(if (big) 36.dp else 28.dp)) }
        IconButton(onClick = Player::cycleRepeat) {
            Icon(if (repeat == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat", tint = if (repeat != RepeatMode.OFF) activeTint else dim)
        }
    }
}

/** Track without the gap Material 3 leaves around the thumb, which hid the filled part at low values. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GaplessTrack(state: SliderState, colors: SliderColors) {
    val range = state.valueRange
    val fraction = ((state.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    // Same height as the knob so both are centred on one line; the fill ends at the knob's centre.
    Canvas(Modifier.fillMaxWidth().height(KnobSize)) {
        val h = 6.dp.toPx()
        val r = KnobSize.toPx() / 2
        val top = (size.height - h) / 2
        drawRoundRect(colors.inactiveTrackColor, Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
        drawRoundRect(colors.activeTrackColor, Offset(0f, top), Size(r + fraction * (size.width - 2 * r), h), CornerRadius(h / 2))
    }
}

private val KnobSize = 14.dp

/** A small round knob instead of Material 3's tall bar. */
@Composable
private fun DotThumb(colors: SliderColors) =
    SliderDefaults.Thumb(remember { MutableInteractionSource() }, colors = colors, thumbSize = DpSize(KnobSize, KnobSize))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeControl() {
    val settings by Settings.state.collectAsState()
    var beforeMute by remember { mutableFloatStateOf(0.8f) }
    val v = settings.volume
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            if (v > 0f) {
                beforeMute = v
                Player.setVolume(0f)
            } else {
                Player.setVolume(beforeMute.takeIf { it > 0 } ?: 0.8f)
            }
        }) {
            Icon(
                when {
                    v == 0f -> Icons.AutoMirrored.Filled.VolumeOff
                    v < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                },
                "Volume",
            )
        }
        val colors = SliderDefaults.colors()
        Slider(value = v, onValueChange = Player::setVolume, modifier = Modifier.width(110.dp).height(24.dp), colors = colors, thumb = { DotThumb(colors) }, track = { GaplessTrack(it, colors) })
        Text(
            "${(v * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp),
        )
    }
}

@Composable
fun LikeButton(track: Track, light: Boolean = false) {
    val lib by Library.state.collectAsState()
    val liked = lib.liked.any { it.id == track.id }
    IconButton(onClick = { Actions.toggleLike(track) }) {
        Icon(
            if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            if (liked) "Unlike" else "Like",
            tint = if (liked) (if (light) Color.White else MaterialTheme.colorScheme.primary) else if (light) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun PlayerBar(nav: Navigator) {
    val track by Player.current.collectAsState()
    val settings by Settings.state.collectAsState()
    val t = track ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Artwork(t.thumbnail, 60.dp, modifier = Modifier.clickable { Ui.expandedPlayer = true })
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, modifier = Modifier.clickable { Ui.expandedPlayer = true })
                    Row {
                        t.artists.forEachIndexed { i, a ->
                            Text(
                                a.name + if (i < t.artists.lastIndex) ", " else "",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = if (a.id != null) Modifier.clickable { Ui.expandedPlayer = false; nav.push(Screen.Artist(a.id)) } else Modifier,
                            )
                        }
                    }
                }
                LikeButton(t)
            }
            Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.CenterHorizontally) {
                TransportButtons()
                SeekBar(Modifier.fillMaxWidth())
            }
            // Not weighted: the volume controls keep their full width and the seek bar absorbs a narrow window.
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Ui.expandedPlayer = true }) { Icon(Icons.Default.Lyrics, "Lyrics") }
                IconButton(onClick = { Settings.update { it.copy(showQueuePanel = !it.showQueuePanel) } }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue", tint = if (settings.showQueuePanel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
                VolumeControl()
            }
        }
    }
}

@Composable
fun QueueList(nav: Navigator, light: Boolean = false, modifier: Modifier = Modifier) {
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val title by Player.queueTitle.collectAsState()
    val state = rememberLazyListState()
    LaunchedEffect(index) { if (index > 0) state.animateScrollToItem((index - 1).coerceAtLeast(0)) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Up next", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (light) Color.White else Color.Unspecified)
                title?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (light) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Text("${queue.size} songs", style = MaterialTheme.typography.labelMedium, color = if (light) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = Player::clearQueue) { Icon(Icons.Default.ClearAll, "Clear queue", tint = if (light) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        LazyColumn(state = state, contentPadding = PaddingValues(8.dp)) {
            itemsIndexed(queue, key = { i, t -> "$i-${t.id}" }) { i, t ->
                QueueRow(nav, t, i, i == index, light)
            }
        }
    }
}

@Composable
private fun QueueRow(nav: Navigator, t: Track, i: Int, isCurrent: Boolean, light: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val menu = {
        trackMenu(
            nav,
            t,
            listOf(
                MenuEntry("Move up", Icons.Default.ArrowUpward) { Player.moveInQueue(i, i - 1) },
                MenuEntry("Move down", Icons.Default.ArrowDownward) { Player.moveInQueue(i, i + 1) },
                MenuEntry("Remove from queue", Icons.Default.Close) { Player.removeFromQueue(i) },
            ),
        )
    }
    WithContextMenu(menu) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        isCurrent -> if (light) Color.White.copy(alpha = 0.15f) else MaterialTheme.colorScheme.secondaryContainer
                        hovered -> if (light) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainerHighest
                        else -> Color.Transparent
                    },
                )
                .hoverable(interaction)
                .clickable { Player.skipTo(i) }
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(t.thumbnail, 44.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal, color = if (light) Color.White else Color.Unspecified)
                Text(t.artistText, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = if (light) Color.White.copy(alpha = 0.65f) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (hovered && !isCurrent) {
                IconButton(onClick = { Player.removeFromQueue(i) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, "Remove", Modifier.size(18.dp), tint = if (light) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                t.durationSec?.let { Text(formatDuration(it.toLong()), style = MaterialTheme.typography.labelSmall, color = if (light) Color.White.copy(alpha = 0.65f) else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
fun QueuePanel(nav: Navigator) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(340.dp).fillMaxHeight()) {
        QueueList(nav, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun LyricsView(track: Track, modifier: Modifier = Modifier) {
    val lyrics by produceState<Lyrics?>(null, track.id) { value = LyricsRepository.get(track) }
    val position by Player.position.collectAsState()
    val l = lyrics
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            l == null -> CircularProgressIndicator(color = Color.White)
            l.isEmpty -> Text("No lyrics found", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.titleMedium)
            !l.synced.isNullOrEmpty() -> {
                val lines = l.synced
                val active by remember(lines) { derivedStateOf { (lines.indexOfLast { it.timeMs <= position + 250 }).coerceAtLeast(0) } }
                val state = rememberLazyListState()
                LaunchedEffect(active) { state.animateScrollToItem((active - 3).coerceAtLeast(0)) }
                LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 120.dp, horizontal = 8.dp)) {
                    itemsIndexed(lines) { i, line ->
                        val color by animateColorAsState(if (i == active) Color.White else Color.White.copy(alpha = 0.38f), tween(300))
                        Text(
                            line.text.ifBlank { "♪" },
                            color = color,
                            fontSize = 28.sp,
                            lineHeight = 36.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { Player.seekTo(line.timeMs) }.padding(vertical = 10.dp, horizontal = 8.dp),
                        )
                    }
                    item { Text("Lyrics from ${l.source}", color = Color.White.copy(alpha = 0.4f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp)) }
                }
            }
            else ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 32.dp, horizontal = 16.dp)) {
                    item { Text(l.plain.orEmpty(), color = Color.White.copy(alpha = 0.9f), fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium) }
                    item { Text("Lyrics from ${l.source}", color = Color.White.copy(alpha = 0.4f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 16.dp)) }
                }
        }
    }
}

/** Full-window player with blurred artwork backdrop, lyrics and queue (the Android expanded player sheet). */
@Composable
fun ExpandedPlayer(nav: Navigator) {
    val track by Player.current.collectAsState()
    val t = track ?: return
    var tab by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(enabled = false) {}) {
        AsyncImage(
            model = t.thumbnail.resized(400),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(90.dp).alpha(0.75f),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.7f)))))
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Ui.expandedPlayer = false }) { Icon(Icons.Default.KeyboardArrowDown, "Close", tint = Color.White, modifier = Modifier.size(32.dp)) }
                Spacer(Modifier.weight(1f))
                Text("NOW PLAYING", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                MoreButton({ trackMenu(nav, t).map { e -> MenuEntry(e.label, e.icon) { Ui.expandedPlayer = false; e.action() } } }, tint = Color.White)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth > 900.dp
                val boxW = maxWidth
                val boxH = maxHeight
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        val showVisualizer = Settings.state.collectAsState().value.showVisualizer
                        val reserved = if (showVisualizer) 300.dp else 220.dp
                        val artSize = minOf(boxH - reserved, if (wide) boxW / 2 - 80.dp else boxW - 80.dp).coerceIn(160.dp, 520.dp)
                        Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 24.dp) {
                            AsyncImage(t.thumbnail.resized(1080), null, contentScale = ContentScale.Crop, modifier = Modifier.size(artSize))
                        }
                        Spacer(Modifier.height(24.dp))
                        Row(Modifier.width(artSize), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    t.artistText,
                                    color = Color.White.copy(alpha = 0.75f),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = t.artists.firstOrNull()?.id?.let { id -> Modifier.clickable { Ui.expandedPlayer = false; nav.push(Screen.Artist(id)) } } ?: Modifier,
                                )
                            }
                            LikeButton(t, light = true)
                        }
                        Spacer(Modifier.height(8.dp))
                        SeekBar(Modifier.width(artSize + 96.dp), light = true)
                        Spacer(Modifier.height(8.dp))
                        TransportButtons(big = true, light = true)
                        if (showVisualizer) {
                            Spacer(Modifier.height(16.dp))
                            Visualizer(MaterialTheme.colorScheme.primary, Modifier.width(artSize + 96.dp).height(56.dp))
                        }
                    }
                    if (wide) {
                        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp)) {
                            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = Color.White, divider = { HorizontalDivider(color = Color.White.copy(alpha = 0.15f)) }) {
                                Tab(tab == 0, { tab = 0 }, text = { Text("Lyrics") })
                                Tab(tab == 1, { tab = 1 }, text = { Text("Up next") })
                            }
                            if (tab == 0) LyricsView(t, Modifier.fillMaxSize()) else QueueList(nav, light = true, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}
