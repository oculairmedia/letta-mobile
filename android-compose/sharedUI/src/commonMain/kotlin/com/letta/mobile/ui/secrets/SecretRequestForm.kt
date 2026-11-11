package com.letta.mobile.ui.secrets

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.letta.mobile.data.secrets.client.SecretBuffer
import com.letta.mobile.data.secrets.client.SecretFormController
import com.letta.mobile.data.secrets.client.SecretFormFailure
import com.letta.mobile.data.secrets.client.SecretFormMachine
import com.letta.mobile.data.secrets.client.SecretFormState
import com.letta.mobile.data.secrets.request.SecretFailureReason
import com.letta.mobile.data.secrets.request.SecretServiceError
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.secret_form_close
import com.letta.mobile.sharedui.resources.secret_form_decline
import com.letta.mobile.sharedui.resources.secret_form_declined
import com.letta.mobile.sharedui.resources.secret_form_destination
import com.letta.mobile.sharedui.resources.secret_form_done
import com.letta.mobile.sharedui.resources.secret_form_expired
import com.letta.mobile.sharedui.resources.secret_form_failed_forbidden
import com.letta.mobile.sharedui.resources.secret_form_failed_invalid_value
import com.letta.mobile.sharedui.resources.secret_form_failed_journal
import com.letta.mobile.sharedui.resources.secret_form_failed_refused
import com.letta.mobile.sharedui.resources.secret_form_failed_uncertain
import com.letta.mobile.sharedui.resources.secret_form_failed_unreachable
import com.letta.mobile.sharedui.resources.secret_form_failed_write
import com.letta.mobile.sharedui.resources.secret_form_field_label
import com.letta.mobile.sharedui.resources.secret_form_hide
import com.letta.mobile.sharedui.resources.secret_form_retry
import com.letta.mobile.sharedui.resources.secret_form_reveal
import com.letta.mobile.sharedui.resources.secret_form_submit
import com.letta.mobile.sharedui.resources.secret_form_submitting
import com.letta.mobile.sharedui.resources.secret_form_writes
import com.letta.mobile.sharedui.resources.secret_request_purpose_label
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

/** What the field shows for every typed character: the field's own text never holds anything else. */
const val SECRET_MASK_CHAR: Char = '\u2022'

private val RevealDuration = 15.seconds

/**
 * The secure surface that answers one secret request: what will be written where and for whom,
 * the agent's purpose labelled as agent-authored, a masked field, submit and decline, and then
 * the receipt (or why it failed). It is a dedicated surface, never part of chat content.
 *
 * The field's own text is mask characters only; each edit is forwarded to [controller]'s
 * [SecretBuffer], the one place the value lives, so nothing the text field keeps, saves, copies or
 * exposes to accessibility holds the value. Nothing here is saveable state, so the field is never
 * restored. The value is zeroed when the form leaves composition, and on every exit the controller
 * handles. The optional reveal toggle shows the value until it is turned off, the form stops
 * editing, or [RevealDuration] passes. The platform's [SecureSurfaceEffect] protects the window
 * while the form is shown.
 */
@Composable
fun SecretRequestForm(controller: SecretFormController, agentLabel: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val capabilities = SecureSurfaceEffect()
    LaunchedEffect(controller, capabilities) { controller.surfaceShown(capabilities) }
    DisposableEffect(controller) {
        controller.open()
        onDispose { controller.dispose() }
    }
    Column(modifier.testTag(SecretRequestFormTags.FORM), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
        RequestSummary(controller, agentLabel)
        when (val current = state) {
            SecretFormState.Idle, is SecretFormState.Editing, SecretFormState.Submitting -> EntrySection(controller, current)
            is SecretFormState.Done ->
                Outcome(stringResource(Res.string.secret_form_done, current.receipt.itemId.value, current.receipt.fieldName.value), onClose)
            is SecretFormState.Failed -> FailureSection(controller, current.failure, onClose)
            SecretFormState.Expired -> Outcome(stringResource(Res.string.secret_form_expired), onClose)
            SecretFormState.Declined -> Outcome(stringResource(Res.string.secret_form_declined), onClose)
        }
    }
}

