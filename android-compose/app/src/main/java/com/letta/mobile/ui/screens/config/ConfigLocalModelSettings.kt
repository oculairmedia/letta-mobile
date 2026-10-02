package com.letta.mobile.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import ca.oculair.meridian.R
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

// The server card's local model settings: the model file, its tuning, and the local provider.

/**
 * The on-device model's settings. Each part below emits its fields straight into this column, so
 * they keep the column's spacing.
 */
@Composable
internal fun LocalModelSettingsItem(
    state: ConfigUiState,
    callbacks: LocalModelCallbacks,
    embeddedModel: EmbeddedModelCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Text(
            text = stringResource(R.string.screen_config_on_device_model_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        HuggingFaceTokenField(state = state, callbacks = callbacks)
        EmbeddedModelCatalogSection(state = state, callbacks = embeddedModel)
        LocalModelFileFields(state = state, callbacks = callbacks, embeddedModel = embeddedModel)
        LocalModelTuningFields(state = state, callbacks = callbacks)
        LocalProviderFields(state = state, callbacks = callbacks)
    }
}

@Composable
private fun HuggingFaceTokenField(state: ConfigUiState, callbacks: LocalModelCallbacks) {
    var hfTokenVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = state.huggingFaceToken,
        onValueChange = callbacks.onHuggingFaceTokenChange,
        label = { Text(stringResource(R.string.screen_config_hugging_face_token)) },
        placeholder = { Text(stringResource(R.string.screen_config_hugging_face_token_placeholder)) },
        visualTransformation = if (hfTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = { Icon(LettaIcons.Key, null) },
        trailingIcon = {
            TokenVisibilityButton(
                visible = hfTokenVisible,
                onToggle = { hfTokenVisible = !hfTokenVisible },
            )
        },
        singleLine = true,
    )
}

/** The model file's path, the import button that fills it, and the handle the model is served as. */
@Composable
private fun LocalModelFileFields(
    state: ConfigUiState,
    callbacks: LocalModelCallbacks,
    embeddedModel: EmbeddedModelCallbacks,
) {
    OutlinedTextField(
        value = state.localModelPath,
        onValueChange = callbacks.onLocalModelPathChange,
        label = { Text(stringResource(R.string.screen_config_local_model_path)) },
        placeholder = { Text(stringResource(R.string.screen_config_local_model_path_placeholder)) },
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = { Icon(LettaIcons.Database, null) },
        singleLine = true,
    )
    LocalModelImportButton(state = state, embeddedModel = embeddedModel)
    OutlinedTextField(
        value = state.localModelHandle,
        onValueChange = callbacks.onLocalModelHandleChange,
        label = { Text(stringResource(R.string.screen_config_local_model_handle)) },
        placeholder = { Text(stringResource(R.string.screen_config_local_model_handle_placeholder)) },
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = { Icon(LettaIcons.Psychology, null) },
        singleLine = true,
    )
}

@Composable
private fun LocalModelImportButton(state: ConfigUiState, embeddedModel: EmbeddedModelCallbacks) {
    OutlinedButton(
        onClick = embeddedModel.onImportLocalModel,
        enabled = !state.isImportingLocalModel,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.isImportingLocalModel) {
            CircularProgressIndicator(
                modifier = Modifier.size(LettaDimens.Control.icon),
                strokeWidth = LettaDimens.Space.hair,
            )
            Spacer(modifier = Modifier.width(LettaDimens.Space.sm))
        } else {
            Icon(LettaIcons.FileOpen, contentDescription = null)
            Spacer(modifier = Modifier.width(LettaDimens.Space.sm))
        }
        Text(
            stringResource(
                if (state.isImportingLocalModel) {
                    R.string.screen_config_local_model_importing
                } else {
                    R.string.screen_config_local_model_import
                }
            )
        )
    }
}

/** The accelerator the model runs on and its token budget. */
@Composable
private fun LocalModelTuningFields(state: ConfigUiState, callbacks: LocalModelCallbacks) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        Text(
            text = stringResource(R.string.screen_config_local_model_accelerator),
            style = MaterialTheme.typography.bodyMedium,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            LocalModelAcceleratorOption.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = state.localModelAccelerator == option.value,
                    onClick = {
                        HapticEffects.segmentTick(
                            haptic,
                            view,
                            enabled = state.localModelAccelerator != option.value,
                        )
                        callbacks.onLocalModelAcceleratorChange(option.value)
                    },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = LocalModelAcceleratorOption.entries.size,
                    ),
                    label = { Text(stringResource(option.labelRes)) },
                )
            }
        }
    }
    OutlinedTextField(
        value = state.localModelMaxTokens,
        onValueChange = callbacks.onLocalModelMaxTokensChange,
        label = { Text(stringResource(R.string.screen_config_local_model_max_tokens)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        leadingIcon = { Icon(LettaIcons.Settings, null) },
        singleLine = true,
    )
}

/** An OpenAI-compatible provider on the local network, as an alternative to the on-device model. */
@Composable
private fun LocalProviderFields(state: ConfigUiState, callbacks: LocalModelCallbacks) {
    Text(
        text = stringResource(R.string.screen_config_local_provider_section),
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        text = stringResource(R.string.screen_config_local_provider_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = state.localProviderBaseUrl,
        onValueChange = callbacks.onLocalProviderBaseUrlChange,
        label = { Text(stringResource(R.string.screen_config_local_provider_base_url)) },
        placeholder = { Text("http://192.168.1.10:8082/v1") },
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = { Icon(LettaIcons.Link, null) },
        singleLine = true,
    )
    OutlinedTextField(
        value = state.localProviderApiKey,
        onValueChange = callbacks.onLocalProviderApiKeyChange,
        label = { Text(stringResource(R.string.screen_config_local_provider_api_key)) },
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = { Icon(LettaIcons.Key, null) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
    )
}

private enum class LocalModelAcceleratorOption(
    val value: String,
    val labelRes: Int,
) {
    CPU("cpu", R.string.screen_config_local_model_accelerator_cpu),
    GPU("gpu", R.string.screen_config_local_model_accelerator_gpu),
    NPU("npu", R.string.screen_config_local_model_accelerator_npu),
}
