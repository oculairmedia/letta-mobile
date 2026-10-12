package com.letta.mobile.ui.pairing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.letta.mobile.data.pairing.PairingState
import com.letta.mobile.data.pairing.QrModules
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/** What the joining device can do from the status card and the scanner (letta-mobile-dpv7m.5). */
class PairingScanActions(
    /** A scanned, opened or pasted value. */
    val onSubmit: (String) -> Unit,
    /** "Connect" on the trust prompt — the only path to a dial. */
    val onConfirm: () -> Unit,
    /** "Cancel" on the trust prompt or while verifying. */
    val onCancel: () -> Unit,
    /** "Retry" after an unreachable host. */
    val onRetry: () -> Unit,
    val onScanAgain: () -> Unit,
    val onOpenChat: () -> Unit,
)

/**
 * What the joining device sees once a code was read (letta-mobile-dpv7m.2/.5):
 * the mandatory trust prompt, Verifying, Success, or one distinct error with one
 * action. Renders nothing while [PairingState.Idle] so the scanner shows through.
 * Status is always carried by text (and an icon), never colour alone.
 */
@Composable
fun PairingStatusCard(
    state: PairingState,
    actions: PairingScanActions,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    if (state is PairingState.Idle) return
    Surface(
        modifier = modifier.fillMaxWidth().testTag(PairingTags.STATUS),
        shape = MaterialTheme.shapes.large,
        tonalElevation = LettaDimens.Space.hair,
    ) {
        Column(
            modifier = Modifier.padding(LettaDimens.Space.lg).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            when (state) {
                is PairingState.ConfirmHost -> TrustPrompt(state, actions, nowMs)
                is PairingState.Pairing -> VerifyingContent(actions.onCancel)
                is PairingState.Paired -> PairedContent(state, actions.onOpenChat)
                is PairingState.Failed -> FailedContent(state, actions)
                PairingState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun TrustPrompt(state: PairingState.ConfirmHost, actions: PairingScanActions, nowMs: Long) {
    Column(
        modifier = Modifier.testTag(PairingTags.TRUST_PROMPT),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Text(PairingStrings.trustTitle(state), style = MaterialTheme.typography.titleMedium)
        Text(PairingStrings.trustHostLine(state), style = MaterialTheme.typography.bodyMedium)
        Text(PairingStrings.TRUST_ACCESS, style = MaterialTheme.typography.bodyMedium)
        Text(PairingStrings.trustExpiresLine(TimeLeft(state.expiresAtMs - nowMs)), style = MaterialTheme.typography.bodyMedium)
        Text(PairingStrings.TRUST_BODY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Destructive-safe default: Cancel comes first and is never the filled button's neighbour by accident.
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm), modifier = Modifier.align(Alignment.End)) {
            TextButton(onClick = actions.onCancel, modifier = Modifier.testTag(PairingTags.TRUST_CANCEL)) {
                Text(PairingStrings.CANCEL)
            }
            Button(onClick = actions.onConfirm, modifier = Modifier.testTag(PairingTags.TRUST_CONNECT)) {
                Text(PairingStrings.CONNECT)
            }
        }
    }
}

@Composable
private fun VerifyingContent(onCancel: () -> Unit) {
    CircularProgressIndicator()
    Column(
        modifier = Modifier.testTag(PairingTags.VERIFYING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Text(PairingStrings.VERIFYING_TITLE, style = MaterialTheme.typography.titleMedium)
        Text(PairingStrings.VERIFYING_STEP_CONTACT)
        Text(PairingStrings.VERIFYING_STEP_SECURE)
        Text(
            PairingStrings.VERIFYING_KEEP_OPEN,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    TextButton(onClick = onCancel, modifier = Modifier.testTag(PairingTags.VERIFY_CANCEL)) { Text(PairingStrings.CANCEL) }
}

@Composable
private fun PairedContent(state: PairingState.Paired, onOpenChat: () -> Unit) {
    Icon(LettaIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    Text(PairingStrings.pairedTitle(state), style = MaterialTheme.typography.titleMedium)
    Text(
        text = if (state.alreadyPaired) PairingStrings.ALREADY_PAIRED_BODY else PairingStrings.PAIRED_BODY,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.deviceName.isNotBlank()) {
        Text(
            PairingStrings.pairedDeviceLine(state),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(onClick = onOpenChat, modifier = Modifier.testTag(PairingTags.OPEN_CHAT)) { Text(PairingStrings.OPEN_CHAT) }
}

@Composable
private fun FailedContent(state: PairingState.Failed, actions: PairingScanActions) {
    Icon(LettaIcons.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
    Text(
        text = PairingStrings.error(state.error),
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag(PairingTags.ERROR),
    )
    if (state.retryable) {
        Button(onClick = actions.onRetry, modifier = Modifier.testTag(PairingTags.RETRY)) { Text(PairingStrings.RETRY) }
        TextButton(onClick = actions.onScanAgain, modifier = Modifier.testTag(PairingTags.SCAN_AGAIN)) {
            Text(PairingStrings.CANCEL)
        }
    } else {
        OutlinedButton(onClick = actions.onScanAgain, modifier = Modifier.testTag(PairingTags.SCAN_AGAIN)) {
            Text(PairingStrings.SCAN_AGAIN)
        }
    }
}

/**
 * Paste a pairing link: desktop's and web's scanner, and Android's "Paste link"
 * fallback. The value is the one-time invite; it lives only in this field's
 * state until submitted.
 */
@Composable
fun PairCodeEntry(onSubmit: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var value by remember { mutableStateOf("") }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(PairingStrings.PASTE_LABEL) },
            placeholder = { Text(PairingStrings.PASTE_HINT) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f).testTag(PairingTags.PASTE_FIELD),
        )
        Button(
            onClick = { onSubmit(value.trim()).also { value = "" } },
            enabled = enabled && value.isNotBlank(),
            modifier = Modifier.testTag(PairingTags.PASTE_SUBMIT),
        ) { Text(PairingStrings.PASTE_ACTION) }
    }
}

/**
 * Draws a QR grid black-on-white with a quiet zone — scanners need the fixed
 * contrast, so this deliberately ignores the theme. [dimmed] greys it out (an
 * expired code) so it can no longer be scanned by accident.
 */
@Composable
fun QrModulesImage(modules: QrModules, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    val ink = if (dimmed) Color.LightGray else Color.Black
    Canvas(modifier = modifier.fillMaxWidth().aspectRatio(1f)) {
        val total = modules.size + QUIET_ZONE_MODULES * 2
        val cell = size.minDimension / total
        drawRect(color = Color.White, size = Size(size.minDimension, size.minDimension))
        for (row in 0 until modules.size) {
            for (col in 0 until modules.size) {
                if (!modules.isDark(row, col)) continue
                drawRect(
                    color = ink,
                    topLeft = Offset((col + QUIET_ZONE_MODULES) * cell, (row + QUIET_ZONE_MODULES) * cell),
                    size = Size(cell, cell),
                )
            }
        }
    }
}

private const val QUIET_ZONE_MODULES = 4