/** What will be written where, for whom, why, and where the value goes. */
@Composable
private fun RequestSummary(controller: SecretFormController, agentLabel: String) {
    val target = controller.target
    Text(
        stringResource(Res.string.secret_form_writes, target.item.value, target.field.value, target.keystore.value, agentLabel),
        style = MaterialTheme.typography.bodyMedium,
    )
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair)) {
        Text(
            stringResource(Res.string.secret_request_purpose_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Agent-authored plain text: drawn as a plain Text, never as markdown or links.
        Text(controller.details.purpose.text.value, style = MaterialTheme.typography.bodyMedium)
    }
    Text(
        stringResource(Res.string.secret_form_destination, agentLabel),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The masked field with its reveal toggle, then decline and submit. */
@Composable
private fun EntrySection(controller: SecretFormController, state: SecretFormState) {
    val field = remember(controller) { TextFieldState() }
    val revealed = remember(controller) { mutableStateOf(false) }
    val editing = state is SecretFormState.Editing
    val focus = remember { FocusRequester() }
    LaunchedEffect(editing) {
        if (!editing) {
            field.clearText()
            revealed.value = false
        }
    }
    LaunchedEffect(revealed.value) {
        if (revealed.value) {
            delay(RevealDuration)
            revealed.value = false
        }
    }
    LaunchedEffect(controller) { focus.requestFocus() }
    SecretField(SecretFieldModel(controller, field, revealed, editing), Modifier.focusRequester(focus))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = controller::decline, enabled = editing, modifier = Modifier.testTag(SecretRequestFormTags.DECLINE)) {
            Text(stringResource(Res.string.secret_form_decline))
        }
        Button(
            onClick = { controller.submit() },
            enabled = SecretFormMachine.canSubmit(state),
            modifier = Modifier.testTag(SecretRequestFormTags.SUBMIT),
        ) {
            val label = if (state == SecretFormState.Submitting) Res.string.secret_form_submitting else Res.string.secret_form_submit
            Text(stringResource(label))
        }
    }
}

/** Everything the masked field needs, kept together so the field takes one model. */
private class SecretFieldModel(
    val controller: SecretFormController,
    val field: TextFieldState,
    val revealed: MutableState<Boolean>,
    val editing: Boolean,
)

@Composable
private fun SecretField(model: SecretFieldModel, modifier: Modifier) {
    val masking = remember(model.controller) { MaskingInput(model.controller) }
    val reveal = remember(model.controller) { RevealOutput(model.controller) }
    SecureTextInput {
        OutlinedTextField(
            state = model.field,
            modifier = modifier.fillMaxWidth().semantics { password() }.testTag(SecretRequestFormTags.FIELD),
            readOnly = !model.editing,
            label = { Text(stringResource(Res.string.secret_form_field_label)) },
            trailingIcon = { RevealToggle(model) },
            inputTransformation = masking,
            outputTransformation = reveal.takeIf { model.revealed.value },
            keyboardOptions = SecretKeyboard,
            onKeyboardAction = { model.controller.submit() },
            lineLimits = TextFieldLineLimits.SingleLine,
        )
    }
}

@Composable
private fun RevealToggle(model: SecretFieldModel) {
    val shown = model.revealed.value
    val label = stringResource(if (shown) Res.string.secret_form_hide else Res.string.secret_form_reveal)
    IconButton(
        onClick = { model.revealed.value = !shown },
        enabled = model.editing,
        modifier = Modifier.testTag(SecretRequestFormTags.REVEAL),
    ) {
        Icon(if (shown) LettaIcons.VisibilityOff else LettaIcons.Visibility, contentDescription = label)
    }
}

/** A password keyboard without suggestions, autocorrect or capitals; its action submits. */
private val SecretKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Password,
    imeAction = ImeAction.Done,
)

