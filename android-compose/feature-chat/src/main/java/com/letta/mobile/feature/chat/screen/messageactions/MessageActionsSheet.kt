package com.letta.mobile.feature.chat.screen.messageactions

import com.letta.mobile.data.chat.projection.MessageActionAvailability

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.icons.LettaIcons
import java.util.Locale

@Stable
internal data class MessageActionsSheetState(
    val message: UiMessage,
    val copyText: String,
    val availability: MessageActionAvailability,
    val show: Boolean,
)

@Stable
internal data class MessageActionsSheetActions(
    val onDismiss: () -> Unit,
    val onCopy: () -> Unit,
    val onSendAgain: () -> Unit,
    /** letta-mobile-7mys7: the branch actions; null when the screen cannot branch. */
    val branch: MessageBranchActions? = null,
)

@Composable
internal fun MessageActionsSheet(
    state: MessageActionsSheetState,
    actions: MessageActionsSheetActions,
) {
    var showTextSelection by remember(state.message.id) { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        configuration.locales[0]?.let { Locale.forLanguageTag(it.toLanguageTag()) }
            ?: Locale.getDefault()
    }
    val timestampHeader = remember(state.message.timestamp, locale) {
        formatMessageActionTimestamp(state.message.timestamp, locale = locale)
    } ?: stringResource(R.string.message_actions_title)
    val availableHeight = LocalConfiguration.current.screenHeightDp.dp
    val actionListMaxHeight = (availableHeight - 112.dp)
        .coerceAtLeast(96.dp)
        .coerceAtMost(320.dp)

    ActionSheet(
        show = state.show,
        onDismiss = actions.onDismiss,
        title = timestampHeader,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = actionListMaxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            BranchActionItems(state, actions)
            if (state.availability.canSendAgain) {
                ActionSheetItem(
                    text = stringResource(R.string.message_action_send_again),
                    icon = LettaIcons.Send,
                    onClick = {
                        actions.onDismiss()
                        actions.onSendAgain()
                    },
                )
            }
            if (state.availability.canCopy) {
                ActionSheetItem(
                    text = stringResource(R.string.action_copy),
                    icon = LettaIcons.Copy,
                    onClick = {
                        actions.onDismiss()
                        actions.onCopy()
                    },
                )
            }
            if (state.availability.canSelectText) {
                ActionSheetItem(
                    text = stringResource(R.string.message_action_select_text),
                    icon = LettaIcons.ManageSearch,
                    onClick = {
                        actions.onDismiss()
                        showTextSelection = true
                    },
                )
            }
        }
    }

    if (showTextSelection) {
        MessageTextSelectionDialog(
            text = state.copyText,
            onDismiss = { showTextSelection = false },
        )
    }
}

/** letta-mobile-7mys7: Edit and resend, Rerun and Fork from here, above the sheet's other actions. */
@Composable
private fun BranchActionItems(state: MessageActionsSheetState, actions: MessageActionsSheetActions) {
    val branch = actions.branch ?: return
    val items = listOf(
        Triple(state.availability.canEdit, R.string.message_action_edit_and_resend, LettaIcons.Edit) to branch.onEditAndResend,
        Triple(state.availability.canRerun, R.string.message_action_rerun, LettaIcons.Refresh) to branch.onRerun,
        Triple(state.availability.canFork, R.string.message_action_fork, LettaIcons.ForkRight) to branch.onFork,
    )
    for ((item, onClick) in items) {
        val (available, label, icon) = item
        if (!available) continue
        ActionSheetItem(
            text = stringResource(label),
            icon = icon,
            onClick = {
                actions.onDismiss()
                onClick(state.message)
            },
        )
    }
}

internal fun copyMessageText(
    context: Context,
    text: String,
) {
    val label = context.getString(R.string.action_copy)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, context.getString(R.string.action_copied), Toast.LENGTH_SHORT).show()
    }
}
