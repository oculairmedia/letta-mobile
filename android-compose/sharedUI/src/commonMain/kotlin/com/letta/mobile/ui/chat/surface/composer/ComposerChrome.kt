package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.Lucide
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_dismiss_error
import com.letta.mobile.sharedui.resources.composer_hint
import com.letta.mobile.sharedui.resources.composer_working_directory
import com.letta.mobile.sharedui.resources.composer_working_directory_change
import com.letta.mobile.sharedui.resources.composer_working_directory_loading
import com.letta.mobile.sharedui.resources.composer_working_directory_unknown
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.session.ChatWorkingDirectoryUiState
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatComposerDimens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaMotionTokens
import org.jetbrains.compose.resources.stringResource

/**
 * The coding agent's working directory, with a picker to change it. Lifted from desktop's
 * DesktopWorkingDirectoryRow; picking goes through the host (a native folder dialog), which
 * reports the choice back via ChatActions.changeWorkingDirectory.
 */
@Composable
internal fun ComposerWorkingDirectoryRow(
    state: ChatWorkingDirectoryUiState,
    onPick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .widthIn(max = ChatColumnMaxWidth)
            .fillMaxWidth()
            .clickable(enabled = onPick != null && !state.isLoading) { onPick?.invoke() }
            .testTag(ComposerTestTags.WORKING_DIRECTORY)
            .padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Icon(
            imageVector = Lucide.Folder,
            contentDescription = stringResource(Res.string.composer_working_directory),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(LettaDimens.Control.iconSm),
        )
        val branch = state.branch
        val path = state.path
        Text(
            text = when {
                state.isLoading -> stringResource(Res.string.composer_working_directory_loading)
                branch != null && path != null -> "$path ($branch)"
                branch != null -> branch
                else -> path ?: stringResource(Res.string.composer_working_directory_unknown)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onPick != null) {
            Text(
                text = stringResource(Res.string.composer_working_directory_change),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** A composer error (attachment refused, send blocked) shown inline above the prompt. */
@Composable
internal fun ComposerErrorRow(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth().testTag(ComposerTestTags.ERROR),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = LettaDimens.Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(vertical = LettaDimens.Space.sm),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = LettaIcons.Close,
                    contentDescription = stringResource(Res.string.composer_dismiss_error),
                    modifier = Modifier.size(LettaDimens.Control.icon),
                )
            }
        }
    }
}

/**
 * The keyboard-affordance strip under the composer. Faded rather than removed: it keeps its
 * space, so typing the first character dims a line instead of shifting the composer. Lifted
 * from desktop's ComposerHintRow.
 */
@Composable
internal fun ComposerHintRow(visible: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = LettaMotionTokens.CONTENT_SIZE_MILLIS),
        label = "composerHintAlpha",
    )
    Row(
        modifier = Modifier
            .widthIn(max = ChatColumnMaxWidth)
            .fillMaxWidth()
            .padding(start = LettaDimens.Space.sm, top = LettaDimens.Space.hair, bottom = LettaDimens.Space.hair)
            .graphicsLayer { this.alpha = alpha }
            .testTag(ComposerTestTags.HINT)
            // Hidden copy must not be announced or hit-tested.
            .clearAndSetSemantics { },
    ) {
        Text(
            text = stringResource(Res.string.composer_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ChatComposerDimens.hintAlpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
