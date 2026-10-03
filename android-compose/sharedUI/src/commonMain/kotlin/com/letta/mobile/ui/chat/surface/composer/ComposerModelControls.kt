package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Lucide
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_effort_default
import com.letta.mobile.sharedui.resources.composer_effort_header
import com.letta.mobile.sharedui.resources.composer_model_fallback
import com.letta.mobile.sharedui.resources.composer_model_selected
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatModelHandle
import com.letta.mobile.ui.chat.session.ChatModelOption
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatComposerDimens
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/**
 * The model chip (and, when the current model has named reasoning efforts, the effort chip).
 * The chip opens the host's own picker when it has one, otherwise the shared sheet.
 */
@Composable
internal fun ComposerModelControls(
    model: ChatModelUiState,
    actions: ChatActions,
    host: ChatSurfaceHost,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    ComposerActionChip(
        label = ComposerChipLabel(model.currentLabel.ifBlank { stringResource(Res.string.composer_model_fallback) }),
        onClick = { host.openModelPicker?.invoke() ?: run { sheetOpen = true } },
        enabled = !model.isSwitching,
        modifier = Modifier.testTag(ComposerTestTags.MODEL_CHIP),
    )
    val efforts = currentModelEfforts(model)
    val handle = model.currentHandle
    if (efforts.isNotEmpty() && handle != null) {
        ComposerEffortChip(
            current = model.currentEffort,
            efforts = efforts,
            onSelect = { choice -> actions.selectModel(ChatModelHandle(handle), choice) },
        )
    }
    if (sheetOpen) {
        ComposerModelPickerSheet(
            model = model,
            onSelect = { option ->
                sheetOpen = false
                if (option.handle != model.currentHandle) {
                    actions.selectModel(ChatModelHandle(option.handle), ReasoningEffortChoice.Unchanged)
                }
            },
            onDismiss = { sheetOpen = false },
        )
    }
}

private fun currentModelEfforts(model: ChatModelUiState): List<String> =
    model.options.firstOrNull { it.handle == model.currentHandle }?.reasoningEfforts.orEmpty()

/**
 * The reasoning-effort chip and popover, lifted from desktop's ComposerEffortChip. Unlike the
 * desktop chip (local state nothing read) it writes the conversation's effort through
 * [ChatActions.selectModel]. Desktop's decorative Thinking toggle is not carried over.
 */
@Composable
private fun ComposerEffortChip(
    current: String?,
    efforts: List<String>,
    onSelect: (ReasoningEffortChoice) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val defaultLabel = stringResource(Res.string.composer_effort_default)
    Box {
        ComposerActionChip(
            label = ComposerChipLabel(current ?: defaultLabel, Lucide.Gauge),
            onClick = { open = !open },
            modifier = Modifier.testTag(ComposerTestTags.EFFORT_CHIP),
        )
        if (open) {
            ComposerPopover(
                width = ChatComposerDimens.effortPopoverWidth,
                onDismiss = { open = false },
                testTag = ComposerTestTags.EFFORT_CHIP + "-popover",
            ) {
                ComposerPopoverHeader(stringResource(Res.string.composer_effort_header))
                EffortRow(label = defaultLabel, selected = current == null) {
                    open = false
                    onSelect(ReasoningEffortChoice.ProviderDefault)
                }
                efforts.forEach { effort ->
                    EffortRow(label = effort, selected = effort == current) {
                        open = false
                        onSelect(ReasoningEffortChoice.Named(effort))
                    }
                }
            }
        }
    }
}

@Composable
private fun EffortRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = LettaIcons.Check,
                contentDescription = stringResource(Res.string.composer_model_selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(LettaDimens.Control.icon),
            )
        }
    }
}

/** Options grouped by provider in first-seen order; options without one go under [otherLabel]. */
internal fun groupModelOptions(
    options: List<ChatModelOption>,
    query: String,
    otherLabel: String,
): List<Pair<String, List<ChatModelOption>>> {
    val needle = query.trim()
    return options
        .filter { needle.isEmpty() || it.matches(needle) }
        .groupBy { it.provider?.takeIf(String::isNotBlank) ?: otherLabel }
        .toList()
}

private fun ChatModelOption.matches(needle: String): Boolean =
    label.contains(needle, ignoreCase = true) ||
        handle.contains(needle, ignoreCase = true) ||
        provider?.contains(needle, ignoreCase = true) == true
