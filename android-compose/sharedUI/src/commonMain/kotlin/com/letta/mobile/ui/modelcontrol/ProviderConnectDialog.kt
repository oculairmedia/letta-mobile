package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.letta.mobile.data.repository.modelcontrol.ProviderConnectForm
import com.letta.mobile.data.repository.modelcontrol.ProviderField
import com.letta.mobile.ui.theme.LettaDimens

data class ProviderFormActions(
    val onChange: (ProviderConnectForm) -> Unit,
    val onSubmit: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * Connect form generated from the provider's auth methods: one chip per
 * method when there are several, then one text field per method field.
 * Secret fields are password inputs; validation lives in [ProviderConnectForm].
 */
@Composable
fun ProviderConnectDialog(form: ProviderConnectForm, busy: Boolean, actions: ProviderFormActions) {
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text("Connect ${form.provider.displayName}") },
        text = { ProviderFormBody(form, actions.onChange) },
        confirmButton = {
            TextButton(
                onClick = actions.onSubmit,
                enabled = form.canSubmit && !busy,
                modifier = Modifier.testTag(ProviderPaneTags.SUBMIT),
            ) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = actions.onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProviderFormBody(form: ProviderConnectForm, onChange: (ProviderConnectForm) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        modifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
        if (form.provider.authMethods.size > 1) AuthMethodChips(form, onChange)
        form.method?.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        form.fields.forEach { field ->
            ProviderFieldInput(field, form.value(field)) { onChange(form.withValue(field.key, it)) }
        }
    }
}

@Composable
private fun AuthMethodChips(form: ProviderConnectForm, onChange: (ProviderConnectForm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        form.provider.authMethods.forEachIndexed { index, method ->
            FilterChip(
                selected = index == form.methodIndex,
                onClick = { onChange(form.withMethod(index)) },
                label = { Text(method.label) },
            )
        }
    }
}

@Composable
private fun ProviderFieldInput(field: ProviderField, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(if (field.required) "${field.label} *" else field.label) },
        placeholder = field.placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (field.secret) KeyboardType.Password else KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().testTag("provider_field_${field.key}"),
    )
}
