package com.letta.mobile.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.settings.SettingsAction
import com.letta.mobile.data.settings.SettingsDrafts
import com.letta.mobile.data.settings.SettingsOption
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.ui.components.picker.LettaPicker
import com.letta.mobile.ui.components.picker.LettaPickerSpec
import com.letta.mobile.ui.components.picker.PickMode
import com.letta.mobile.ui.components.picker.PickerCallbacks
import com.letta.mobile.ui.components.picker.PickerItem
import com.letta.mobile.ui.components.picker.PickerPane
import com.letta.mobile.ui.components.picker.PickerSearch
import com.letta.mobile.ui.components.picker.PickerSection
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/** Test handles of the settings primitives; a row's tag is its prefix plus the row key. */
object SettingsSectionTags {
    const val SECTION_PREFIX = "settings-section-"
    const val ROW_PREFIX = "settings-row-"
    const val OPTION_PREFIX = "settings-option-"
    const val ACTION_PREFIX = "settings-action-"
    const val PROBLEM_PREFIX = "settings-problem-"
}

/**
 * letta-mobile-ckmub.3.12 (hard cut S10, primitive P9): one settings card. Every Settings row on
 * every platform is drawn by the [SettingsScope] rows inside one of these; a platform describes its
 * own sections as data (SettingsSectionModel) instead of drawing them.
 */
@Composable
fun SettingsSection(
    key: String,
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    content: @Composable SettingsScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().testTag(SettingsSectionTags.SECTION_PREFIX + key),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.xl),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            supporting?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsScope.content()
        }
    }
}

/** The rows a [SettingsSection] holds: a switch, a choice, a text field, buttons, facts and notes. */
object SettingsScope {
    /** A switch with its title and explanation. */
    @Composable
    fun ToggleRow(key: String, title: String, checked: Boolean, onChange: (Boolean) -> Unit, supporting: String? = null, enabled: Boolean = true) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag(SettingsSectionTags.ROW_PREFIX + key),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            RowText(title, supporting, Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
    }

