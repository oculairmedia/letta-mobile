package com.letta.mobile.desktop.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelBadge
import com.letta.mobile.data.model.ModelCatalog
import com.letta.mobile.data.model.ModelOption
import org.jetbrains.jewel.ui.component.TextField as JewelTextField
import com.letta.mobile.ui.theme.LettaDimens

private val AccentTeal = Color(0xFF00BFA5)

/**
 * Searchable, provider-grouped model picker (Penpot "Model Picker · sheet"):
 * a centered sheet with a search field, provider sections, capability sublabels,
 * BYOK/LOCAL badges, and a selected check. Grouping/metadata come from the
 * shared [ModelCatalog] so the desktop and mobile pickers stay in sync.
 */
@Composable
internal fun DesktopModelPickerSheet(
    models: List<LlmModel>,
    selectedValue: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onEditModels: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val groups = remember(models, selectedValue) { ModelCatalog.group(models, selectedValue) }
    val filtered = remember(groups, query.text) { ModelCatalog.filter(groups, query.text) }
    val selectedOptionValue = remember(models, selectedValue) {
        ModelCatalog.selectedModel(models, selectedValue)
            ?.let { ModelCatalog.selectionValue(models, it) }
    }
    // The sheet opens for the express purpose of searching models, so the
    // search field takes focus immediately — otherwise typing goes nowhere and
    // the user has to click the field first. Mirrors the quick-query palette
    // and the new-conversation surface.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .width(560.dp)
                .heightIn(max = 540.dp)
                // Absorb clicks so they don't fall through to the dismiss scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(LettaDimens.Radius.lg),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = LettaDimens.Space.sm,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(LettaDimens.Space.lg),
                    )
                    JewelTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1.dp)
                        .heightIn(min = 1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                text = "No models match \"${query.text}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(LettaDimens.Space.xl),
                            )
                        }
                    }
                    filtered.forEach { group ->
                        item(key = "h-${group.provider}") { ProviderHeader(group.provider) }
                        items(group.models, key = { "${group.provider}-${it.value}" }) { option ->
                            ModelRow(
                                option = option,
                                selected = option.value == selectedOptionValue,
                                onClick = {
                                    val value = if (option.value == selectedOptionValue) {
                                        selectedValue ?: option.value
                                    } else {
                                        option.value
                                    }
                                    onSelect(value)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onEditModels != null) {
                        Row(
                            modifier = Modifier.clickable(onClick = onEditModels),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = null,
                                tint = AccentTeal,
                                modifier = Modifier.size(LettaDimens.Space.lg),
                            )
                            Text(
                                text = "Edit models…",
                                style = MaterialTheme.typography.labelLarge,
                                color = AccentTeal,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "↑↓ select · ⏎ select · esc",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderHeader(provider: String) {
    Text(
        text = provider.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = LettaDimens.Space.lg, end = LettaDimens.Space.lg, top = LettaDimens.Space.md, bottom = LettaDimens.Space.xs),
    )
}

@Composable
private fun ModelRow(
    option: ModelOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Text(
            text = option.displayName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        option.sublabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        option.badge?.let { ModelBadgePill(it) }
        if (selected) {
            Box(
                modifier = Modifier.size(LettaDimens.Space.xl).background(AccentTeal, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = "Selected",
                    tint = Color.White,
                    modifier = Modifier.size(LettaDimens.Space.md),
                )
            }
        }
    }
}

@Composable
private fun ModelBadgePill(badge: ModelBadge) {
    val label = when (badge) {
        ModelBadge.Byok -> "BYOK"
        ModelBadge.Local -> "LOCAL"
    }
    Box(
        modifier = Modifier
            .border(1.dp, AccentTeal.copy(alpha = 0.55f), RoundedCornerShape(LettaDimens.Radius.sm))
            .padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.hair),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = AccentTeal,
        )
    }
}
