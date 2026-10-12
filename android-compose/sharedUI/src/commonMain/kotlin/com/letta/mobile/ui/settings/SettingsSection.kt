package com.letta.mobile.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.settings.SettingsAction
import com.letta.mobile.data.settings.SettingsDrafts
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.ui.components.picker.LettaPicker
import com.letta.mobile.ui.components.picker.LettaPickerSpec
import com.letta.mobile.ui.components.picker.PickMode
import com.letta.mobile.ui.components.picker.PickerCallbacks
import com.letta.mobile.ui.components.picker.PickerItem
import com.letta.mobile.ui.components.picker.PickerPane
import com.letta.mobile.ui.components.picker.PickerSearch
import com.letta.mobile.ui.components.picker.PickerSection
import com.letta.mobile.ui.host.LettaConfirmPane
import com.letta.mobile.ui.host.LettaConfirmRequest
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.LettaPaneKind
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.host.PaneAnchor
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/** Test handles of the settings primitives; a row's tag is its prefix plus the row key. */
object SettingsSectionTags {
    const val SECTION_PREFIX = "settings-section-"
    const val ROW_PREFIX = "settings-row-"
    const val OPTION_PREFIX = "settings-option-"
    const val ACTION_PREFIX = "settings-action-"
    const val PROBLEM_PREFIX = "settings-problem-"
    const val PASTE_PREFIX = "settings-paste-"
}

/**
 * A settings card's heading: its key (the test tag), its title and an optional explanation.
 * [showTitle] false leaves the title to the group header above the card (a group's own section).
 */
data class SettingsHeader(val key: String, val title: String, val supporting: String? = null, val showTitle: Boolean = true)

/**
 * letta-mobile-ckmub.3.12 (hard cut S10, primitive P9) / ckmub.3.12.1: one rounded settings card.
 * Every Settings row on every platform is drawn by the [SettingsScope] rows inside one of these, from
 * the row models in com.letta.mobile.data.settings; a platform describes its own sections as data.
 */
@Composable
fun SettingsSection(header: SettingsHeader, modifier: Modifier = Modifier, content: @Composable SettingsScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().testTag(SettingsSectionTags.SECTION_PREFIX + header.key),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            if (header.showTitle) {
                Text(
                    header.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = LettaDimens.Space.sm),
                )
            }
            header.supporting?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsScope.content()
        }
    }
}

/**
 * The rows a [SettingsSection] holds. Toggle, choice, info, status and link rows share one anatomy
 * (label, one-line helper, trailing value, a whole-row hit target); fields, buttons, notes, logs and
 * progress keep their own shape.
 */
object SettingsScope {
    /** A switch: the whole row toggles it. */
    @Composable
    fun ToggleRow(row: SettingsRow.Toggle) {
        val lock = LocalSettingsLock.current
        val enabled = row.enabled && lock == null
        SettingsRowFrame(
            tag = SettingsSectionTags.ROW_PREFIX + row.key,
            words = RowWords(row.title, lock ?: row.supporting),
            enabled = enabled,
            interaction = Modifier.toggleable(value = row.checked, enabled = enabled, role = Role.Switch, onValueChange = row.onChange),
        ) {
            Switch(checked = row.checked, onCheckedChange = null, enabled = enabled)
        }
    }

    /**
     * One of the row's options. A segmented control when the row is [SettingsRow.Choice.inline], or
     * on a pointer for three options or fewer; otherwise the current value, opening a bottom sheet on
     * touch and an anchored list on a pointer.
     */
    @Composable
    fun ChoiceRow(row: SettingsRow.Choice) {
        val lock = LocalSettingsLock.current
        if (row.segmentedOn(LocalLettaHost.current.input)) {
            SegmentedChoice(row, lock)
        } else {
            ListChoice(row, lock)
        }
    }

