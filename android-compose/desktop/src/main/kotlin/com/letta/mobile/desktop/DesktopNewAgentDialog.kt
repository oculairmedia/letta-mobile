package com.letta.mobile.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.PopupMenu as JewelPopupMenu
import org.jetbrains.jewel.ui.component.TextField as JewelTextField

internal data class NewAgentDialogParams(
    val modelOptions: List<Pair<String, String>>,
    val onDismiss: () -> Unit,
    val onCreate: (name: String, model: String?) -> Unit,
)

@Composable
internal fun NewAgentDialog(params: NewAgentDialogParams) {
    var name by remember { mutableStateOf(TextFieldValue("New agent")) }
    var modelValue by remember { mutableStateOf<String?>(null) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    val modelLabel = params.modelOptions.firstOrNull { it.second == modelValue }?.first ?: "Same as current"
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = params.onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(420.dp).clickable(enabled = false) {},
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "New agent",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                NewAgentNameField(name = name, onNameChange = { name = it })
                NewAgentModelPicker(
                    NewAgentModelPickerParams(
                        selection = NewAgentModelSelection(
                            label = modelLabel,
                            value = modelValue,
                            menuOpen = modelMenuOpen,
                        ),
                        modelOptions = params.modelOptions,
                        onMenuOpenChange = { modelMenuOpen = it },
                        onModelSelected = { modelValue = it },
                    ),
                )
                NewAgentDialogActions(
                    onDismiss = params.onDismiss,
                    onCreate = { params.onCreate(name.text.trim(), modelValue) },
                )
            }
        }
    }
}

@Composable
private fun NewAgentNameField(
    name: TextFieldValue,
    onNameChange: (TextFieldValue) -> Unit,
) {
    Text(
        text = "Name",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    JewelTextField(
        value = name,
        onValueChange = onNameChange,
        modifier = Modifier.fillMaxWidth(),
    )
}

private data class NewAgentModelSelection(
    val label: String,
    val value: String?,
    val menuOpen: Boolean,
)

private data class NewAgentModelPickerParams(
    val selection: NewAgentModelSelection,
    val modelOptions: List<Pair<String, String>>,
    val onMenuOpenChange: (Boolean) -> Unit,
    val onModelSelected: (String?) -> Unit,
)

@Composable
private fun NewAgentModelPicker(params: NewAgentModelPickerParams) {
    Text(
        text = "Model",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Box {
        NewAgentModelTrigger(
            label = params.selection.label,
            onOpen = { params.onMenuOpenChange(true) },
        )
        if (params.selection.menuOpen) {
            NewAgentModelMenu(
                NewAgentModelMenuParams(
                    selectedValue = params.selection.value,
                    modelOptions = params.modelOptions,
                    onMenuOpenChange = params.onMenuOpenChange,
                    onModelSelected = params.onModelSelected,
                ),
            )
        }
    }
}

@Composable
private fun NewAgentModelTrigger(label: String, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class NewAgentModelMenuParams(
    val selectedValue: String?,
    val modelOptions: List<Pair<String, String>>,
    val onMenuOpenChange: (Boolean) -> Unit,
    val onModelSelected: (String?) -> Unit,
)

@Composable
private fun NewAgentModelMenu(params: NewAgentModelMenuParams) {
    JewelPopupMenu(
        onDismissRequest = { params.onMenuOpenChange(false); true },
        horizontalAlignment = Alignment.Start,
    ) {
        selectableItem(
            selected = params.selectedValue == null,
            onClick = {
                params.onMenuOpenChange(false)
                params.onModelSelected(null)
            },
        ) {
            DesktopControlText("Same as current")
        }
        params.modelOptions.forEach { (label, value) ->
            selectableItem(
                selected = params.selectedValue == value,
                onClick = {
                    params.onMenuOpenChange(false)
                    params.onModelSelected(value)
                },
            ) {
                DesktopControlText(label)
            }
        }
    }
}

@Composable
private fun NewAgentDialogActions(
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
    ) {
        DesktopOutlinedButton(onClick = onDismiss) { DesktopButtonContent("Cancel") }
        DesktopDefaultButton(onClick = onCreate) {
            DesktopButtonContent("Create agent")
        }
    }
}
