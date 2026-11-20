package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.letta.mobile.data.servicecredentials.ServiceCredentialDraft
import com.letta.mobile.data.servicecredentials.ServiceCredentialStatus
import com.letta.mobile.data.servicecredentials.ServiceId
import com.letta.mobile.ui.host.LettaConfirmPane
import com.letta.mobile.ui.host.LettaConfirmRequest
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/**
 * A service's form, kept in memory only: a typed secret never enters saved UI state, leaves the
 * device once inside the write-only command, and is dropped with the form.
 */
@Stable
private class ServiceFormState(service: ServiceCredentialStatus) {
    var baseUrl: String by mutableStateOf(service.baseUrl ?: service.defaultBaseUrl.orEmpty())
    var secret: String by mutableStateOf("")
    var confirmRemove: Boolean by mutableStateOf(false)
    val config = mutableStateMapOf<String, String>().apply {
        service.configFields.forEach { field -> put(field.key, service.config[field.key] ?: field.defaultValue.orEmpty()) }
    }

    /** Saving needs a secret unless the host already keeps one. */
    fun canSave(service: ServiceCredentialStatus): Boolean = service.hasSecret || secret.isNotBlank()

    fun draft(): ServiceCredentialDraft = ServiceCredentialDraft(baseUrl, secret, config.toMap())

    override fun toString(): String = "ServiceFormState(secretTyped=${secret.isNotBlank()})"
}

/** The inline form under a service card: address (where it may change), the write-only secret, the settings. */
@Composable
internal fun ServiceCredentialForm(service: ServiceCredentialStatus, binding: ServiceCredentialsBinding) {
    // Keyed on the actions too: they are rebuilt when the host connection changes, and a typed secret must not follow.
    val form = remember(binding.actions, service.serviceId, service.revision) { ServiceFormState(service) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = LettaDimens.Space.sm).testTag(ServiceCredentialTags.FORM),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        FormFields(service, form)
        ServiceHint(ServiceCredentialStrings.TEST_NOTE)
        if (binding.state.failedService == service.serviceId) binding.state.error?.let { ServiceErrorLine(it) }
        FormButtons(service, form, binding)
    }
    if (form.confirmRemove) RemoveConfirmation(service, form, binding.actions.onRemove)
}

@Composable
private fun FormFields(service: ServiceCredentialStatus, form: ServiceFormState) {
    if (service.baseUrlEditable) {
        FormField(FieldSpec("base_url", ServiceCredentialStrings.ADDRESS, KeyboardType.Uri), form.baseUrl) { form.baseUrl = it }
    }
    SecretField(service, form.secret) { form.secret = it }
    if (service.hasSecret) ServiceHint(ServiceCredentialStrings.SECRET_KEPT_HINT)
    service.configFields.forEach { field ->
        val spec = FieldSpec(field.key, field.label, KeyboardType.Text, placeholder = field.defaultValue)
        FormField(spec, form.config[field.key].orEmpty()) { form.config[field.key] = it }
        if (field.help.isNotBlank()) ServiceHint(field.help)
    }
}

@Composable
private fun FormButtons(service: ServiceCredentialStatus, form: ServiceFormState, binding: ServiceCredentialsBinding) {
    val busy = binding.state.busy != null
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        Button(
            onClick = {
                binding.actions.onSave(service.serviceId, form.draft())
                form.secret = ""
            },
            enabled = form.canSave(service) && !busy,
            modifier = Modifier.testTag(ServiceCredentialTags.SAVE),
        ) { Text(ServiceCredentialStrings.SAVE) }
        TextButton(onClick = binding.actions.onCloseEdit) { Text(ServiceCredentialStrings.CANCEL) }
        if (service.configured) {
            TextButton(
                onClick = { form.confirmRemove = true },
                enabled = !busy,
                modifier = Modifier.testTag(ServiceCredentialTags.REMOVE),
            ) { Text(ServiceCredentialStrings.REMOVE) }
        }
    }
}

@Composable
private fun RemoveConfirmation(service: ServiceCredentialStatus, form: ServiceFormState, onRemove: (ServiceId) -> Unit) {
    val request = LettaConfirmRequest(
        title = ServiceCredentialStrings.removeTitle(service.displayName),
        message = ServiceCredentialStrings.REMOVE_MESSAGE,
        confirmLabel = ServiceCredentialStrings.REMOVE,
    )
    LettaConfirmPane(
        request,
        onConfirm = {
            form.confirmRemove = false
            onRemove(service.serviceId)
        },
        onDismiss = { form.confirmRemove = false },
    )
}

/** What a text field of the form is: its key (for tests), label, keyboard and the placeholder showing a default. */
private class FieldSpec(val key: String, val label: String, val keyboard: KeyboardType, val placeholder: String? = null)

@Composable
private fun FormField(spec: FieldSpec, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(spec.label) },
        placeholder = spec.placeholder?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = spec.keyboard),
        modifier = Modifier.fillMaxWidth().testTag("${ServiceCredentialTags.FIELD_PREFIX}${spec.key}"),
    )
}

/** Masked; the eye shows what was typed here, never a saved secret (the host returns none). */
@Composable
private fun SecretField(service: ServiceCredentialStatus, value: String, onChange: (String) -> Unit) {
    var revealed by remember(service.serviceId) { mutableStateOf(false) }
    val label = if (service.hasSecret) ServiceCredentialStrings.replaceSecret(service.secretLabel) else service.secretLabel
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    imageVector = if (revealed) LettaIcons.VisibilityOff else LettaIcons.Visibility,
                    contentDescription = ServiceCredentialStrings.showSecret(service.secretLabel, revealed),
                )
            }
        },
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag("${ServiceCredentialTags.FIELD_PREFIX}secret"),
    )
}
