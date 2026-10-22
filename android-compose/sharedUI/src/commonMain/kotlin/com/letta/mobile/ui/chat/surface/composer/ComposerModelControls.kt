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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Lucide
import com.letta.mobile.data.composer.ComposerEffort
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelCatalogNormalizer
import com.letta.mobile.data.repository.modelcontrol.ModelPickerBinding
import com.letta.mobile.data.repository.modelcontrol.ModelPickerSource
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_effort_default
import com.letta.mobile.sharedui.resources.composer_effort_header
import com.letta.mobile.sharedui.resources.composer_model_fallback
import com.letta.mobile.sharedui.resources.composer_model_selected
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatModelHandle
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.context.ModelPickerHost
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.providers.icons.ProviderIcons
import com.letta.mobile.ui.theme.ChatComposerDimens
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.stringResource

/**
 * The model chip (and, when the current model has named reasoning efforts, the effort chip).
 * The chip opens the shared picker the host binds ([ChatSurfaceHost.modelPicker]).
 */
@Composable
internal fun ComposerModelControls(
    model: ChatModelUiState,
    actions: ChatActions,
    host: ChatSurfaceHost,
) {
    val picker = host.modelPicker
    ComposerActionChip(
        label = ComposerChipLabel(
            text = model.currentLabel.ifBlank { stringResource(Res.string.composer_model_fallback) },
            // The provider the current model routes through ("lmstudio/…"), when the app has its logo.
            leadingIcon = model.currentHandle?.let { ProviderIcons.forProvider(ModelCatalogNormalizer.providerPrefix(it)) },
        ),
        onClick = { picker?.open() },
        enabled = !model.isSwitching && picker != null,
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
}

/** The current model's efforts, None … Max (the full upstream set, in order). */
private fun currentModelEfforts(model: ChatModelUiState): List<String> =
    ComposerEffort.sorted(model.options.firstOrNull { it.handle == model.currentHandle }?.reasoningEfforts.orEmpty())

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
            label = ComposerChipLabel(current?.let { ComposerEffort.labelOf(it) } ?: defaultLabel, Lucide.Gauge),
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
                    EffortRow(label = ComposerEffort.labelOf(effort), selected = effort == current) {
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

/**
 * The open picker over a plain [models] list (the agent editor's Model row), re-read through
 * [reload]: [onPick] gets the picked model's value; it shows no effort chips and no Models sheet.
 */
@Composable
internal fun rememberModelListPicker(
    models: StateFlow<List<LlmModel>>,
    reload: suspend () -> Unit,
    onPick: (String) -> Unit,
): ModelPickerBinding {
    val latestPick by rememberUpdatedState(onPick)
    val latestReload by rememberUpdatedState(reload)
    return remember(models) {
        ModelPickerBinding(
            source = ModelPickerSource.of(models) { latestReload() },
            onModelSelected = { entry -> latestPick(entry.value) },
            onEffortSelected = null,
            editor = null,
            onAddProvider = null,
            onModelsEdited = {},
        ).also(ModelPickerBinding::open)
    }
}

/** The one model picker over [binding], shown until a pick or a dismiss closes it, then [onDismiss]. */
@Composable
internal fun ModelListPicker(binding: ModelPickerBinding, selectedValue: String?, onDismiss: () -> Unit) {
    val latestDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(binding) {
        snapshotFlow { binding.pickerOpen }.first { open -> !open }
        latestDismiss()
    }
    ModelPickerHost(binding, selectedValue)
}
