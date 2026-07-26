package com.letta.mobile.feature.chat.screen.messageactions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.icons.LettaIcons
import java.util.Locale

@Composable
internal fun MessageActionsSheet(
    message: UiMessage,
    copyText: String,
    availability: MessageActionAvailability,
    show: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSendAgain: () -> Unit,
) {
    var showTextSelection by remember(message.id) { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        configuration.locales[0]?.let { Locale.forLanguageTag(it.toLanguageTag()) }
            ?: Locale.getDefault()
    }
    val timestampHeader = remember(message.timestamp, locale) {
        formatMessageActionTimestamp(message.timestamp, locale = locale)
    } ?: stringResource(R.string.message_actions_title)

    ActionSheet(
        show = show,
        onDismiss = onDismiss,
        title = timestampHeader,
    ) {
        if (availability.canSendAgain) {
            ActionSheetItem(
                text = stringResource(R.string.message_action_send_again),
                icon = LettaIcons.Send,
                onClick = {
                    onDismiss()
                    onSendAgain()
                },
            )
        }
        if (availability.canCopy) {
            ActionSheetItem(
                text = stringResource(R.string.action_copy),
                icon = LettaIcons.Copy,
                onClick = {
                    onDismiss()
                    onCopy()
                },
            )
        }
        if (availability.canSelectText) {
            ActionSheetItem(
                text = stringResource(R.string.message_action_select_text),
                icon = LettaIcons.ManageSearch,
                onClick = {
                    onDismiss()
                    showTextSelection = true
                },
            )
        }
    }

    if (showTextSelection) {
        MessageTextSelectionDialog(
            text = copyText,
            onDismiss = { showTextSelection = false },
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
