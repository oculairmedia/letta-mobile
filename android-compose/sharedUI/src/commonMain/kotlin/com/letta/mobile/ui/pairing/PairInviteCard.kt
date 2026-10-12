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
import com.letta.mobile.data.pairing.PairedDevice
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
    view: PairDeviceView,
    actions: PairDeviceActions,
    modifier: Modifier = Modifier,
) {
    val qr = view.state.qr
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
        when (val phase = view.phase) {
            InvitePhase.Configuring -> InviteForm(agents = view.agents, enabled = !view.state.busy, onMint = actions.onMint)
            is InvitePhase.Showing -> LiveInvite(LiveCode(phase.invite, phase.remainingMs, expiring = false), qr, actions)
            is InvitePhase.Expiring -> LiveInvite(LiveCode(phase.invite, phase.remainingMs, expiring = true), qr, actions)
            is InvitePhase.Expired -> EndedInvite(qr, EndedCode.EXPIRED, actions)
            InvitePhase.Cancelled -> EndedInvite(null, EndedCode.CANCELLED, actions)
            is InvitePhase.Redeemed -> Redeemed(phase.device, actions.onChangeAccess)
        }
    }
}

/** The two scope radios of the form. */
private enum class AgentScopeOption(val label: String, val tag: String) {
    ALL(PairingStrings.INVITE_ALL_AGENTS, PairingTags.INVITE_ALL_AGENTS),
    CHOOSE(PairingStrings.INVITE_CHOOSE_AGENTS, PairingTags.INVITE_CHOOSE_AGENTS),
}

/**
 * The scope form's values and the rules that derive the request from them,
 * kept out of the composable. Least privilege by default (dpv7m.5): named
 * agents (when any are offered), chat only, five minutes.
 */
private class InviteFormState(var agents: List<AgentChoice>) {
    var name by mutableStateOf("")
    var scope by mutableStateOf(if (agents.isEmpty()) AgentScopeOption.ALL else AgentScopeOption.CHOOSE)
    var selected by mutableStateOf(emptySet<String>())
    var allowSettings by mutableStateOf(false)

    val options: List<AgentScopeOption> get() = if (agents.isEmpty()) listOf(AgentScopeOption.ALL) else AgentScopeOption.entries
    val choosing: Boolean get() = scope == AgentScopeOption.CHOOSE
    val ready: Boolean get() = !choosing || selected.isNotEmpty()

    val summary: InviteScopeSummary
        get() = InviteScopeSummary(
            agentNames = agents.filter { it.id in selected }.map { it.name }.takeIf { choosing },
            allowSettings = allowSettings,
            ttlMs = PairInviteRequest.DEFAULT_TTL_MS,
        )

    fun request() = PairInviteRequest(
        deviceName = name.trim(),
        agentIds = selected.takeIf { choosing }.orEmpty(),
        role = PairInviteRequest.roleFor(allowSettings),
    )
}

