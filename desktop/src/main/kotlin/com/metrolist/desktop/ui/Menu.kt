package com.metrolist.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberCursorPositionProvider

private val MenuShape = RoundedCornerShape(12.dp)

/** Right-click menu that opens at the cursor, in the same style as the "more" menus. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WithContextMenu(entries: () -> List<MenuEntry>, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf<List<MenuEntry>?>(null) }
    Box(Modifier.onPointerEvent(PointerEventType.Press) { if (it.buttons.isSecondaryPressed) shown = entries().takeIf { e -> e.isNotEmpty() } }) {
        content()
        shown?.let { items ->
            val dismiss = { shown = null }
            Popup(
                popupPositionProvider = rememberCursorPositionProvider(),
                onDismissRequest = dismiss,
                properties = PopupProperties(focusable = true),
                onKeyEvent = { if (it.key == Key.Escape) dismiss().let { true } else false },
            ) {
                Surface(shape = MenuShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 8.dp, tonalElevation = 2.dp) {
                    Column(Modifier.padding(vertical = 6.dp)) { MenuItems(items, dismiss) }
                }
            }
        }
    }
}

@Composable
fun MoreButton(entries: () -> List<MenuEntry>, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More", tint = tint) }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MenuShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
        ) {
            if (open) MenuItems(entries()) { open = false }
        }
    }
}

@Composable
private fun MenuItems(entries: List<MenuEntry>, dismiss: () -> Unit) {
    Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 200.dp, max = 320.dp)) {
        entries.forEach { e ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (hovered) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent)
                    .hoverable(interaction)
                    .clickable(interactionSource = interaction, indication = null) {
                        dismiss()
                        e.action()
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(e.icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(14.dp))
                Text(e.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
