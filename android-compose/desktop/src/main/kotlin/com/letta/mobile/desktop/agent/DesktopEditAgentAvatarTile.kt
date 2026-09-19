package com.letta.mobile.desktop.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.ui.mascot.LocalMascotTransport
import com.letta.mobile.ui.mascot.MascotPicker
import com.letta.mobile.ui.mascot.MascotShapeGlyph
import com.letta.mobile.ui.theme.LettaDimens

/** The editor's avatar tile: the flat silhouette that opens the picker. */
private val EditorAvatarTileSize = 72.dp

/**
 * The avatar is the picker: click it, choose shape and colour in a popover. A pencil badge says
 * so - a bare mascot gave no hint it could be changed.
 *
 * The editor moves nothing: the pick previews on the character where it stands (the transport
 * morphs it live, and the preview ends with the editor) and this tile is the flat silhouette that
 * opens the picker. [loadedIdentity] is what the agent already shows; an unchanged pick previews
 * nothing.
 */
@Composable
internal fun EditorAvatarTile(
    agentId: String,
    identity: MascotIdentity,
    loadedIdentity: MascotIdentity?,
    onChange: (MascotIdentity) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val transport = LocalMascotTransport.current
    LaunchedEffect(identity, loadedIdentity) {
        // No baseline (still loading, or the load failed) means nothing to preview: the seeded
        // stand-in must never reach the live character as a "pick".
        transport.preview(agentId, identity.takeIf { loadedIdentity != null && it != loadedIdentity })
    }
    DisposableEffect(agentId) {
        onDispose { transport.preview(agentId, null) }
    }
    Box(Modifier.size(EditorAvatarTileSize)) {
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(LettaDimens.Radius.lg))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { pickerOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            MascotShapeGlyph(identity.shape, identity.argb, LettaDimens.Orb.railSlotWidth)
        }
        DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
            Box(Modifier.padding(LettaDimens.Space.md)) {
                MascotPicker(identity = identity, onChange = onChange, accent = MaterialTheme.colorScheme.primary)
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(LettaDimens.Control.iconButtonSm)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable { pickerOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = "Change mascot",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(LettaDimens.Control.iconSm),
            )
        }
    }
}
