package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Shield
import com.letta.mobile.data.runtime.PermissionModeRuntime
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_permission_mode
import com.letta.mobile.sharedui.resources.composer_permission_mode_accept_edits
import com.letta.mobile.sharedui.resources.composer_permission_mode_accept_edits_hint
import com.letta.mobile.sharedui.resources.composer_permission_mode_changing
import com.letta.mobile.sharedui.resources.composer_permission_mode_failed
import com.letta.mobile.sharedui.resources.composer_permission_mode_item
import com.letta.mobile.sharedui.resources.composer_permission_mode_not_saved
import com.letta.mobile.sharedui.resources.composer_permission_mode_on_start
import com.letta.mobile.sharedui.resources.composer_permission_mode_runtime_host
import com.letta.mobile.sharedui.resources.composer_permission_mode_runtime_host_unknown
import com.letta.mobile.sharedui.resources.composer_permission_mode_runtime_local
import com.letta.mobile.sharedui.resources.composer_permission_mode_unconfirmed
import com.letta.mobile.sharedui.resources.composer_permission_mode_standard
import com.letta.mobile.sharedui.resources.composer_permission_mode_standard_hint
import com.letta.mobile.sharedui.resources.composer_permission_mode_strict
import com.letta.mobile.sharedui.resources.composer_permission_mode_strict_hint
import com.letta.mobile.sharedui.resources.composer_permission_mode_unrestricted
import com.letta.mobile.sharedui.resources.composer_permission_mode_unrestricted_hint
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.session.ChatPermissionModeUiState
import com.letta.mobile.ui.theme.ChatComposerDimens
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

// letta-mobile-bzvro.13 (F13): the permission-mode chip, in the pointer composer's control row; the
// Touch bar, which has no control row, offers the same choice as a row of its "+" sheet. Both show
// the mode the server confirmed (or, while a change waits for the server's echo, that the change is
// pending) and open the one popover of the modes. Where the owner cannot change the mode the chip and
// the row stay, disabled, with the reason in the note above the composer.

/** The mode's name as the picker and the settings show it. */
@Composable
fun AppServerPermissionMode.displayName(): String = stringResource(
    when (this) {
        AppServerPermissionMode.Standard -> Res.string.composer_permission_mode_standard
        AppServerPermissionMode.AcceptEdits -> Res.string.composer_permission_mode_accept_edits
        AppServerPermissionMode.Strict -> Res.string.composer_permission_mode_strict
        AppServerPermissionMode.Unrestricted -> Res.string.composer_permission_mode_unrestricted
    },
)

/** One line on what the mode does. */
@Composable
fun AppServerPermissionMode.description(): String = stringResource(
    when (this) {
        AppServerPermissionMode.Standard -> Res.string.composer_permission_mode_standard_hint
        AppServerPermissionMode.AcceptEdits -> Res.string.composer_permission_mode_accept_edits_hint
        AppServerPermissionMode.Strict -> Res.string.composer_permission_mode_strict_hint
        AppServerPermissionMode.Unrestricted -> Res.string.composer_permission_mode_unrestricted_hint
    },
)

/**
 * The chip and its picker. On the pointer page it sits in the card's control row beside the
 * model and effort chips; its note stays above the card ([ComposerPermissionModeNoteRow]).
 */
@Composable
internal fun ComposerPermissionModeChip(
    state: ChatPermissionModeUiState,
    onSelect: (AppServerPermissionMode) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = state.shown().displayName()
    val runtimeLabel = state.runtime?.let { permissionModeRuntimeLabel(it) }
    Box {
        ComposerActionChip(
            label = ComposerChipLabel(
                text = chipText(state, label),
                leadingIcon = Lucide.Shield,
            ),
            onClick = { open = true },
            enabled = state.canChange,
            modifier = Modifier
                .testTag(ComposerTestTags.PERMISSION_MODE_CHIP)
                .semantics { contentDescription = runtimeLabel?.let { "$label, $it" } ?: label },
        )
        ComposerPermissionModePicker(state, open, onSelect, onDismiss = { open = false })
    }
}

/**
 * The modes' popover, above whatever it is composed beside (the chip, or the Touch bar's "+"), while
 * [open] and the owner can change the mode. A pick closes it before it is applied.
 */
@Composable
internal fun ComposerPermissionModePicker(
    state: ChatPermissionModeUiState,
    open: Boolean,
    onSelect: (AppServerPermissionMode) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!open || !state.canChange) return
    PermissionModePopover(state, onSelect = { mode -> onDismiss(); onSelect(mode) }, onDismiss = onDismiss)
}

