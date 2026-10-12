package com.letta.mobile.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import com.letta.mobile.data.pairing.PairInviteRequest
import com.letta.mobile.data.pairing.PairInviteUiState
import com.letta.mobile.data.pairing.PairedDevice
import com.letta.mobile.data.pairing.PendingInvite
import com.letta.mobile.data.pairing.QrModules
import com.letta.mobile.ui.theme.LettaDimens

/** An agent the inviter may share with the new device. */
data class AgentChoice(val id: String, val name: String)

/** Inviter callbacks, grouped so the panel's signature stays small. */
class PairDeviceActions(
    val onMint: (PairInviteRequest) -> Unit,
    val onRevokeDevice: (String) -> Unit,
    val onRevokeInvite: (String) -> Unit,
    val onDismissInvite: () -> Unit,
)

/**
 * The inviter side of scan-to-connect (letta-mobile-dpv7m.2), shared by the
 * phone and desktop: choose which agents the new device may use, show a
 * one-time QR, see it get accepted, and manage paired devices.
 */
@Composable
fun PairDevicePanel(
    state: PairInviteUiState,
    agents: List<AgentChoice>,
    actions: PairDeviceActions,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg)) {
        Text(PairingStrings.INVITE_TITLE, style = MaterialTheme.typography.titleLarge)
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(PairingTags.INVITE_ERROR))
        }
        state.accepted?.let {
            Text(
                PairingStrings.accepted(it.name),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(PairingTags.INVITE_ACCEPTED),
            )
        }
        val qr = state.qr
        val invite = state.invite
        if (qr != null && invite != null) {
            InviteCode(qr = qr, expiresInMs = invite.expiresAtMs - nowMs, onCancel = { actions.onRevokeInvite(invite.inviteId) })
        } else {
            InviteForm(agents = agents, enabled = !state.busy, onMint = actions.onMint)
        }
        HorizontalDivider()
        DeviceList(state.devices, onRevoke = actions.onRevokeDevice)
        PendingList(state.pending.filterNot { it.inviteId == invite?.inviteId }, onRevoke = actions.onRevokeInvite)
    }
}

@Composable
private fun InviteForm(agents: List<AgentChoice>, enabled: Boolean, onMint: (PairInviteRequest) -> Unit) {
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(PairingStrings.INVITE_DEVICE_NAME) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (agents.isNotEmpty()) {
        Text(PairingStrings.INVITE_AGENTS, style = MaterialTheme.typography.labelLarge)
        Text(
            if (selected.isEmpty()) PairingStrings.INVITE_ALL_AGENTS else PairingStrings.deviceScope(selected.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        agents.forEach { agent ->
            val checked = agent.id in selected
            // The whole row toggles (bigger target, one accessible checkbox node).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = checked, role = Role.Checkbox) { on ->
                        selected = if (on) selected + agent.id else selected - agent.id
                    }
                    .testTag(PairingTags.agentOption(agent.id)),
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Text(agent.name)
            }
        }
    }
    Button(
        onClick = { onMint(PairInviteRequest(deviceName = name.trim(), agentIds = selected)) },
        enabled = enabled,
        modifier = Modifier.testTag(PairingTags.INVITE_SHOW),
    ) { Text(PairingStrings.INVITE_SHOW_CODE) }
}

@Composable
private fun InviteCode(qr: QrModules, expiresInMs: Long, onCancel: () -> Unit) {
    Text(PairingStrings.INVITE_SCAN_PROMPT)
    QrModulesImage(qr, modifier = Modifier.widthIn(max = LettaDimens.Pane.qrCodeMaxWidth).testTag(PairingTags.INVITE_QR))
    Text(
        "${PairingStrings.expiresAt(expiresInMs / MS_PER_MINUTE)} · ${PairingStrings.INVITE_ONE_TIME}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = onCancel) { Text(PairingStrings.INVITE_CANCEL) }
}

@Composable
private fun DeviceList(devices: List<PairedDevice>, onRevoke: (String) -> Unit) {
    Text(PairingStrings.DEVICES_TITLE, style = MaterialTheme.typography.titleMedium)
    if (devices.isEmpty()) {
        Text(PairingStrings.NO_DEVICES, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    devices.forEach { device ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name.ifBlank { device.nodeId.take(FINGERPRINT_CHARS) })
                Text(
                    "${device.nodeId.take(FINGERPRINT_CHARS)}… · ${PairingStrings.deviceScope(device.agentIds?.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onRevoke(device.nodeId) }, modifier = Modifier.testTag(PairingTags.revokeDevice(device.nodeId))) {
                Text(PairingStrings.REVOKE)
            }
        }
    }
}

@Composable
private fun PendingList(pending: List<PendingInvite>, onRevoke: (String) -> Unit) {
    if (pending.isEmpty()) return
    Text(PairingStrings.PENDING_TITLE, style = MaterialTheme.typography.titleMedium)
    pending.forEach { invite ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(invite.name, modifier = Modifier.weight(1f))
            TextButton(onClick = { onRevoke(invite.inviteId) }) { Text(PairingStrings.REVOKE) }
        }
    }
}

private const val FINGERPRINT_CHARS = 8
private const val MS_PER_MINUTE = 60_000L
