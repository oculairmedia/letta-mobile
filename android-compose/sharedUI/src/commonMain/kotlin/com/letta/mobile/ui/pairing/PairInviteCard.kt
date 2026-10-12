package com.letta.mobile.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import com.letta.mobile.data.pairing.InviteCountdown
import com.letta.mobile.data.pairing.InvitePhase
import com.letta.mobile.data.pairing.MintedPairInvite
import com.letta.mobile.data.pairing.PairInviteRequest
import com.letta.mobile.data.pairing.QrModules
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/** An agent the inviter may share with the new device. */
data class AgentChoice(val id: String, val name: String)

/**
 * The inviter's card through every [InvitePhase] (letta-mobile-dpv7m.5):
 * Configuring (least-privilege scope form) -> Showing/Expiring (QR on a white
 * card + "Expires in m:ss", or the full link with Copy/Share) -> Expired /
 * Cancelled ("New code", never an auto-refresh) or Redeemed.
 */
@Composable
fun PairInviteCard(
    phase: InvitePhase,
    qr: QrModules?,
    agents: List<AgentChoice>,
    actions: PairDeviceActions,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
        when (phase) {
            InvitePhase.Configuring -> InviteForm(agents = agents, enabled = !busy, onMint = actions.onMint)
            is InvitePhase.Showing -> LiveInvite(phase.invite, qr, phase.remainingMs, expiring = false, actions)
            is InvitePhase.Expiring -> LiveInvite(phase.invite, qr, phase.remainingMs, expiring = true, actions)
            is InvitePhase.Expired -> EndedInvite(qr, PairingStrings.CODE_EXPIRED, PairingStrings.CODE_EXPIRED_BODY, PairingTags.INVITE_EXPIRED, actions)
            InvitePhase.Cancelled -> EndedInvite(null, PairingStrings.CODE_CANCELLED, PairingStrings.CODE_CANCELLED_BODY, PairingTags.INVITE_CANCELLED, actions)
            is InvitePhase.Redeemed -> Redeemed(phase.device.name, actions.onChangeAccess)
        }
    }
}

@Composable
private fun InviteForm(agents: List<AgentChoice>, enabled: Boolean, onMint: (PairInviteRequest) -> Unit) {
    var name by remember { mutableStateOf("") }
    // Least privilege by default (dpv7m.5): named agents, chat only, five minutes.
    var allAgents by remember { mutableStateOf(agents.isEmpty()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var allowSettings by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(PairingStrings.INVITE_DEVICE_NAME) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(PairingStrings.INVITE_ACCESS, style = MaterialTheme.typography.labelLarge)
    ChoiceRow(PairingStrings.INVITE_ALL_AGENTS, selected = allAgents, tag = PairingTags.INVITE_ALL_AGENTS) { allAgents = true }
    if (agents.isNotEmpty()) {
        ChoiceRow(PairingStrings.INVITE_CHOOSE_AGENTS, selected = !allAgents, tag = PairingTags.INVITE_CHOOSE_AGENTS) { allAgents = false }
    }
    if (!allAgents) {
        AgentChecklist(agents, selected) { selected = it }
    }
    SettingsToggle(allowSettings) { allowSettings = it }
    val chosenNames = agents.filter { it.id in selected }.map { it.name }
    Text(
        PairingStrings.accessSummary(if (allAgents) null else chosenNames, allowSettings, PairInviteRequest.DEFAULT_TTL_MS),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val ready = allAgents || selected.isNotEmpty()
    if (!ready) Text(PairingStrings.INVITE_CHOOSE_AT_LEAST_ONE, style = MaterialTheme.typography.bodySmall)
    Button(
        onClick = {
            onMint(
                PairInviteRequest(
                    deviceName = name.trim(),
                    agentIds = if (allAgents) emptySet() else selected,
                    role = PairInviteRequest.roleFor(allowSettings),
                ),
            )
        },
        enabled = enabled && ready,
        modifier = Modifier.testTag(PairingTags.INVITE_SHOW),
    ) { Text(PairingStrings.INVITE_SHOW_CODE) }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, tag: String, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).testTag(tag),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label)
    }
}

@Composable
private fun AgentChecklist(agents: List<AgentChoice>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    agents.forEach { agent ->
        val checked = agent.id in selected
        // The whole row toggles (bigger target, one accessible checkbox node).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = LettaDimens.Space.lg)
                .toggleable(value = checked, role = Role.Checkbox) { on -> onChange(if (on) selected + agent.id else selected - agent.id) }
                .testTag(PairingTags.agentOption(agent.id)),
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Text(agent.name)
        }
    }
}

