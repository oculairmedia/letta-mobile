package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.runtime.RunErrorAction
import com.letta.mobile.data.runtime.RunErrorClassifier
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.run_error_action_compact
import com.letta.mobile.sharedui.resources.run_error_action_retry
import com.letta.mobile.sharedui.resources.run_error_action_switch_model
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bzvro.9 (F09): the one thing to do about a failed run, under its error bubble:
 * Retry (rate limit, network, timeout, a provider hiccup), Switch model (out of credit, model not
 * supported, refused) or Compact (context window exceeded). An error that is none of these, or an
 * action this page cannot take, shows no button: the bubble reads as it always did.
 */
@Composable
internal fun RunErrorActionButton(errorText: String, context: ChatRowContext, callbacks: ChatRowCallbacks) {
    val action = remember(errorText) { RunErrorClassifier.classifyDisplayed(errorText)?.action } ?: return
    val perform = runErrorHandler(action, context, callbacks) ?: return
    OutlinedButton(onClick = perform, modifier = Modifier.testTag(ChatRowTestTags.ERROR_ACTION)) {
        Text(stringResource(action.label()))
    }
}

/** What pressing [action] does on this page, or null when the page cannot do it. */
internal fun runErrorHandler(action: RunErrorAction, context: ChatRowContext, callbacks: ChatRowCallbacks): (() -> Unit)? =
    when (action) {
        RunErrorAction.Retry -> retryHandler(context, callbacks)
        RunErrorAction.SwitchModel -> callbacks.host.openModelPicker?.takeIf { context.capabilities.modelSwitch }
        // Puts the server's /compact command in the draft, as picking it from the "/" list does.
        RunErrorAction.Compact -> { { callbacks.actions.updateComposerText(COMPACT_COMMAND) } }
        RunErrorAction.None -> null
    }

private fun retryHandler(context: ChatRowContext, callbacks: ChatRowCallbacks): (() -> Unit)? = {
    val prompt = callbacks.lastPrompt()
    when {
        prompt == null -> Unit
        context.capabilities.rerun -> callbacks.actions.rerun(prompt)
        prompt.content.isNotBlank() -> callbacks.actions.sendText(prompt.content)
    }
}

private fun RunErrorAction.label(): StringResource = when (this) {
    RunErrorAction.Retry -> Res.string.run_error_action_retry
    RunErrorAction.SwitchModel -> Res.string.run_error_action_switch_model
    RunErrorAction.Compact -> Res.string.run_error_action_compact
    RunErrorAction.None -> Res.string.run_error_action_retry
}

internal const val COMPACT_COMMAND = "/compact"