    /** The chosen option of [options]; a click opens them in a LettaPicker. */
    @Composable
    fun ChoiceRow(
        key: String,
        title: String,
        options: List<SettingsOption>,
        selected: String,
        onSelect: (String) -> Unit,
        supporting: String? = null,
        enabled: Boolean = true,
    ) {
        var open by remember { mutableStateOf(false) }
        val current = options.firstOrNull { it.key == selected }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { open = true }
                .semantics { role = Role.Button }
                .testTag(SettingsSectionTags.ROW_PREFIX + key),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            RowText(title, supporting ?: current?.description, Modifier.weight(1f))
            Text(
                current?.label.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(LettaIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon))
        }
        if (open) {
            ChoicePicker(ChoicePickerModel(key, title, options, selected), onPick = { picked ->
                open = false
                if (picked != selected) onSelect(picked)
            }, onDismiss = { open = false })
        }
    }

    /** A text field; [secret] hides it behind a show/hide toggle, [problem] marks it invalid. */
    @Composable
    fun TextFieldRow(key: String, title: String, value: String, onValueChange: (String) -> Unit, options: TextFieldOptions = TextFieldOptions()) {
        var revealed by rememberSaveable(key) { mutableStateOf(false) }
        val problem = options.problem?.takeIf { value.isNotBlank() }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(title) },
            placeholder = options.placeholder?.let { { Text(it) } },
            isError = problem != null,
            supportingText = if (problem != null) {
                { Text(problem, modifier = Modifier.testTag(SettingsSectionTags.PROBLEM_PREFIX + key)) }
            } else {
                options.supporting?.let { text -> { Text(text) } }
            },
            singleLine = true,
            visualTransformation = if (options.secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (options.numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            trailingIcon = if (options.secret) {
                { SecretToggle(revealed) { revealed = !revealed } }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth().testTag(SettingsSectionTags.ROW_PREFIX + key),
        )
    }

    /** A row of buttons; the primary one is filled. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun ActionRow(key: String, actions: List<ActionButton>) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().testTag(SettingsSectionTags.ROW_PREFIX + key),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            actions.forEach { action ->
                val tag = Modifier.testTag(SettingsSectionTags.ACTION_PREFIX + action.key)
                if (action.primary) {
                    Button(onClick = action.onClick, enabled = action.enabled, modifier = tag) { Text(action.label) }
                } else {
                    OutlinedButton(onClick = action.onClick, enabled = action.enabled, modifier = tag) { Text(action.label) }
                }
            }
        }
    }

    /** A fact: its label at the start, its value at the end. */
    @Composable
    fun InfoRow(key: String, title: String, value: String, tone: SettingsTone = SettingsTone.Normal) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag(SettingsSectionTags.ROW_PREFIX + key),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = toneColor(tone, MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }

    /** A line of explanation or status in its tone's colour. */
    @Composable
    fun NoteRow(key: String, status: SettingsStatus) {
        Text(
            status.text,
            style = MaterialTheme.typography.bodySmall,
            color = toneColor(status.tone, MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.fillMaxWidth().testTag(SettingsSectionTags.ROW_PREFIX + key),
        )
    }

    /** Monospaced text on a well, for a log tail. */
    @Composable
    fun CodeRow(key: String, text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(LettaDimens.Radius.sm))
                .padding(LettaDimens.Space.md)
                .testTag(SettingsSectionTags.ROW_PREFIX + key),
        )
    }

    /** A progress bar with an optional label under it; a null [fraction] is indeterminate. */
    @Composable
    fun ProgressRow(key: String, fraction: Float?, label: String? = null) {
        Column(
            modifier = Modifier.fillMaxWidth().testTag(SettingsSectionTags.ROW_PREFIX + key),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            label?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** How a [SettingsScope.TextFieldRow] reads and checks its text. */
data class TextFieldOptions(
    val placeholder: String? = null,
    val supporting: String? = null,
    val secret: Boolean = false,
    val numeric: Boolean = false,
    /** What is wrong with the current text; shown once something is typed. */
    val problem: String? = null,
)

/** A button of a [SettingsScope.ActionRow]. */
data class ActionButton(val key: String, val label: String, val onClick: () -> Unit, val primary: Boolean = false, val enabled: Boolean = true)

/** [SettingsAction] as a button over the section's current drafts. */
internal fun SettingsAction.toButton(drafts: SettingsDrafts): ActionButton = ActionButton(
    key = key,
    label = label,
    onClick = { run(drafts) },
    primary = primary,
    enabled = enabled && enabledFor?.invoke(drafts) != false,
)

@Composable
private fun RowText(title: String, supporting: String?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun SecretToggle(revealed: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (revealed) LettaIcons.VisibilityOff else LettaIcons.Visibility,
            contentDescription = if (revealed) "Hide" else "Show",
        )
    }
}

private class ChoicePickerModel(val key: String, val title: String, val options: List<SettingsOption>, val selected: String)

@Composable
private fun ChoicePicker(model: ChoicePickerModel, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val items = model.options.map { option ->
        PickerItem(
            key = option.key,
            value = option.key,
            title = option.label,
            subtitle = option.description,
            testTag = SettingsSectionTags.OPTION_PREFIX + model.key + "-" + option.key,
        )
    }
    LettaPicker(
        spec = LettaPickerSpec(
            sections = listOf(PickerSection(key = model.key, title = null, items = items)),
            query = query,
            mode = PickMode.Single,
            selected = setOf(model.selected),
            search = if (model.options.size > SEARCHABLE_CHOICE_COUNT) PickerSearch() else null,
        ),
        callbacks = PickerCallbacks(onQuery = { query = it }, onPick = { onPick(it.value) }, onDismiss = onDismiss),
        pane = PickerPane(title = model.title),
    )
}

@Composable
private fun toneColor(tone: SettingsTone, normal: Color): Color = when (tone) {
    SettingsTone.Normal -> normal
    SettingsTone.Positive -> MaterialTheme.colorScheme.primary
    SettingsTone.Warning -> MaterialTheme.colorScheme.tertiary
    SettingsTone.Error -> MaterialTheme.colorScheme.error
}

/** A choice longer than this gets a search field. */
private const val SEARCHABLE_CHOICE_COUNT = 8