    /**
     * A text field with its label above it, showing [value]; edits go to [onValueChange]. A secret
     * row hides its text behind a show/hide toggle; [paste] adds a Paste button. The row's problem
     * marks it invalid once something is typed, in words under the field.
     */
    @Composable
    fun TextFieldRow(row: SettingsRow.Text, value: String, paste: Boolean = false, onValueChange: (String) -> Unit) {
        val enabled = LocalSettingsLock.current == null
        var revealed by rememberSaveable(row.key) { mutableStateOf(false) }
        val problem = row.problem?.invoke(value)?.takeIf { value.isNotBlank() }
        Column(Modifier.fillMaxWidth().padding(vertical = LettaDimens.Space.xs), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
            Text(row.title, style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                placeholder = row.placeholder?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                isError = problem != null,
                supportingText = fieldSupport(row, problem),
                singleLine = true,
                visualTransformation = if (row.secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = if (row.numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                trailingIcon = fieldTrailing(row, FieldExtras(paste, revealed, { revealed = !revealed }, onValueChange)),
                modifier = Modifier.fillMaxWidth().testTag(SettingsSectionTags.ROW_PREFIX + row.key),
            )
        }
    }

    /** A row of buttons; the primary one is filled. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun ActionRow(key: String, actions: List<ActionButton>) {
        val unlocked = LocalSettingsLock.current == null
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = LettaDimens.Space.xs).testTag(SettingsSectionTags.ROW_PREFIX + key),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            actions.forEach { action ->
                val tag = Modifier.testTag(SettingsSectionTags.ACTION_PREFIX + action.key)
                val enabled = action.enabled && unlocked
                if (action.primary) {
                    Button(onClick = action.onClick, enabled = enabled, modifier = tag) { Text(action.label) }
                } else {
                    OutlinedButton(onClick = action.onClick, enabled = enabled, modifier = tag) { Text(action.label) }
                }
            }
        }
    }

    /** A fact: its label at the start, its value at the end; a click copies the value. */
    @Composable
    fun InfoRow(row: SettingsRow.Info) {
        @Suppress("DEPRECATION")
        val clipboard = LocalClipboardManager.current
        SettingsRowFrame(
            tag = SettingsSectionTags.ROW_PREFIX + row.key,
            words = RowWords(row.title, row.supporting),
            enabled = true,
            interaction = Modifier.clickable(onClickLabel = COPY_LABEL) { clipboard.setText(AnnotatedString(row.value)) },
        ) {
            Text(
                row.value,
                style = MaterialTheme.typography.bodyMedium,
                color = row.tone.color(MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }

    /** A state with its badge: a dot or glyph and words. */
    @Composable
    fun StatusRow(row: SettingsRow.Status) {
        SettingsRowFrame(SettingsSectionTags.ROW_PREFIX + row.key, RowWords(row.title, row.supporting), enabled = true) {
            SettingsStatusBadge(row.status)
        }
    }

    /** One action as a row; a destructive one reads red and asks first. */
    @Composable
    fun LinkRow(row: SettingsRow.Link) {
        val lock = LocalSettingsLock.current
        val enabled = row.enabled && lock == null
        var asking by remember { mutableStateOf(false) }
        val confirm = row.confirm
        SettingsRowFrame(
            tag = SettingsSectionTags.ROW_PREFIX + row.key,
            words = RowWords(row.title, lock ?: row.supporting, row.destructive),
            enabled = enabled,
            interaction = Modifier.clickable(enabled = enabled, role = Role.Button) {
                if (confirm != null) {
                    asking = true
                } else {
                    row.onClick()
                }
            },
        ) {
            row.value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!row.destructive) Chevron()
        }
        if (asking && confirm != null) {
            LettaConfirmPane(
                request = LettaConfirmRequest(title = confirm.title, message = confirm.message, confirmLabel = confirm.confirmLabel),
                onConfirm = {
                    asking = false
                    row.onClick()
                },
                onDismiss = { asking = false },
            )
        }
    }

    /** A line of explanation or status in its tone's colour, with its tone's glyph. */
    @Composable
    fun NoteRow(row: SettingsRow.Note) {
        val color = row.status.tone.color(MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = LettaDimens.Space.xs).testTag(SettingsSectionTags.ROW_PREFIX + row.key),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            row.status.tone.glyph?.let { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(LettaDimens.Control.iconSm)) }
            Text(row.status.text, style = MaterialTheme.typography.bodySmall, color = color)
        }
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
            modifier = Modifier.fillMaxWidth().padding(vertical = LettaDimens.Space.xs).testTag(SettingsSectionTags.ROW_PREFIX + row.key),
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
private fun SegmentedChoice(row: SettingsRow.Choice, lock: String?) {
    val enabled = row.enabled && lock == null
    val current = row.options.firstOrNull { it.key == row.selected }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LettaDimens.Control.minTouchTarget)
            .padding(vertical = LettaDimens.Space.xs)
            .testTag(SettingsSectionTags.ROW_PREFIX + row.key),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        RowWordsColumn(RowWords(row.title, lock ?: row.supporting ?: current?.description), enabled)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            row.options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option.key == row.selected,
                    onClick = { if (option.key != row.selected) row.onSelect(option.key) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = row.options.size),
                    modifier = Modifier.testTag(SettingsSectionTags.OPTION_PREFIX + row.key + "-" + option.key),
                ) { Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun ListChoice(row: SettingsRow.Choice, lock: String?) {
    val enabled = row.enabled && lock == null
    var open by remember { mutableStateOf(false) }
    val current = row.options.firstOrNull { it.key == row.selected }
    SettingsRowFrame(
        tag = SettingsSectionTags.ROW_PREFIX + row.key,
        words = RowWords(row.title, lock ?: row.supporting ?: current?.description),
        enabled = enabled,
        interaction = Modifier.clickable(enabled = enabled, role = Role.DropdownList) { open = true },
    ) {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
                Text(
                    current?.label.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Chevron()
            }
            if (open) {
                ChoicePicker(row, onPick = { picked ->
                    open = false
                    if (picked != row.selected) row.onSelect(picked)
                }, onDismiss = { open = false })
            }
        }
    }
}

@Composable
private fun Chevron() {
    Icon(
        LettaIcons.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(LettaDimens.Control.icon),
    )
}

/** A field's trailing controls and what they act on. */
private class FieldExtras(val paste: Boolean, val revealed: Boolean, val onToggleReveal: () -> Unit, val onValueChange: (String) -> Unit)

private fun fieldSupport(row: SettingsRow.Text, problem: String?): (@Composable () -> Unit)? = when {
    problem != null -> {
        { Text(problem, modifier = Modifier.testTag(SettingsSectionTags.PROBLEM_PREFIX + row.key)) }
    }
    row.supporting != null -> {
        { Text(row.supporting.orEmpty()) }
    }
    else -> null
}

private fun fieldTrailing(row: SettingsRow.Text, extras: FieldExtras): (@Composable () -> Unit)? {
    if (!row.secret && !extras.paste) return null
    return {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (extras.paste) PasteButton(row.key, extras.onValueChange)
            if (row.secret) SecretToggle(extras.revealed, extras.onToggleReveal)
        }
    }
}

@Composable
private fun PasteButton(key: String, onPaste: (String) -> Unit) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    TextButton(
        onClick = { clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let(onPaste) },
        modifier = Modifier.testTag(SettingsSectionTags.PASTE_PREFIX + key),
    ) { Text(PASTE_LABEL) }
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
        // A bottom sheet on touch, a list anchored under the value on a pointer.
        pane = PickerPane(kind = LettaPaneKind.Adaptive(PaneAnchor.BelowEnd), title = row.title),
    )
}

/** A choice longer than this gets a search field. */
private const val SEARCHABLE_CHOICE_COUNT = 8

/** A pointer shows a choice of this many options or fewer as a segmented control. */
private const val SEGMENTED_CHOICE_COUNT = 3

/** Whether this choice draws as a segmented control on [input]: always when inline, else a short choice on a pointer. */
internal fun SettingsRow.Choice.segmentedOn(input: LettaInput): Boolean {
    if (inline) return true
    val short = options.size <= SEGMENTED_CHOICE_COUNT
    return short && input == LettaInput.Pointer
}

private const val COPY_LABEL = "Copy"
private const val PASTE_LABEL = "Paste"
