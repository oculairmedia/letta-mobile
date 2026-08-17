package com.letta.mobile.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.runtime.LocalRuntimeProviderConfig
import com.letta.mobile.data.runtime.LocalRuntimeProviderStatus
import org.jetbrains.jewel.ui.component.Text as JewelText
import org.jetbrains.jewel.ui.component.TextField as JewelTextField

/**
 * Desktop state for the "Local runtime provider" settings section — the
 * OpenAI-compatible endpoint (vLLM, llama.cpp, Ollama, etc.) the BUNDLED
 * local letta-code runtime should use as its model provider. See
 * `LocalRuntimeProviderAuthFile.kt` in sharedLogic for how this is stored.
 */
internal data class DesktopLocalRuntimeProviderState(
    val status: LocalRuntimeProviderStatus?,
    val isSaving: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

internal data class DesktopLocalRuntimeProviderActions(
    val onLoad: () -> Unit,
    val onSave: (baseUrl: String, apiKey: String?) -> Unit,
)

@Composable
internal fun LocalRuntimeProviderSettingsCard(
    state: DesktopLocalRuntimeProviderState,
    actions: DesktopLocalRuntimeProviderActions,
) {
    LaunchedEffect(Unit) { actions.onLoad() }

    var baseUrlInput by remember(state.status?.baseUrl) {
        mutableStateOf(TextFieldValue(state.status?.baseUrl.orEmpty()))
    }
    var apiKeyInput by remember(state.status) { mutableStateOf(TextFieldValue("")) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Local runtime provider",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Point the bundled local Letta Code runtime at an OpenAI-compatible " +
                    "endpoint — vLLM, llama.cpp, Ollama, LM Studio, or similar. This writes the " +
                    "runtime's own credential file, so a terminal-driven \"letta setup\" sees the " +
                    "same configuration.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LocalRuntimeProviderStatusLine(state)
            DesktopSettingsFieldLabel("Base URL")
            JewelTextField(
                value = baseUrlInput,
                onValueChange = { baseUrlInput = it },
                placeholder = { JewelText("http://localhost:11434/v1") },
                modifier = Modifier.fillMaxWidth(),
            )
            DesktopSettingsFieldLabel("API key")
            JewelTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                placeholder = {
                    JewelText(
                        if (state.status?.hasApiKey == true) "Optional — saved key hidden" else "Optional",
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DesktopDefaultButton(
                    enabled = !state.isSaving && LocalRuntimeProviderConfig.isValidBaseUrl(baseUrlInput.text),
                    onClick = {
                        actions.onSave(
                            baseUrlInput.text.trim(),
                            apiKeyInput.text.trim().takeIf { it.isNotBlank() },
                        )
                    },
                ) {
                    DesktopButtonContent(if (state.isSaving) "Saving…" else "Save")
                }
            }
        }
    }
}

@Composable
private fun LocalRuntimeProviderStatusLine(state: DesktopLocalRuntimeProviderState) {
    val message = state.message
    val color = when {
        message != null && state.isError -> MaterialTheme.colorScheme.error
        message != null -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = message ?: when {
        state.status == null -> "Loading current configuration…"
        state.status.isConfigured ->
            "Configured: ${state.status.baseUrl}" + if (state.status.hasApiKey) " (API key set)" else " (no API key)"
        else -> "Not configured — the bundled runtime has no local model provider yet."
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}