/** The Touch "+" sheet's row for the mode: it names the mode as the chip would and opens the picker. */
@Composable
internal fun permissionModeSheetItem(state: ChatPermissionModeUiState, onOpenPicker: () -> Unit): TouchSheetItem = TouchSheetItem(
    label = stringResource(Res.string.composer_permission_mode_item, chipText(state, state.shown().displayName())),
    icon = Lucide.Shield,
    onClick = onOpenPicker,
    enabled = state.canChange,
)

/** The mode to name: the requested one while it is pending or unconfirmed, else the confirmed one. */
private fun ChatPermissionModeUiState.shown(): AppServerPermissionMode = pending ?: unconfirmed ?: selected

/** The mode's note on its own, above the pointer card or the Touch bar, when there is one to show. */
@Composable
internal fun ComposerPermissionModeNoteRow(state: ChatPermissionModeUiState) {
    if (permissionModeNote(state) == null) return
    Box(modifier = Modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth()) {
        ComposerPermissionModeNote(state)
    }
}

@Composable
private fun ComposerPermissionModeNote(state: ChatPermissionModeUiState) {
    val note = permissionModeNote(state) ?: return
    Text(
        text = note,
        style = MaterialTheme.typography.labelSmall,
        color = if (state.unconfirmed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = LettaDimens.Space.sm)
            .testTag(ComposerTestTags.PERMISSION_MODE_NOTE),
    )
}

/** "Host: <node id short>" or "This device's bundled runtime". */
@Composable
internal fun permissionModeRuntimeLabel(runtime: PermissionModeRuntime): String = when (runtime) {
    is PermissionModeRuntime.Host -> runtime.shortId
        ?.let { stringResource(Res.string.composer_permission_mode_runtime_host, it) }
        ?: stringResource(Res.string.composer_permission_mode_runtime_host_unknown)
    PermissionModeRuntime.Local -> stringResource(Res.string.composer_permission_mode_runtime_local)
}

/** The chip's text: the mode, as pending or unconfirmed when its change is not settled. */
@Composable
private fun chipText(state: ChatPermissionModeUiState, label: String): String = when {
    state.pending != null -> stringResource(Res.string.composer_permission_mode_changing, label)
    state.unconfirmed != null -> stringResource(Res.string.composer_permission_mode_unconfirmed, label)
    else -> label
}

/** The line under the chip: why it is locked, that the last change is unconfirmed, or when a choice applies. */
@Composable
private fun permissionModeNote(state: ChatPermissionModeUiState): String? = state.unavailableReason
    ?: stringResource(Res.string.composer_permission_mode_failed).takeIf { state.unconfirmed != null }
    ?: stringResource(Res.string.composer_permission_mode_not_saved).takeIf { state.notSaved }
    ?: stringResource(Res.string.composer_permission_mode_on_start).takeIf { state.appliesOnStart }

@Composable
private fun PermissionModePopover(
    state: ChatPermissionModeUiState,
    onSelect: (AppServerPermissionMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ComposerPopover(
        width = ChatComposerDimens.permissionModePopoverWidth,
        onDismiss = onDismiss,
        testTag = ComposerTestTags.PERMISSION_MODE_POPOVER,
    ) {
        ComposerPopoverHeader(stringResource(Res.string.composer_permission_mode))
        // letta-mobile-a4tm7: which runtime this governs, so a desktop on its own runtime is not
        // expected to follow a phone on an Iroh host (and two clients of one host visibly share it).
        state.runtime?.let { runtime ->
            Text(
                text = permissionModeRuntimeLabel(runtime),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = LettaDimens.Space.lg, end = LettaDimens.Space.lg, bottom = LettaDimens.Space.xs)
                    .testTag(ComposerTestTags.PERMISSION_MODE_RUNTIME),
            )
        }
        state.options.forEach { mode ->
            val selected = mode == state.selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(mode) }
                    .semantics {
                        role = Role.RadioButton
                        this.selected = selected
                    }
                    .testTag(ComposerTestTags.PERMISSION_MODE_OPTION + mode.name)
                    .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = mode.displayName(), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = mode.description(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selected) {
                    Icon(
                        imageVector = Lucide.Check,
                        contentDescription = null,
                        modifier = Modifier.size(LettaDimens.Control.iconSm),
                    )
                }
            }
        }
    }
}
