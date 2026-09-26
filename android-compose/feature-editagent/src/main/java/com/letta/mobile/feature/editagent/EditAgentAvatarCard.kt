package com.letta.mobile.feature.editagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.mascot.LocalMascotRegistry
import com.letta.mobile.ui.mascot.MascotAvatar
import com.letta.mobile.ui.mascot.MascotPicker
import com.letta.mobile.ui.mascot.MascotShapeGlyph
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The agent's mascot identity: the live tile (or the flat silhouette until the renderer has it)
 * and a sheet with the shared [MascotPicker]. A pick is written to the registry at once so every
 * tile of this agent re-skins while the sheet is still open; persistence is the view model's.
 */
@Composable
internal fun EditAgentAvatarCard(
    agentId: String,
    identity: MascotIdentity?,
    onChange: (MascotIdentity) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    CardGroup(title = { Text(stringResource(R.string.screen_agent_edit_avatar_section)) }) {
        item(
            headlineContent = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MascotAvatar(
                        agentId = agentId,
                        size = AvatarTileSize,
                        cornerRadius = AvatarTileSize / 2,
                        fallback = {
                            val shown = identity ?: MascotIdentity.DEFAULT
                            MascotShapeGlyph(shown.shape, shown.argb, AvatarTileSize)
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (identity == null) {
                                stringResource(R.string.screen_agent_edit_avatar_none)
                            } else {
                                identity.shape.name.lowercase().replaceFirstChar { it.titlecase() }
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.screen_agent_edit_avatar_supporting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { showPicker = true }) {
                        Text(stringResource(R.string.screen_agent_edit_avatar_change))
                    }
                }
            },
        )
    }
    if (showPicker) {
        EditAgentAvatarPickerSheet(
            agentId = agentId,
            identity = identity,
            onChange = onChange,
            onDismiss = { showPicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditAgentAvatarPickerSheet(
    agentId: String,
    identity: MascotIdentity?,
    onChange: (MascotIdentity) -> Unit,
    onDismiss: () -> Unit,
) {
    val registry = LocalMascotRegistry.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LettaDimens.Space.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
        ) {
            MascotPicker(
                identity = identity ?: MascotIdentity.DEFAULT,
                onChange = { picked ->
                    registry.identities[agentId] = picked
                    onChange(picked)
                },
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End).padding(bottom = LettaDimens.Space.sm),
            ) {
                Text(stringResource(R.string.screen_agent_edit_avatar_done))
            }
        }
    }
}

private val AvatarTileSize = LettaDimens.Orb.railSlotWidth
