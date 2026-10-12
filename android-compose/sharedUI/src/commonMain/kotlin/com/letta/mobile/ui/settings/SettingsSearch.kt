package com.letta.mobile.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.settings.AppearanceSettings
import com.letta.mobile.data.settings.ChatSettings
import com.letta.mobile.data.settings.SettingsGroup
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsSectionModel

/*
 * letta-mobile-ckmub.3.12.1: the Settings page's search. Every row the page would draw has an entry
 * (its label and helper, and the section it sits in); the field filters them across sections and a
 * hit opens its group.
 */

/** One searchable row: where it sits, the section name shown under a hit, its label and helper. */
data class SettingsSearchEntry(val group: SettingsGroup, val section: String, val title: String, val helper: String? = null)

/** The entries whose label or helper contains [query], ignoring case; nothing for a blank query. */
fun List<SettingsSearchEntry>.matching(query: String): List<SettingsSearchEntry> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return filter { it.title.contains(needle, ignoreCase = true) || it.helper?.contains(needle, ignoreCase = true) == true }
}

/** The page's entries for [mode]: the standard rows its ports bind, then the platform sections' rows. */
internal fun SettingsPageServices.searchEntries(mode: LettaConfig.Mode, hasSavedToken: Boolean): List<SettingsSearchEntry> =
    backendEntries(mode, hasSavedToken) +
        appearance.entries() +
        chat.entries() +
        listOfNotNull(
            notifications?.attention?.let { SettingsCopy.APPROVALS.entry(SettingsGroup.Notifications) },
            onOpenProviders?.let { SettingsCopy.PROVIDERS.entry(SettingsGroup.Providers) },
        ) +
        contributions
            .filter { it.showForMode == null || it.showForMode == mode }
            .flatMap { it.initial.searchEntries(it.group) }

/** A platform section's rows as entries; notes, logs and progress bars are not settings to find. */
internal fun SettingsSectionModel.searchEntries(group: SettingsGroup): List<SettingsSearchEntry> = rows.flatMap { row ->
    when (row) {
        is SettingsRow.Toggle -> listOf(entry(group, row.title, row.supporting))
        is SettingsRow.Choice -> listOf(entry(group, row.title, row.supporting))
        is SettingsRow.Text -> listOf(entry(group, row.title, row.supporting))
        is SettingsRow.Info -> listOf(entry(group, row.title, row.supporting))
        is SettingsRow.Status -> listOf(entry(group, row.title, row.supporting))
        is SettingsRow.Link -> listOf(entry(group, row.title, row.supporting))
        is SettingsRow.Actions -> row.actions.map { entry(group, it.label, null) }
        is SettingsRow.Note, is SettingsRow.Code, is SettingsRow.Progress -> emptyList()
    }
}

private fun SettingsSectionModel.entry(group: SettingsGroup, title: String, helper: String?) =
    SettingsSearchEntry(group, this.title, title, helper)

private fun SettingsLabel.entry(group: SettingsGroup) = SettingsSearchEntry(group, group.title, title, helper)

private fun SettingsPageServices.backendEntries(mode: LettaConfig.Mode, hasSavedToken: Boolean): List<SettingsSearchEntry> = buildList {
    add(SettingsCopy.MODE.entry(SettingsGroup.Backend))
    if (mode == LettaConfig.Mode.SELF_HOSTED) {
        add(SettingsCopy.ADDRESS.entry(SettingsGroup.Backend))
        add(SettingsCopy.TOKEN.entry(SettingsGroup.Backend))
        backend.testConnection?.let { add(SettingsCopy.TEST.entry(SettingsGroup.Backend)) }
        backend.onScanToConnect?.let { add(SettingsCopy.SCAN.entry(SettingsGroup.Backend)) }
    }
    pairing?.let { add(PAIRING_ENTRY) }
    if (hasSavedToken) add(SettingsCopy.CLEAR_TOKEN.entry(SettingsGroup.Danger))
    backend.onIrohIdentityReset?.let { add(SettingsCopy.RESET_IDENTITY.entry(SettingsGroup.Danger)) }
}

private fun AppearanceSettings?.entries(): List<SettingsSearchEntry> = if (this == null) {
    emptyList()
} else {
    listOfNotNull(
        theme?.let { SettingsCopy.THEME },
        dynamicColor?.let { SettingsCopy.DYNAMIC_COLOR },
        themePreset?.let { SettingsCopy.PRESET },
        haptics?.let { SettingsCopy.HAPTICS },
    ).map { it.entry(SettingsGroup.Appearance) }
}

private fun ChatSettings?.entries(): List<SettingsSearchEntry> = if (this == null) {
    emptyList()
} else {
    listOfNotNull(
        openChatsOnCanvas?.let { SettingsCopy.CANVAS },
        permissionMode?.let { SettingsCopy.PERMISSION_MODE },
    ).map { it.entry(SettingsGroup.Chat) }
}

private val PAIRING_ENTRY = SettingsSearchEntry(SettingsGroup.Backend, SettingsGroup.Backend.title, "Pair a device", "Install Letta elsewhere and pair it with this host")

/** The hits for the page's query, each opening its group; or the empty state when none match. */
@Composable
internal fun SettingsSearchResults(hits: List<SettingsSearchEntry>, query: String, onOpen: (SettingsGroup) -> Unit) {
    if (hits.isEmpty()) {
        Text(
            SettingsCopy.noMatch(query.trim()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(SettingsPageTags.SEARCH_EMPTY),
        )
        return
    }
    SettingsSection(SettingsHeader(SettingsPageTags.SEARCH_RESULTS, "Results", showTitle = false)) {
        hits.forEachIndexed { index, hit ->
            LinkRow(
                SettingsRow.Link(
                    key = SettingsPageTags.SEARCH_HIT_PREFIX + index,
                    title = hit.title,
                    supporting = hit.section,
                    onClick = { onOpen(hit.group) },
                ),
            )
        }
    }
}
