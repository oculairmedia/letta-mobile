package com.letta.mobile.ui.chat.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.chat.tools.ToolActivity
import com.letta.mobile.data.chat.tools.ToolPresenterRegistry
import com.letta.mobile.data.chat.tools.ToolStepExpansions
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb
import com.letta.mobile.data.chat.tools.bundled.BundledToolPresenters
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_steps_called
import com.letta.mobile.sharedui.resources.rows_steps_delegated
import com.letta.mobile.sharedui.resources.rows_steps_edited
import com.letta.mobile.sharedui.resources.rows_steps_explored
import com.letta.mobile.sharedui.resources.rows_steps_fetched
import com.letta.mobile.sharedui.resources.rows_steps_join
import com.letta.mobile.sharedui.resources.rows_steps_ran
import com.letta.mobile.sharedui.resources.rows_steps_remembered
import com.letta.mobile.sharedui.resources.rows_tool_step_label
import com.letta.mobile.sharedui.resources.rows_tool_verb_added_to_memory
import com.letta.mobile.sharedui.resources.rows_tool_verb_called
import com.letta.mobile.sharedui.resources.rows_tool_verb_delegated
import com.letta.mobile.sharedui.resources.rows_tool_verb_edited
import com.letta.mobile.sharedui.resources.rows_tool_verb_edited_memory
import com.letta.mobile.sharedui.resources.rows_tool_verb_fetched
import com.letta.mobile.sharedui.resources.rows_tool_verb_planned
import com.letta.mobile.sharedui.resources.rows_tool_verb_ran
import com.letta.mobile.sharedui.resources.rows_tool_verb_read
import com.letta.mobile.sharedui.resources.rows_tool_verb_rewrote_memory
import com.letta.mobile.sharedui.resources.rows_tool_verb_searched
import com.letta.mobile.sharedui.resources.rows_tool_verb_stopped
import com.letta.mobile.sharedui.resources.rows_tool_verb_updated
import com.letta.mobile.sharedui.resources.rows_tool_verb_used
import com.letta.mobile.sharedui.resources.rows_tool_verb_wrote
import com.letta.mobile.sharedui.resources.rows_tool_verb_wrote_memfs
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The presenters the timeline draws tool calls with: the bundled ones unless a shell adds plugins'. */
val LocalToolPresenters = staticCompositionLocalOf { BundledToolPresenters.registry }

/** [content] drawing tool calls with [registry]. */
@Composable
fun ProvideToolPresenters(registry: ToolPresenterRegistry, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalToolPresenters provides registry, content = content)
}

/** What a step line leads with: its verb, followed by its subject when it has one ("Called fetch_issue"). */
@Composable
fun ToolStepLine.title(): String {
    val verb = stringResource(verb.resource)
    val subject = subject ?: return verb
    return stringResource(Res.string.rows_tool_step_label, verb, subject)
}

private val ToolVerb.resource: StringResource
    get() = when (this) {
        ToolVerb.Ran -> Res.string.rows_tool_verb_ran
        ToolVerb.Read -> Res.string.rows_tool_verb_read
        ToolVerb.Wrote -> Res.string.rows_tool_verb_wrote
        ToolVerb.Edited -> Res.string.rows_tool_verb_edited
        ToolVerb.Searched -> Res.string.rows_tool_verb_searched
        ToolVerb.Fetched -> Res.string.rows_tool_verb_fetched
        ToolVerb.Delegated -> Res.string.rows_tool_verb_delegated
        ToolVerb.Called -> Res.string.rows_tool_verb_called
        ToolVerb.EditedMemory -> Res.string.rows_tool_verb_edited_memory
        ToolVerb.AddedToMemory -> Res.string.rows_tool_verb_added_to_memory
        ToolVerb.RewroteMemory -> Res.string.rows_tool_verb_rewrote_memory
        ToolVerb.WroteMemfs -> Res.string.rows_tool_verb_wrote_memfs
        ToolVerb.Stopped -> Res.string.rows_tool_verb_stopped
        ToolVerb.Updated -> Res.string.rows_tool_verb_updated
        ToolVerb.Planned -> Res.string.rows_tool_verb_planned
        ToolVerb.Used -> Res.string.rows_tool_verb_used
    }

/** Which tool steps the person flipped open or closed; null where no host keeps them (each step then keeps its own). */
val LocalToolStepExpansions = staticCompositionLocalOf<ToolStepExpansions?> { null }

/** [content] remembering tool step expansions in [settings], across reloads. */
@Composable
fun ProvideToolStepExpansions(settings: SecureSettingsStore, content: @Composable () -> Unit) {
    val expansions = remember(settings) { ToolStepExpansions(settings) }
    CompositionLocalProvider(LocalToolStepExpansions provides expansions, content = content)
}

/** Several steps' label: their [activities] joined in order, its first letter capitalised ("Explored files, ran commands"). */
@Composable
fun groupLabel(activities: List<ToolActivity>): String {
    val separator = stringResource(Res.string.rows_steps_join, "", "")
    val phrases = activities.map { stringResource(it.phrase) }
    return phrases.joinToString(separator).replaceFirstChar { it.uppercaseChar() }
}

private val ToolActivity.phrase: StringResource
    get() = when (this) {
        ToolActivity.Explored -> Res.string.rows_steps_explored
        ToolActivity.Ran -> Res.string.rows_steps_ran
        ToolActivity.Edited -> Res.string.rows_steps_edited
        ToolActivity.Fetched -> Res.string.rows_steps_fetched
        ToolActivity.Delegated -> Res.string.rows_steps_delegated
        ToolActivity.Called -> Res.string.rows_steps_called
        ToolActivity.Remembered -> Res.string.rows_steps_remembered
    }
