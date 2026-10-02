package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.letta.mobile.data.repository.modelcontrol.ProviderConnectForm
import com.letta.mobile.data.repository.modelcontrol.ProviderField
import com.letta.mobile.ui.icons.LettaIcons
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
    ProviderFormFields(form, onChange, modifier = Modifier.verticalScroll(rememberScrollState()))
}

/** The form's method chips and fields; the dialog scrolls them, the settings pages show them inline. */
@Composable
internal fun ProviderFormFields(form: ProviderConnectForm, onChange: (ProviderConnectForm) -> Unit, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        modifier = modifier,
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

/** Secret fields are masked; the eye toggle shows what was typed (never a saved key: the host returns none). */
@Composable
private fun ProviderFieldInput(field: ProviderField, value: String, onValueChange: (String) -> Unit) {
    var revealed by remember(field.key) { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(if (field.required) "${field.label} *" else field.label) },
        placeholder = field.placeholder?.let { { Text(it) } },
        singleLine = true,
        trailingIcon = if (field.secret) {
            {
                IconButton(
                    onClick = { revealed = !revealed },
                    modifier = Modifier.testTag("provider_field_reveal_${field.key}"),
                ) {
                    Icon(
                        imageVector = if (revealed) LettaIcons.VisibilityOff else LettaIcons.Visibility,
                        contentDescription = ModelControlStrings.showSecret(field.label, revealed),
                    )
                }
            }
        } else {
            null
        },
        visualTransformation = if (field.secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (field.secret) KeyboardType.Password else KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().testTag("provider_field_${field.key}"),
    )
}
