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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import com.letta.mobile.data.pairing.PairingState
import com.letta.mobile.data.pairing.QrModules
import com.letta.mobile.ui.theme.LettaDimens

/**
 * What the joining device sees while a scanned code is processed
 * (letta-mobile-dpv7m.2). Renders nothing while [PairingState.Idle] so the
 * platform's scanner (camera on Android, paste field on desktop) shows through.
 */
@Composable
fun PairingStatusCard(
    state: PairingState,
    onScanAgain: () -> Unit,
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is PairingState.Idle) return
    Surface(
        modifier = modifier.fillMaxWidth().testTag(PairingTags.STATUS),
        shape = MaterialTheme.shapes.large,
        tonalElevation = LettaDimens.Space.hair,
    ) {
        Column(
            modifier = Modifier.padding(LettaDimens.Space.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            when (state) {
                is PairingState.Pairing -> PairingProgress(state.hostFingerprint)
                is PairingState.Paired -> PairedContent(state, onOpenChat)
                is PairingState.Failed -> FailedContent(PairingStrings.error(state.error), onScanAgain)
                PairingState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun PairingProgress(fingerprint: String) {
    CircularProgressIndicator()
    Text(PairingStrings.pairing(fingerprint), textAlign = TextAlign.Center)
}

@Composable
private fun PairedContent(state: PairingState.Paired, onOpenChat: () -> Unit) {
    Text(PairingStrings.PAIRED_TITLE, style = MaterialTheme.typography.titleMedium)
    Text(
        text = if (state.alreadyPaired) PairingStrings.ALREADY_PAIRED_BODY else PairingStrings.PAIRED_BODY,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        PairingStrings.hostLine(state.host.nodeId.take(FINGERPRINT_CHARS)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onOpenChat, modifier = Modifier.testTag(PairingTags.OPEN_CHAT)) { Text(PairingStrings.OPEN_CHAT) }
}

@Composable
private fun FailedContent(message: String, onScanAgain: () -> Unit) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag(PairingTags.ERROR),
    )
    OutlinedButton(onClick = onScanAgain, modifier = Modifier.testTag(PairingTags.SCAN_AGAIN)) { Text(PairingStrings.SCAN_AGAIN) }
}

/**
 * Paste a pairing link: desktop's scanner, and Android's fallback when the
 * camera is unavailable. The value is the one-time invite; it lives only in
 * this field's state until submitted.
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
 * contrast, so this deliberately ignores the theme.
 */
@Composable
fun QrModulesImage(modules: QrModules, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().aspectRatio(1f)) {
        val total = modules.size + QUIET_ZONE_MODULES * 2
        val cell = size.minDimension / total
        drawRect(color = Color.White, size = Size(size.minDimension, size.minDimension))
        for (row in 0 until modules.size) {
            for (col in 0 until modules.size) {
                if (!modules.isDark(row, col)) continue
                drawRect(
                    color = Color.Black,
                    topLeft = Offset((col + QUIET_ZONE_MODULES) * cell, (row + QUIET_ZONE_MODULES) * cell),
                    size = Size(cell, cell),
                )
            }
        }
    }
}

private const val QUIET_ZONE_MODULES = 4
private const val FINGERPRINT_CHARS = 8
