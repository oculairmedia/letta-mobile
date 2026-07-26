package com.letta.mobile.feature.chat.screen.messageactions

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.components.SelectableTextDialog

@Composable
internal fun MessageTextSelectionDialog(
    text: String,
    onDismiss: () -> Unit,
) {
    SelectableTextDialog(
        title = stringResource(R.string.message_action_select_text),
        text = text,
        closeText = stringResource(R.string.action_close),
        onDismiss = onDismiss,
    )
}
