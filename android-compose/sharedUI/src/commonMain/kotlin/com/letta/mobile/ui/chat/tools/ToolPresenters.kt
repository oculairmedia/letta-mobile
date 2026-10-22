package com.letta.mobile.ui.chat.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolPresenterRegistry
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb
import com.letta.mobile.data.chat.tools.bundled.BundledToolPresenters
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.plugin.PluginManifest
import com.letta.mobile.data.plugin.view.PluginViewsModel
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

/** [content] with the tool and command presenters [manifests] contribute added to the provided ones. */
@Composable
fun ProvideToolPresenters(manifests: List<PluginManifest>, content: @Composable () -> Unit) {
    val base = LocalToolPresenters.current
    // Worked out once per plugin list: a new registry on every recomposition would recompose every step.
    val registry = remember(base, manifests) { base.contributedBy(manifests) }
    CompositionLocalProvider(LocalToolPresenters provides registry, content = content)
}

/** [content] with the presenters of [model]'s enabled plugins; as it is when there is no model. */
@Composable
fun ProvidePluginToolPresenters(model: PluginViewsModel?, content: @Composable () -> Unit) {
    if (model == null) return content()
    val plugins by model.plugins.collectAsState()
    val manifests = remember(plugins) { plugins.map { it.manifest } }
    ProvideToolPresenters(manifests, content)
}

/** [this] with every manifest's presenters, each owned by its plugin's id. */
fun ToolPresenterRegistry.contributedBy(manifests: List<PluginManifest>): ToolPresenterRegistry =
    manifests.fold(this) { registry, manifest -> registry.contribute(manifest.id, manifest.toolPresenters, manifest.commandPresenters) }

/** [call] as a presenter reads it. */
fun UiToolCall.facts(): ToolCallFacts = ToolCallFacts(name, arguments, result)

/** [call]'s step line, worked out again only when the call or the presenters change. */
@Composable
fun rememberToolStepLine(call: UiToolCall): ToolStepLine {
    val presenters = LocalToolPresenters.current
    return remember(call, presenters) { presenters.line(call.facts()) }
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