@Composable
private fun InviteForm(agents: List<AgentChoice>, enabled: Boolean, onMint: (PairInviteRequest) -> Unit) {
    // One form per screen visit; the offered agents are refreshed each composition without resetting it.
    val form = remember { InviteFormState(agents) }.also { it.agents = agents }
    OutlinedTextField(
        value = form.name,
        onValueChange = { form.name = it },
        label = { Text(PairingStrings.INVITE_DEVICE_NAME) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(PairingStrings.INVITE_ACCESS, style = MaterialTheme.typography.labelLarge)
    form.options.forEach { option -> ChoiceRow(option, selected = form.scope == option) { form.scope = option } }
    if (form.choosing) AgentChecklist(agents, form.selected) { form.selected = it }
    SettingsToggle(form.allowSettings) { form.allowSettings = it }
    Text(PairingStrings.accessSummary(form.summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!form.ready) Text(PairingStrings.INVITE_CHOOSE_AT_LEAST_ONE, style = MaterialTheme.typography.bodySmall)
    Button(
        onClick = { onMint(form.request()) },
        enabled = enabled && form.ready,
        modifier = Modifier.testTag(PairingTags.INVITE_SHOW),
    ) { Text(PairingStrings.INVITE_SHOW_CODE) }
}

@Composable
private fun ChoiceRow(option: AgentScopeOption, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).testTag(option.tag),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(option.label)
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

/** A code that can still be redeemed, and how long it has left. */
private class LiveCode(val invite: MintedPairInvite, val remainingMs: Long, val expiring: Boolean)

@Composable
private fun LiveInvite(code: LiveCode, qr: QrModules?, actions: PairDeviceActions) {
    var showLink by remember(code.invite.inviteId) { mutableStateOf(qr == null) }
    Text(PairingStrings.INVITE_STEP_1)
    Text(PairingStrings.INVITE_STEP_2)
    Text(PairingStrings.INVITE_STEP_3)
    if (showLink || qr == null) PairLinkBlock(code.invite) else QrCard(qr, remainingMs = code.remainingMs)
    Countdown(code)
    LinkActions(code.invite, toggle = qr?.let { LinkToggle(showLink) { showLink = !showLink } }, onShare = actions.onShareLink)
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        OutlinedButton(onClick = actions.onNewCode, modifier = Modifier.testTag(PairingTags.INVITE_NEW_CODE)) { Text(PairingStrings.NEW_CODE) }
        TextButton(onClick = { actions.onRevokeInvite(code.invite.inviteId) }, modifier = Modifier.testTag(PairingTags.INVITE_CANCEL)) {
            Text(PairingStrings.CANCEL_CODE)
        }
    }
}

/**
 * The QR on a white card with a quiet zone, never theme-inverted, at least
 * [LettaDimens.Pane.qrCodeMinWidth]. A null [remainingMs] is an ended code:
 * greyed so it can no longer be scanned by accident.
 */
@Composable
private fun QrCard(qr: QrModules, remainingMs: Long?) {
    Surface(color = Color.White, shape = MaterialTheme.shapes.large, modifier = Modifier.testTag(PairingTags.INVITE_QR)) {
        QrModulesImage(
            qr,
            dimmed = remainingMs == null,
            modifier = Modifier
                .padding(LettaDimens.Space.md)
                .widthIn(min = LettaDimens.Pane.qrCodeMinWidth, max = LettaDimens.Pane.qrCodeMaxWidth)
                .clearAndSetSemantics { contentDescription = PairingStrings.qrDescription(TimeLeft(remainingMs ?: 0)) },
        )
    }
}

/** The full pairing link (product decision: the fallback is the link, not a short code — a code cannot carry the host). */
@Composable
private fun PairLinkBlock(invite: MintedPairInvite) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = LettaDimens.Space.hair, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LettaDimens.Space.md), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
            Text(invite.pairLink, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(PairingTags.INVITE_LINK))
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
            InviteCountdown.crossedThreshold(previous, remainingMs)?.let { text = PairingStrings.countdownAnnouncement(TimeLeft(it * MS_PER_SECOND)) }
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
private fun Countdown(code: LiveCode) {
    val latch = remember { AnnouncementLatch() }
    val announcement = latch.update(code.remainingMs)
    val label = PairingStrings.expiresIn(TimeLeft(code.remainingMs)) + if (code.expiring) " · ${PairingStrings.EXPIRING_SOON}" else ""
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (code.expiring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(PairingTags.INVITE_COUNTDOWN),
    )
    if (announcement.isNotEmpty()) {
        Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement })
    }
}

/** The QR <-> link switch, present only when there is a QR to switch back to. */
private class LinkToggle(val showingLink: Boolean, val onToggle: () -> Unit)

@Composable
private fun LinkActions(invite: MintedPairInvite, toggle: LinkToggle?, onShare: ((String) -> Unit)?) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(invite.inviteId) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs), verticalAlignment = Alignment.CenterVertically) {
        toggle?.let {
            TextButton(onClick = it.onToggle, modifier = Modifier.testTag(PairingTags.INVITE_TOGGLE_LINK)) {
                Text(if (it.showingLink) PairingStrings.SHOW_QR else PairingStrings.SHOW_LINK)
            }
        }
        TextButton(
            onClick = { clipboard.setText(AnnotatedString(invite.pairLink)); copied = true },
            modifier = Modifier.testTag(PairingTags.INVITE_COPY),
        ) {
            Icon(LettaIcons.Copy, contentDescription = null)
            Text(if (copied) PairingStrings.LINK_COPIED else PairingStrings.COPY_LINK)
        }
        onShare?.let { share ->
            TextButton(onClick = { share(invite.pairLink) }, modifier = Modifier.testTag(PairingTags.INVITE_SHARE)) {
                Icon(LettaIcons.Share, contentDescription = null)
                Text(PairingStrings.SHARE_LINK)
            }
        }
    }
}

/** How a code ended: what the card says and the tag tests find it by. */
private enum class EndedCode(val title: String, val body: String, val tag: String) {
    EXPIRED(PairingStrings.CODE_EXPIRED, PairingStrings.CODE_EXPIRED_BODY, PairingTags.INVITE_EXPIRED),
    CANCELLED(PairingStrings.CODE_CANCELLED, PairingStrings.CODE_CANCELLED_BODY, PairingTags.INVITE_CANCELLED),
}

@Composable
private fun EndedInvite(qr: QrModules?, ended: EndedCode, actions: PairDeviceActions) {
    // The secret is masked once the code is over: only a greyed QR, never the link.
    qr?.let { QrCard(it, remainingMs = null) }
    Text(ended.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(ended.tag))
    Text(ended.body, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        Button(onClick = actions.onNewCode, modifier = Modifier.testTag(PairingTags.INVITE_NEW_CODE)) { Text(PairingStrings.NEW_CODE) }
        TextButton(onClick = actions.onChangeAccess) { Text(PairingStrings.CHANGE_ACCESS) }
    }
}

@Composable
private fun Redeemed(device: PairedDevice, onDone: () -> Unit) {
    Icon(LettaIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    Text(
        PairingStrings.accepted(device),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(PairingTags.INVITE_ACCEPTED),
    )
    Button(onClick = onDone) { Text(PairingStrings.DONE) }
}

private const val MS_PER_SECOND = 1_000L