@Composable
private fun FailureSection(controller: SecretFormController, failure: SecretFormFailure, onClose: () -> Unit) {
    Message(failureMessage(failure), MaterialTheme.colorScheme.error)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm, Alignment.End),
    ) {
        if (failure.retryable) {
            OutlinedButton(onClick = controller::decline, modifier = Modifier.testTag(SecretRequestFormTags.DECLINE)) {
                Text(stringResource(Res.string.secret_form_decline))
            }
            OutlinedButton(onClick = controller::retry, modifier = Modifier.testTag(SecretRequestFormTags.RETRY)) {
                Text(stringResource(Res.string.secret_form_retry))
            }
        }
        CloseButton(onClose)
    }
}

@Composable
private fun Outcome(message: String, onClose: () -> Unit) {
    Message(message, MaterialTheme.colorScheme.onSurface)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { CloseButton(onClose) }
}

@Composable
private fun Message(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(SecretRequestFormTags.MESSAGE),
    )
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    Button(onClick = onClose, modifier = Modifier.testTag(SecretRequestFormTags.CLOSE)) {
        Text(stringResource(Res.string.secret_form_close))
    }
}

/** The person-facing text of [failure]: fixed per code, never provider output. */
@Composable
private fun failureMessage(failure: SecretFormFailure): String = when (failure) {
    is SecretFormFailure.WriteFailed -> writeFailureMessage(failure.reason)
    is SecretFormFailure.Refused -> refusalMessage(failure.error)
    SecretFormFailure.Unreachable -> stringResource(Res.string.secret_form_failed_unreachable)
}

@Composable
private fun writeFailureMessage(reason: SecretFailureReason): String = when (reason) {
    SecretFailureReason.ROLLBACK_FAILED, SecretFailureReason.INTERRUPTED ->
        stringResource(Res.string.secret_form_failed_uncertain, reason.wire())
    else -> stringResource(Res.string.secret_form_failed_write, reason.wire())
}

@Composable
private fun refusalMessage(error: SecretServiceError): String = when (error) {
    SecretServiceError.INVALID_VALUE -> stringResource(Res.string.secret_form_failed_invalid_value)
    SecretServiceError.JOURNAL_UNAVAILABLE -> stringResource(Res.string.secret_form_failed_journal)
    SecretServiceError.FORBIDDEN -> stringResource(Res.string.secret_form_failed_forbidden)
    else -> stringResource(Res.string.secret_form_failed_refused, error.name.lowercase())
}

/** The contract's wire code of [this] reason (`vault_locked`), shown so the person can report it. */
private fun SecretFailureReason.wire(): String = name.lowercase()

/**
 * Forwards each edit of the field to the controller's buffer and leaves only mask characters in
 * the field. Changes are applied last to first so each one's original range is still valid.
 * An edit that is not allowed (the form is not editing, or the result is too long) is reverted.
 */
@OptIn(ExperimentalFoundationApi::class)
private class MaskingInput(private val controller: SecretFormController) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val count = changes.changeCount
        if (count == 0) return
        if (controller.state.value !is SecretFormState.Editing || length > SecretBuffer.MAX_CHARS) {
            revertAllChanges()
            return
        }
        val ranges = (0 until count).map { changes.getRange(it) }
        val originals = (0 until count).map { changes.getOriginalRange(it) }
        val typed = asCharSequence()
        for (index in count - 1 downTo 0) {
            val inserted = TypedSlice(typed, ranges[index].start, ranges[index].end)
            controller.edit(originals[index].start until originals[index].end, inserted)
        }
        for (index in count - 1 downTo 0) {
            val range = ranges[index]
            replace(range.start, range.end, SECRET_MASK_CHAR.toString().repeat(range.length))
        }
    }
}

/** While revealed, draws the buffer's characters in place of the masks (same length, so the cursor stays put). */
private class RevealOutput(private val controller: SecretFormController) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val shown = length
        controller.reveal { view -> if (view.length == shown) replace(0, shown, view) }
    }
}

/** A window onto the typed characters of one change, read without making a string; it prints nothing. */
private class TypedSlice(private val source: CharSequence, private val start: Int, private val end: Int) : CharSequence {
    override val length: Int get() = end - start

    override fun get(index: Int): Char = source[start + index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = TypedSlice(source, start + startIndex, start + endIndex)

    override fun toString(): String = SecretBuffer.REDACTED
}
