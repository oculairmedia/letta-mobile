package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.Tool
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.icons.LettaIcons

@Composable
internal fun ChatComposerActionSheet(
    show: Boolean,
    availableTools: List<Tool>,
    onDismiss: () -> Unit,
    onAttachImage: () -> Unit,
    onToolSelected: (Tool) -> Unit,
) {
    ActionSheet(
        show = show,
        onDismiss = onDismiss,
        title = stringResource(R.string.composer_actions_title),
    ) {
        ActionSheetItem(
            text = stringResource(R.string.action_attach_image),
            icon = LettaIcons.Add,
            onClick = onAttachImage,
        )
        Column(
            modifier = Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            availableTools.forEach { tool ->
                ActionSheetItem(
                    text = tool.name,
                    icon = LettaIcons.Tool,
                    supportingText = stringResource(R.string.composer_action_tool_template_supporting),
                    onClick = { onToolSelected(tool) },
                )
            }
        }
    }
}
