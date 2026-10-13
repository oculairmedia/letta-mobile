package com.letta.mobile.data.settings

import com.letta.mobile.data.model.LettaConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/*
 * letta-mobile-ckmub.3.12 (hard cut S10, primitive P9): the data the shared Settings page draws for
 * a platform's own settings. A platform never draws a settings row itself: it describes the section
 * here (from its services, in plain Kotlin) and the sharedUI SettingsSection primitives draw it.
 */

/**
 * Where a section sits on the Settings page, in page order (letta-mobile-ckmub.3.12.1): the backend
 * first, the danger zone last and on its own.
 */
enum class SettingsGroup(val title: String) {
    Backend("Backend"),
    Chat("Chat"),
    Appearance("Appearance"),
    Notifications("Notifications"),
    Providers("Providers"),
    About("Diagnostics & about"),
    Developer("Developer"),
    Danger("Danger zone"),
}

/** How a line reads: plain, good news, a caution, or a failure. */
enum class SettingsTone { Normal, Positive, Warning, Error }

/** A line of status with its tone (a connection test's result, a save's outcome). */
data class SettingsStatus(val text: String, val tone: SettingsTone = SettingsTone.Normal)

/** One option of a [SettingsRow.Choice]. */
data class SettingsOption(val key: String, val label: String, val description: String? = null)

/** What the section's text fields hold right now, by row key; what an action saves. */
class SettingsDrafts(private val values: Map<String, String>) {
    operator fun get(key: String): String = values[key].orEmpty()

    /** The trimmed value, or null when blank. */
    fun trimmedOrNull(key: String): String? = get(key).trim().takeIf { it.isNotEmpty() }
}

/** A button of a [SettingsRow.Actions] row. [enabledFor] sees the drafts, so Save can wait for a valid field. */
data class SettingsAction(
    val key: String,
    val label: String,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val enabledFor: ((SettingsDrafts) -> Boolean)? = null,
    val run: (SettingsDrafts) -> Unit,
)

/** One row of a settings section. [key] is unique within its section and tags the row for tests. */
sealed interface SettingsRow {
    val key: String

    data class Toggle(
        override val key: String,
        val title: String,
        val checked: Boolean,
        val supporting: String? = null,
        val enabled: Boolean = true,
        val onChange: (Boolean) -> Unit,
    ) : SettingsRow

    /**
     * One of [options]: a bottom sheet on touch, an anchored list or (three options or fewer) a
     * segmented control on a pointer. [inline] keeps the segmented control on every platform (the
     * backend mode).
     */
    data class Choice(
        override val key: String,
        val title: String,
        val options: List<SettingsOption>,
        val selected: String,
        val supporting: String? = null,
        val enabled: Boolean = true,
        val inline: Boolean = false,
        val onSelect: (String) -> Unit,
    ) : SettingsRow

    /**
     * A text field. With [onChange] the field shows [value] and reports every edit (the platform
     * holds the form); without it the field keeps its own draft, seeded from [value], for an
     * [Actions] row to read. [lines] above one makes it a multi-line field that tall at least (a prompt).
     */
    data class Text(
        override val key: String,
        val title: String,
        val value: String = "",
        val placeholder: String? = null,
        val supporting: String? = null,
        val secret: Boolean = false,
        val numeric: Boolean = false,
        val problem: ((String) -> String?)? = null,
        val lines: Int = 1,
        val onChange: ((String) -> Unit)? = null,
    ) : SettingsRow

    data class Actions(override val key: String, val actions: List<SettingsAction>) : SettingsRow

    /** A fact: a label and its value; a click copies the value. */
    data class Info(
        override val key: String,
        val title: String,
        val value: String,
        val tone: SettingsTone = SettingsTone.Normal,
        val supporting: String? = null,
    ) : SettingsRow

    /** A state: its label, and the status as a dot, a glyph and words (never colour alone). */
    data class Status(
        override val key: String,
        val title: String,
        val status: SettingsStatus,
        val supporting: String? = null,
    ) : SettingsRow

    /**
     * One action as a whole row: a label (a verb), an optional explanation and a trailing value. A
     * [destructive] row reads red and asks [confirm] first.
     */
    data class Link(
        override val key: String,
        val title: String,
        val supporting: String? = null,
        val value: String? = null,
        val destructive: Boolean = false,
        val enabled: Boolean = true,
        val confirm: SettingsConfirmText? = null,
        val onClick: () -> Unit,
    ) : SettingsRow

    /** A line of explanation or status. */
    data class Note(override val key: String, val status: SettingsStatus) : SettingsRow

    /** Monospaced text (a log tail). */
    data class Code(override val key: String, val text: String) : SettingsRow

    /** A progress bar; a null [fraction] is indeterminate. */
    data class Progress(override val key: String, val fraction: Float?, val label: String? = null) : SettingsRow
}

/** What a destructive row asks before it acts: the consequence, and whether it can be undone. */
data class SettingsConfirmText(val title: String, val message: String, val confirmLabel: String)

/** A confirmation the section asks for before it acts (switching a data directory). */
data class SettingsConfirm(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

/** One settings card: its title, an optional explanation, its rows, and a pending confirmation. */
data class SettingsSectionModel(
    val key: String,
    val title: String,
    val rows: List<SettingsRow>,
    val supporting: String? = null,
    val confirm: SettingsConfirm? = null,
)

/**
 * A platform's own section of the Settings page (the desktop's bundled runtime, Android's on-device
 * model): where it sits, what it shows first and how it changes. [showForMode] limits it to one
 * backend mode of the Backend form (Android's on-device model only matters for the local runtime).
 * A section that [needsHost] is drawn disabled, saying why, while no host is connected.
 */
class SettingsContribution(
    val group: SettingsGroup,
    val initial: SettingsSectionModel,
    val updates: Flow<SettingsSectionModel> = emptyFlow(),
    val showForMode: LettaConfig.Mode? = null,
    val needsHost: Boolean = false,
) {
    companion object {
        /** A section that follows [state]. */
        fun of(
            group: SettingsGroup,
            state: StateFlow<SettingsSectionModel>,
            showForMode: LettaConfig.Mode? = null,
        ): SettingsContribution = SettingsContribution(group, state.value, state, showForMode)
    }
}
