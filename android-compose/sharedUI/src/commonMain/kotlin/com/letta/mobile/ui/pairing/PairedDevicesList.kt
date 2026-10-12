package com.letta.mobile.ui.pairing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.pairing.PairInviteUiState
import com.letta.mobile.data.pairing.PairedDevice
import com.letta.mobile.data.pairing.PendingInvite
import com.letta.mobile.data.pairing.access
import com.letta.mobile.ui.theme.LettaDimens

/**
 * Settings > Paired devices (letta-mobile-dpv7m.5): THIS DEVICE / OTHER DEVICES
 * with access + agent-scope chips and last activity, per-device revoke with an
 * inline confirmation ("Revoking…" until the host confirms), "Revoke all other
 * devices", and pending invites with Cancel.
 *
 * The agent chip says "Lists N agents" on purpose: today the host filters an
 * agent-limited device's listings but does not yet block opening a
 * conversation by id (letta-mobile-dpv7m.4), and the footnote says so.
 */
@Composable
fun PairedDevicesList(
    state: PairInviteUiState,
    actions: PairDeviceActions,
    nowMs: Long,
    modifier: Modifier = Modifier,
    /** The invite already on the card above; not repeated under Pending. */
    onScreenInviteId: String? = null,
) {
    val groups = state.groups
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
        Text(PairingStrings.DEVICES_TITLE, style = MaterialTheme.typography.titleMedium)
        if (state.devices.isEmpty()) {
            Text(PairingStrings.NO_DEVICES, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        groups.thisDevice?.let { self ->
            GroupLabel(PairingStrings.THIS_DEVICE)
            DeviceRow(self, statusLine = PairingStrings.ACTIVE_NOW, modifier = Modifier.testTag(PairingTags.THIS_DEVICE))
        }
        if (groups.others.isNotEmpty()) {
            GroupLabel(PairingStrings.OTHER_DEVICES)
            groups.others.forEach { device ->
                RevocableDeviceRow(device, revoking = device.nodeId in state.revoking, nowMs = nowMs, onRevoke = actions.onRevokeDevice)
            }
        }
        if (groups.canRevokeAllOthers) RevokeAllOthers(actions.onRevokeAllOthers)
        if (groups.anyAgentScoped) {
            Text(
                PairingStrings.AGENT_SCOPE_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(PairingTags.AGENT_SCOPE_NOTE),
            )
        }
        PendingInvites(state.pending.filterNot { it.inviteId == onScreenInviteId }, state.cancelling, nowMs, actions.onRevokeInvite)
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DeviceRow(device: PairedDevice, statusLine: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().testTag(PairingTags.deviceRow(device.nodeId))) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
            Text(device.name.ifBlank { device.nodeId.take(FINGERPRINT_CHARS) })
            Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
                ScopeChip(PairingStrings.access(device.access))
                ScopeChip(PairingStrings.agentScope(device.agentIds))
            }
            Text(statusLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

/** A read-only label chip: the scope is spelled out in text, never colour alone. */
@Composable
private fun ScopeChip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(LettaDimens.Space.hair / 2, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.hair),
        )
    }
}

@Composable
private fun RevocableDeviceRow(device: PairedDevice, revoking: Boolean, nowMs: Long, onRevoke: (String) -> Unit) {
    var confirming by remember(device.nodeId) { mutableStateOf(false) }
    val name = device.name.ifBlank { device.nodeId.take(FINGERPRINT_CHARS) }
    DeviceRow(device, statusLine = PairingStrings.lastActive(device.lastSeenAtMs, nowMs)) {
        when {
            revoking -> Text(PairingStrings.REVOKING, modifier = Modifier.testTag(PairingTags.revoking(device.nodeId)))
            !confirming -> TextButton(onClick = { confirming = true }, modifier = Modifier.testTag(PairingTags.revokeDevice(device.nodeId))) {
                Text(PairingStrings.REVOKE)
            }
        }
    }
    if (confirming && !revoking) {
        DestructiveConfirm(
            message = PairingStrings.revokeConfirm(name),
            confirmLabel = PairingStrings.REVOKE_ACCESS,
            confirmTag = PairingTags.revokeConfirm(device.nodeId),
            onConfirm = { confirming = false; onRevoke(device.nodeId) },
            onCancel = { confirming = false },
        )
    }
}

@Composable
private fun RevokeAllOthers(onRevokeAll: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    if (!confirming) {
        TextButton(onClick = { confirming = true }, modifier = Modifier.testTag(PairingTags.REVOKE_ALL_OTHERS)) {
            Text(PairingStrings.REVOKE_ALL_OTHERS, color = MaterialTheme.colorScheme.error)
        }
        return
    }
    DestructiveConfirm(
        message = PairingStrings.REVOKE_ALL_OTHERS_BODY,
        confirmLabel = PairingStrings.REVOKE_ALL_OTHERS,
        confirmTag = PairingTags.REVOKE_ALL_CONFIRM,
        onConfirm = { confirming = false; onRevokeAll() },
        onCancel = { confirming = false },
    )
}

/** Inline confirmation: Cancel first (the safe default), the destructive action spelled out. */
@Composable
private fun DestructiveConfirm(message: String, confirmLabel: String, confirmTag: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = LettaDimens.Space.hair, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LettaDimens.Space.md), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
            Text(message)
            Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onCancel) { Text(PairingStrings.CANCEL) }
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.testTag(confirmTag),
                ) { Text(confirmLabel) }
            }
        }
    }
}

@Composable
private fun PendingInvites(pending: List<PendingInvite>, cancelling: Set<String>, nowMs: Long, onCancel: (String) -> Unit) {
    if (pending.isEmpty()) return
    Text(PairingStrings.PENDING_TITLE, style = MaterialTheme.typography.titleMedium)
    pending.forEach { invite ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(invite.name.ifBlank { PairingStrings.INVITE_TITLE })
                Text(
                    PairingStrings.expiresIn(invite.expiresAtMs - nowMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (invite.inviteId in cancelling) {
                Text(PairingStrings.CANCELLING)
            } else {
                TextButton(onClick = { onCancel(invite.inviteId) }, modifier = Modifier.testTag(PairingTags.cancelInvite(invite.inviteId))) {
                    Text(PairingStrings.CANCEL)
                }
            }
        }
    }
}

private const val FINGERPRINT_CHARS = 8