@Composable
private fun SettingsToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).testTag(PairingTags.INVITE_SETTINGS),
    ) {
        Column(Modifier.weight(1f)) {
            Text(PairingStrings.INVITE_SETTINGS_TOGGLE)
            Text(PairingStrings.INVITE_SETTINGS_BODY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun LiveInvite(invite: MintedPairInvite, qr: QrModules?, remainingMs: Long, expiring: Boolean, actions: PairDeviceActions) {
    var showLink by remember(invite.inviteId) { mutableStateOf(qr == null) }
    Text(PairingStrings.INVITE_STEP_1)
    Text(PairingStrings.INVITE_STEP_2)
    Text(PairingStrings.INVITE_STEP_3)
    if (showLink || qr == null) {
        PairLinkBlock(invite.pairLink)
    } else {
        QrCard(qr, remainingMs, dimmed = false)
    }
    Countdown(remainingMs, expiring)
    LinkActions(invite.pairLink, showLink = showLink, canToggle = qr != null, onToggle = { showLink = !showLink }, onShare = actions.onShareLink)
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        OutlinedButton(onClick = actions.onNewCode, modifier = Modifier.testTag(PairingTags.INVITE_NEW_CODE)) { Text(PairingStrings.NEW_CODE) }
        TextButton(onClick = { actions.onRevokeInvite(invite.inviteId) }, modifier = Modifier.testTag(PairingTags.INVITE_CANCEL)) {
            Text(PairingStrings.CANCEL_CODE)
        }
    }
}

/** The QR on a white card with a quiet zone, never theme-inverted, at least [LettaDimens.Pane.qrCodeMinWidth]. */
@Composable
private fun QrCard(qr: QrModules, remainingMs: Long, dimmed: Boolean) {
    Surface(color = Color.White, shape = MaterialTheme.shapes.large, modifier = Modifier.testTag(PairingTags.INVITE_QR)) {
        QrModulesImage(
            qr,
            dimmed = dimmed,
            modifier = Modifier
                .padding(LettaDimens.Space.md)
                .widthIn(min = LettaDimens.Pane.qrCodeMinWidth, max = LettaDimens.Pane.qrCodeMaxWidth)
                .clearAndSetSemantics { contentDescription = PairingStrings.qrDescription(remainingMs) },
        )
    }
}

/** The full pairing link (product decision: the fallback is the link, not a short code — a code cannot carry the host). */
@Composable
private fun PairLinkBlock(link: String) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = LettaDimens.Space.hair, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LettaDimens.Space.md), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
            Text(link, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(PairingTags.INVITE_LINK))
            Text(PairingStrings.LINK_SECRET_NOTE, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Latches the last countdown announcement; a plain holder so composition never writes state it reads. */
private class AnnouncementLatch {
    private var lastRemainingMs: Long? = null
    private var text: String = ""

    fun update(remainingMs: Long): String {
        lastRemainingMs?.let { previous ->
            InviteCountdown.crossedThreshold(previous, remainingMs)?.let { text = PairingStrings.countdownAnnouncement(it) }
        }
        lastRemainingMs = remainingMs
        return text
    }
}

/**
 * "Expires in 4:32" (not a live region, so it never chatters); screen readers
 * get a polite announcement only at 60 s and 10 s. "Expiring soon" is spelled
 * out, not just coloured.
 */
@Composable
private fun Countdown(remainingMs: Long, expiring: Boolean) {
    val latch = remember { AnnouncementLatch() }
    val announcement = latch.update(remainingMs)
    val label = PairingStrings.expiresIn(remainingMs) + if (expiring) " · ${PairingStrings.EXPIRING_SOON}" else ""
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (expiring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(PairingTags.INVITE_COUNTDOWN),
    )
    if (announcement.isNotEmpty()) {
        Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement })
    }
}

@Composable
private fun LinkActions(link: String, showLink: Boolean, canToggle: Boolean, onToggle: () -> Unit, onShare: ((String) -> Unit)?) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(link) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs), verticalAlignment = Alignment.CenterVertically) {
        if (canToggle) {
            TextButton(onClick = onToggle, modifier = Modifier.testTag(PairingTags.INVITE_TOGGLE_LINK)) {
                Text(if (showLink) PairingStrings.SHOW_QR else PairingStrings.SHOW_LINK)
            }
        }
        TextButton(
            onClick = { clipboard.setText(AnnotatedString(link)); copied = true },
            modifier = Modifier.testTag(PairingTags.INVITE_COPY),
        ) {
            Icon(LettaIcons.Copy, contentDescription = null)
            Text(if (copied) PairingStrings.LINK_COPIED else PairingStrings.COPY_LINK)
        }
        onShare?.let { share ->
            TextButton(onClick = { share(link) }, modifier = Modifier.testTag(PairingTags.INVITE_SHARE)) {
                Icon(LettaIcons.Share, contentDescription = null)
                Text(PairingStrings.SHARE_LINK)
            }
        }
    }
}

@Composable
private fun EndedInvite(qr: QrModules?, title: String, body: String, tag: String, actions: PairDeviceActions) {
    // The secret is masked once the code is over: only a greyed QR, never the link.
    qr?.let { QrCard(it, remainingMs = 0, dimmed = true) }
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(tag))
    Text(body, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        Button(onClick = actions.onNewCode, modifier = Modifier.testTag(PairingTags.INVITE_NEW_CODE)) { Text(PairingStrings.NEW_CODE) }
        TextButton(onClick = actions.onChangeAccess) { Text(PairingStrings.CHANGE_ACCESS) }
    }
}

@Composable
private fun Redeemed(name: String, onDone: () -> Unit) {
    Icon(LettaIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    Text(
        PairingStrings.accepted(name),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(PairingTags.INVITE_ACCEPTED),
    )
    Button(onClick = onDone) { Text(PairingStrings.DONE) }
}
