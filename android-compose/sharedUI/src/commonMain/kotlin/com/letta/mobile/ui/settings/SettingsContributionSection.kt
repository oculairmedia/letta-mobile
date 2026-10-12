package com.letta.mobile.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import com.letta.mobile.data.settings.SettingsContribution
import com.letta.mobile.data.settings.SettingsDrafts
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsSectionModel
import com.letta.mobile.ui.host.LettaConfirmPane
import com.letta.mobile.ui.host.LettaConfirmRequest

/**
 * A platform's [SettingsContribution], drawn with the shared rows as it changes. A new contribution
 * (a host that rebuilds its sections as its state changes) starts again from its own initial model.
 */
@Composable
fun SettingsContributionSection(contribution: SettingsContribution, modifier: Modifier = Modifier) {
    val model by produceState(contribution.initial, contribution) { contribution.updates.collect { value = it } }
    SettingsModelSection(model, modifier)
}

/**
 * One [SettingsSectionModel] as a [SettingsSection]. Uncontrolled text rows keep their drafts here,
 * reseeded whenever the platform's value changes (a save landed), so the section's actions always
 * read what is on screen.
 */
@Composable
fun SettingsModelSection(model: SettingsSectionModel, modifier: Modifier = Modifier) {
    val drafts = remember(model.key) { mutableStateMapOf<String, Draft>() }
    val current = SettingsDrafts(model.rows.filterIsInstance<SettingsRow.Text>().associate { it.key to drafts.textOf(it) })
    SettingsSection(SettingsHeader(model.key, model.title, model.supporting), modifier) {
        model.rows.forEach { row -> Row(row, RowDrafts(drafts, current)) }
    }
    model.confirm?.let { confirm ->
        LettaConfirmPane(
            request = LettaConfirmRequest(title = confirm.title, message = confirm.message, confirmLabel = confirm.confirmLabel),
            onConfirm = confirm.onConfirm,
            onDismiss = confirm.onDismiss,
        )
    }
}

/** What an uncontrolled field holds, and the platform value it was typed over. */
internal data class Draft(val seed: String, val text: String)

private fun SnapshotStateMap<String, Draft>.textOf(row: SettingsRow.Text): String =
    if (row.onChange != null) row.value else this[row.key]?.takeIf { it.seed == row.value }?.text ?: row.value

/** The section's uncontrolled drafts and what every text row shows now. */
private class RowDrafts(val drafts: SnapshotStateMap<String, Draft>, val current: SettingsDrafts)

@Composable
private fun SettingsScope.Row(row: SettingsRow, drafts: RowDrafts) {
    when (row) {
        is SettingsRow.Toggle -> ToggleRow(row)
        is SettingsRow.Choice -> ChoiceRow(row)
        is SettingsRow.Text -> TextFieldRow(row, drafts.current[row.key]) { text ->
            val onChange = row.onChange
            if (onChange != null) onChange(text) else drafts.drafts[row.key] = Draft(row.value, text)
        }
        is SettingsRow.Actions -> ActionRow(row.key, row.actions.map { it.toButton(drafts.current) })
        is SettingsRow.Info -> InfoRow(row)
        is SettingsRow.Note -> NoteRow(row)
        is SettingsRow.Code -> CodeRow(row)
        is SettingsRow.Progress -> ProgressRow(row)
    }
}
