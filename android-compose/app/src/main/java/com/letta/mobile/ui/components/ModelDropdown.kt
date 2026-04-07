package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.letta.mobile.data.model.LlmModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDropdown(
    selectedModel: String,
    models: List<LlmModel>,
    onModelSelected: (String) -> Unit,
    onLoadModels: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Model",
) {
    var expanded by remember { mutableStateOf(false) }
    var filterText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        onLoadModels()
    }

    val filtered = remember(models, filterText) {
        if (filterText.isBlank()) models
        else models.filter {
            it.name.contains(filterText, ignoreCase = true) ||
                it.providerType.contains(filterText, ignoreCase = true)
        }
    }

    val grouped = remember(filtered) {
        filtered.groupBy { it.providerType }.toSortedMap()
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedModel,
            onValueChange = { filterText = it },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable),
            label = { Text(label) },
            readOnly = false,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (grouped.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No models available", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {},
                    enabled = false,
                )
            } else {
                grouped.forEach { (provider, providerModels) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = provider.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                    providerModels.forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(text = model.name, style = MaterialTheme.typography.bodyMedium)
                                    model.contextWindow?.let { contextWindow ->
                                        Text(
                                            text = "${contextWindow / 1000}K context",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onModelSelected(model.name)
                                filterText = ""
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}
