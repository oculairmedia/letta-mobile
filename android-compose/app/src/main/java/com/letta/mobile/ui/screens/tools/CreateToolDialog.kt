package com.letta.mobile.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.components.MultiFieldInputDialog
import com.letta.mobile.R

@Composable
fun CreateToolDialog(
    onDismiss: () -> Unit,
    onCreate: (sourceCode: String) -> Unit,
) {
    var sourceCode by remember { mutableStateOf("def my_tool(arg: str) -> str:\n    \"\"\"Describe what this tool does.\"\"\"\n    return f\"Result: {arg}\"\n") }

    MultiFieldInputDialog(
        show = true,
        title = stringResource(R.string.screen_create_tool_title),
        confirmText = stringResource(R.string.action_create),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmEnabled = sourceCode.isNotBlank(),
        onConfirm = { onCreate(sourceCode) },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.screen_create_tool_source_code_helper),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = sourceCode,
                onValueChange = { sourceCode = it },
                label = { Text(stringResource(R.string.screen_create_tool_source_code_label)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 8,
                maxLines = 15,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}
