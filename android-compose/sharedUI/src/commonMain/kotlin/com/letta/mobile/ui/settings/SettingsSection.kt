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
import com.letta.mobile.data.settings.SettingsRow
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

/** A settings card's heading: its key (the test tag), its title and an optional explanation. */
data class SettingsHeader(val key: String, val title: String, val supporting: String? = null)

/**
 * letta-mobile-ckmub.3.12 (hard cut S10, primitive P9): one settings card. Every Settings row on
 * every platform is drawn by the [SettingsScope] rows inside one of these, from the row models in
 * com.letta.mobile.data.settings; a platform describes its own sections as data instead of drawing.
 */
@Composable
fun SettingsSection(header: SettingsHeader, modifier: Modifier = Modifier, content: @Composable SettingsScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().testTag(SettingsSectionTags.SECTION_PREFIX + header.key),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.xl),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            Text(header.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            header.supporting?.let {
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
    fun ToggleRow(row: SettingsRow.Toggle) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag(SettingsSectionTags.ROW_PREFIX + row.key),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            RowText(row.title, row.supporting, Modifier.weight(1f))
            Switch(checked = row.checked, onCheckedChange = row.onChange, enabled = row.enabled)
        }
    }

    /** The chosen option; a click opens all of them in a LettaPicker. */
    @Composable
    fun ChoiceRow(row: SettingsRow.Choice) {
        var open by remember { mutableStateOf(false) }
        val current = row.options.firstOrNull { it.key == row.selected }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = row.enabled) { open = true }
                .semantics { role = Role.Button }
                .testTag(SettingsSectionTags.ROW_PREFIX + row.key),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            RowText(row.title, row.supporting ?: current?.description, Modifier.weight(1f))
            Text(
                current?.label.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(LettaIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon))
        }
        if (open) {
            ChoicePicker(row, onPick = { picked ->
                open = false
                if (picked != row.selected) row.onSelect(picked)
            }, onDismiss = { open = false })
        }
    }

    /**
     * A text field showing [value]; edits go to [onValueChange]. A secret row hides its text behind
     * a show/hide toggle; the row's problem marks it invalid once something is typed.
     */
    @Composable
    fun TextFieldRow(row: SettingsRow.Text, value: String, onValueChange: (String) -> Unit) {
        var revealed by rememberSaveable(row.key) { mutableStateOf(false) }
        val problem = row.problem?.invoke(value)?.takeIf { value.isNotBlank() }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(row.title) },
            placeholder = row.placeholder?.let { { Text(it) } },
            isError = problem != null,
            supportingText = if (problem != null) {
                { Text(problem, modifier = Modifier.testTag(SettingsSectionTags.PROBLEM_PREFIX + row.key)) }
            } else {
                row.supporting?.let { text -> { Text(text) } }
            },
            singleLine = true,
            visualTransformation = if (row.secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (row.numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            trailingIcon = if (row.secret) {
                { SecretToggle(revealed) { revealed = !revealed } }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth().testTag(SettingsSectionTags.ROW_PREFIX + row.key),
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
    fun InfoRow(row: SettingsRow.Info) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag(SettingsSectionTags.ROW_PREFIX + row.key),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            Text(row.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(row.value, style = MaterialTheme.typography.bodyMedium, color = toneColor(row.tone, MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }

    /** A line of explanation or status in its tone's colour. */
    @Composable
    fun NoteRow(row: SettingsRow.Note) {
        Text(
            row.status.text,
            style = MaterialTheme.typography.bodySmall,
            color = toneColor(row.status.tone, MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.fillMaxWidth().testTag(SettingsSectionTags.ROW_PREFIX + row.key),
        )
    }

    /** Monospaced text on a well, for a log tail. */
    @Composable
    fun CodeRow(row: SettingsRow.Code) {
        Text(
            text = row.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(LettaDimens.Radius.sm))
                .padding(LettaDimens.Space.md)
                .testTag(SettingsSectionTags.ROW_PREFIX + row.key),
        )
    }

    /** A progress bar with an optional label under it; a null fraction is indeterminate. */
    @Composable
    fun ProgressRow(row: SettingsRow.Progress) {
        Column(
            modifier = Modifier.fillMaxWidth().testTag(SettingsSectionTags.ROW_PREFIX + row.key),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            val fraction = row.fraction
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            row.label?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

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

@Composable
private fun ChoicePicker(row: SettingsRow.Choice, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val items = row.options.map { option ->
        PickerItem(
            key = option.key,
            value = option.key,
            title = option.label,
            subtitle = option.description,
            testTag = SettingsSectionTags.OPTION_PREFIX + row.key + "-" + option.key,
        )
    }
    LettaPicker(
        spec = LettaPickerSpec(
            sections = listOf(PickerSection(key = row.key, title = null, items = items)),
            query = query,
            mode = PickMode.Single,
            selected = setOf(row.selected),
            search = if (row.options.size > SEARCHABLE_CHOICE_COUNT) PickerSearch() else null,
        ),
        callbacks = PickerCallbacks(onQuery = { query = it }, onPick = { onPick(it.value) }, onDismiss = onDismiss),
        pane = PickerPane(title = row.title),
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
