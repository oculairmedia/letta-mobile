package com.letta.mobile.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.letta.mobile.data.pairing.PairInviteSession
import com.letta.mobile.data.pairing.PairingAdminRepository
import com.letta.mobile.data.pairing.PairingFlow
import com.letta.mobile.data.pairing.PairingState
import com.letta.mobile.data.pairing.QrEncoder
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.tokens.LettaTextStyles
import kotlinx.coroutines.launch

/**
 * What the install-and-pair card needs from its platform container.
 * The platform binds identity, persistence and the QR encoder; the card owns all UI.
 */
class InstallAndPairBindings(
    /** Builds the join flow once (identity + paired-host store are platform services). */
    val joinFlow: () -> PairingFlow,
    val qrEncoder: QrEncoder,
    /** admin_rpc to the connected host; null when no host is connected (inviting needs one). */
    val rpc: AdminRpcInvoker?,
    val agents: List<AgentChoice>,
    val onOpenChat: () -> Unit,
    val nowMs: () -> Long,
    /** A deep-linked pairing link to redeem once; [onPendingLinkConsumed] clears it. */
    val pendingLink: String? = null,
    val onPendingLinkConsumed: () -> Unit = {},
    /** This device's own NodeId (groups "This device" in the list); null when unknown. */
    val selfNodeId: suspend () -> String? = { null },
    /** The platform share sheet, when there is one. */
    val onShareLink: ((String) -> Unit)? = null,
)

/**
 * The body of the Settings card for scan-to-connect. The Settings card around it supplies the surface,
 * the join title and the paste hint ([PairingStrings.JOIN_TITLE], [PairingStrings.PASTE_ONLY_HINT]):
 *  - Join: paste a pairing link (or open one — `meridian://pair` deep links land
 *    here); the trust prompt confirms the host before anything is dialled.
 *  - Invite: the install-and-pair QRs — one to get the phone app, one minted
 *    by the HOST for this pairing — plus the paired-device list with revoke.
 * [onPasteBlocked] says how to paste when the link field's Paste could not read the clipboard.
 */
@Composable
internal fun InstallAndPairContent(
    bindings: InstallAndPairBindings,
    onPasteBlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalPairingPlacement provides PairingPlacement.SETTINGS_CARD) {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
            JoinHostSection(bindings, onPasteBlocked)
            bindings.rpc?.let { rpc ->
                HorizontalDivider(Modifier.padding(vertical = LettaDimens.Space.sm))
                InstallAppSection(bindings.qrEncoder)
                InviteSection(rpc, bindings)
            }
        }
    }
}

@Composable
private fun JoinHostSection(bindings: InstallAndPairBindings, onPasteBlocked: () -> Unit) {
    val scope = rememberCoroutineScope()
    val flow = remember { bindings.joinFlow() }
    val state by flow.state.collectAsState()
    // A meridian://pair link opened from outside the app is consumed once, from memory; it stops at the trust prompt.
    LaunchedEffect(bindings.pendingLink) {
        val link = bindings.pendingLink ?: return@LaunchedEffect
        bindings.onPendingLinkConsumed()
        flow.submit(link)
    }
    val actions = pairingScanActions(flow, scope, bindings.onOpenChat)
    PairLinkField(enabled = state.acceptsInput(), onSubmit = actions.onSubmit, onPasteBlocked = onPasteBlocked)
    PairingStatusCard(
        state = state,
        actions = actions,
        nowMs = rememberTickingNow(bindings.nowMs, active = state is PairingState.ConfirmHost),
    )
}

/** The scanner's actions over a [PairingFlow]; [onOpenChat] after a successful pairing. */
fun pairingScanActions(flow: PairingFlow, scope: kotlinx.coroutines.CoroutineScope, onOpenChat: () -> Unit) = PairingScanActions(
    onSubmit = { raw -> flow.submit(raw) },
    onConfirm = { scope.launch { flow.confirm() } },
    onCancel = flow::cancel,
    onRetry = { scope.launch { flow.retry() } },
    onScanAgain = flow::reset,
    onOpenChat = onOpenChat,
)

@Composable
private fun InstallAppSection(qrEncoder: QrEncoder) {
    val qr = remember(qrEncoder) { runCatching { qrEncoder.encode(PairingStrings.APP_DOWNLOAD_URL) }.getOrNull() }
    Text(PairingStrings.INSTALL_TITLE, style = LettaTextStyles.titleSemibold)
    Text(PairingStrings.INSTALL_BODY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    qr?.let { QrModulesImage(it, modifier = Modifier.widthIn(max = LettaDimens.Pane.qrCodeMaxWidth)) }
}

@Composable
private fun InviteSection(rpc: AdminRpcInvoker, bindings: InstallAndPairBindings) {
    val scope = rememberCoroutineScope()
    val session = remember(rpc) { PairInviteSession(PairingAdminRepository(rpc), bindings.qrEncoder, scope, bindings.nowMs) }
    LaunchedEffect(session) { session.identifySelf(runCatching { bindings.selfNodeId() }.getOrNull()) }
    PairDeviceSessionPanel(PairDeviceBinding(session, bindings.agents, bindings.nowMs, bindings.onShareLink))
}
