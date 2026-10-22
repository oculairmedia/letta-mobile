package com.letta.mobile.ui.chat.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.chat.tools.ToolPresenterRegistry
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb
import com.letta.mobile.data.chat.tools.bundled.BundledToolPresenters
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_tool_step_label
import com.letta.mobile.sharedui.resources.rows_tool_verb_called
import com.letta.mobile.sharedui.resources.rows_tool_verb_delegated
import com.letta.mobile.sharedui.resources.rows_tool_verb_edited
import com.letta.mobile.sharedui.resources.rows_tool_verb_fetched
import com.letta.mobile.sharedui.resources.rows_tool_verb_ran
import com.letta.mobile.sharedui.resources.rows_tool_verb_read
import com.letta.mobile.sharedui.resources.rows_tool_verb_searched
import com.letta.mobile.sharedui.resources.rows_tool_verb_wrote
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
    }
