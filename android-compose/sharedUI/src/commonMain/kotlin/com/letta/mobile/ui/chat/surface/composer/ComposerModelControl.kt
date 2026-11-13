package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.letta.mobile.data.composer.ComposerEffort
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelCatalogNormalizer
import com.letta.mobile.data.repository.modelcontrol.ModelPickerBinding
import com.letta.mobile.data.repository.modelcontrol.ModelPickerSource
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_effort_default
import com.letta.mobile.sharedui.resources.composer_model_control
import com.letta.mobile.sharedui.resources.composer_model_effort_state
import com.letta.mobile.sharedui.resources.composer_model_fallback
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatModelHandle
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.context.ModelPickerHost
import com.letta.mobile.ui.modelcontrol.ModelPickerEffortSection
import com.letta.mobile.ui.providers.icons.ProviderIcons
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.stringResource

/**
 * The prompt's one model control: the current model, its effort a quiet suffix where the model has
 * effort levels ("Opus 5.5 · High"). It opens the shared picker the host binds
 * ([ChatSurfaceHost.modelPicker]), which lists the models and, under them, the current model's
 * effort ([composerEffortSection]). One focus stop, announced as the model and effort control with
 * the current model and effort as its state.
 */
@Composable
internal fun ComposerModelControl(model: ChatModelUiState, host: ChatSurfaceHost) {
    val picker = host.modelPicker
    val modelLabel = model.currentLabel.ifBlank { stringResource(Res.string.composer_model_fallback) }
    val effortLabel = currentEffortLabel(model)
    val controlLabel = stringResource(Res.string.composer_model_control)
    val state = effortLabel?.let { stringResource(Res.string.composer_model_effort_state, modelLabel, it) } ?: modelLabel
    ComposerActionChip(
        label = ComposerChipLabel(
            text = modelLabel,
            // The provider the current model routes through ("lmstudio/…"), when the app has its logo.
            leadingIcon = model.currentHandle?.let { ProviderIcons.forProvider(ModelCatalogNormalizer.providerPrefix(it)) },
            suffix = effortLabel,
        ),
        onClick = { picker?.open() },
        enabled = !model.isSwitching && picker != null,
        modifier = Modifier
            .testTag(ComposerTestTags.MODEL_CHIP)
            .semantics {
                contentDescription = controlLabel
                stateDescription = state
            },
    )
}

/** The effort the control's suffix names: the one in force, "Default" for none; null for a model without levels. */
@Composable
private fun currentEffortLabel(model: ChatModelUiState): String? {
    if (model.currentHandle == null || currentModelEfforts(model).isEmpty()) return null
    return model.currentEffort?.let { ComposerEffort.labelOf(it) } ?: stringResource(Res.string.composer_effort_default)
}

/** The current model's efforts, None … Max (the full upstream set, in order). */
private fun currentModelEfforts(model: ChatModelUiState): List<String> =
    ComposerEffort.sorted(model.options.firstOrNull { it.handle == model.currentHandle }?.reasoningEfforts.orEmpty())

/**
 * The picker's effort section for the current model: its levels, the one in force, and a choice
 * written through [ChatActions.selectModel] as the conversation's effort. Null without a current model.
 */
@Composable
internal fun composerEffortSection(model: ChatModelUiState?, actions: ChatActions): ModelPickerEffortSection? {
    val handle = model?.currentHandle ?: return null
    return ModelPickerEffortSection(
        modelLabel = model.currentLabel.ifBlank { stringResource(Res.string.composer_model_fallback) },
        efforts = currentModelEfforts(model),
        current = model.currentEffort,
        onSelect = { choice -> actions.selectModel(ChatModelHandle(handle), choice) },
    )
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
