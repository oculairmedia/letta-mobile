package com.letta.mobile.desktop.qr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.pairing.PairInviteSession
import com.letta.mobile.data.pairing.PairedHostStore
import com.letta.mobile.data.pairing.PairingAdminRepository
import com.letta.mobile.data.pairing.PairingFlow
import com.letta.mobile.data.pairing.PairingState
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.transport.iroh.IrohPairingRedeemer
import com.letta.mobile.desktop.data.desktopConfigIdFor
import com.letta.mobile.desktop.security.DesktopIrohIdentity
import com.letta.mobile.qr.ZxingQrEncoder
import com.letta.mobile.ui.pairing.AgentChoice
import com.letta.mobile.ui.pairing.PairCodeEntry
import com.letta.mobile.ui.pairing.PairDeviceActions
import com.letta.mobile.ui.pairing.PairDevicePanel
import com.letta.mobile.ui.pairing.PairingStatusCard
import com.letta.mobile.ui.pairing.QrModulesImage
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the desktop pairing card needs from the shell. */
internal class DesktopPairingBindings(
    /** admin_rpc to the connected host; null when no host is connected (inviting needs one). */
    val rpc: AdminRpcInvoker?,
    val agents: List<AgentChoice>,
    val onConfigSaved: (LettaConfig) -> Unit,
    val onOpenChat: () -> Unit,
    /** A deep-linked pairing link to redeem once; [onPendingLinkConsumed] clears it. */
    val pendingLink: String? = null,
    val onPendingLinkConsumed: () -> Unit = {},
)

/**
 * Settings card for scan-to-connect on desktop (letta-mobile-dpv7m.3):
 *  - Join: paste a pairing link (or open one — `meridian://pair` deep links land
 *    here) and this desktop pairs with that host, saves it, and connects.
 *  - Invite: the install-and-pair QRs — one to get the phone app, one minted
 *    by the HOST for this pairing — plus the paired-device list with revoke.
 */
@Composable
internal fun DesktopPairingCard(
    bindings: DesktopPairingBindings,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(LettaDimens.Space.xl),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
        ) {
            JoinHostSection(bindings)
            bindings.rpc?.let { rpc ->
                HorizontalDivider()
                InstallAppSection()
                InviteSection(rpc, bindings.agents)
            }
        }
    }
}

@Composable
private fun JoinHostSection(bindings: DesktopPairingBindings) {
    val scope = rememberCoroutineScope()
    val flow = remember {
        PairingFlow(
            redeemer = IrohPairingRedeemer(IrohSecretKeyStore { DesktopIrohIdentity.loadOrCreate() }),
            store = PairedHostStore { host ->
                bindings.onConfigSaved(
                    LettaConfig(
                        id = desktopConfigIdFor(host.serverUrl),
                        mode = LettaConfig.Mode.SELF_HOSTED,
                        serverUrl = host.serverUrl,
                        accessToken = null,
                    ),
                )
            },
            deviceName = DesktopPairingStrings.deviceName(),
            nowMs = System::currentTimeMillis,
        )
    }
    val state by flow.state.collectAsState()
    // A meridian://pair link opened from outside the app is consumed once, from memory.
    LaunchedEffect(bindings.pendingLink) {
        val link = bindings.pendingLink ?: return@LaunchedEffect
        bindings.onPendingLinkConsumed()
        flow.submit(link)
    }

    Text(DesktopPairingStrings.JOIN_TITLE, style = MaterialTheme.typography.titleMedium)
    Text(DesktopPairingStrings.JOIN_BODY, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state is PairingState.Idle) {
        PairCodeEntry(onSubmit = { raw -> scope.launch { flow.submit(raw) } })
    }
    PairingStatusCard(state = state, onScanAgain = flow::reset, onOpenChat = bindings.onOpenChat)
}

@Composable
private fun InstallAppSection() {
    val qr = remember { runCatching { ZxingQrEncoder.encode(DesktopPairingStrings.APP_DOWNLOAD_URL) }.getOrNull() }
    Text(DesktopPairingStrings.INSTALL_TITLE, style = MaterialTheme.typography.titleMedium)
    Text(DesktopPairingStrings.INSTALL_BODY, color = MaterialTheme.colorScheme.onSurfaceVariant)
    qr?.let { QrModulesImage(it, modifier = Modifier.widthIn(max = LettaDimens.Pane.qrCodeMaxWidth)) }
}

@Composable
private fun InviteSection(rpc: AdminRpcInvoker, agents: List<AgentChoice>) {
    val scope = rememberCoroutineScope()
    val session = remember(rpc) { PairInviteSession(PairingAdminRepository(rpc), ZxingQrEncoder, scope) }
    val state by session.state.collectAsState()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session) { session.refresh() }
    LaunchedEffect(state.invite?.inviteId) {
        if (state.invite == null) return@LaunchedEffect
        while (true) {
            delay(DesktopPairingStrings.POLL_INTERVAL_MS)
            nowMs = System.currentTimeMillis()
            session.refresh()
        }
    }
    PairDevicePanel(
        state = state,
        agents = agents,
        actions = PairDeviceActions(
            onMint = session::mint,
            onRevokeDevice = session::revokeDevice,
            onRevokeInvite = session::revokeInvite,
            onDismissInvite = session::dismissInvite,
        ),
        nowMs = nowMs,
    )
}

internal object DesktopPairingStrings {
    const val JOIN_TITLE = "Connect to a host with a pairing link"
    const val JOIN_BODY = "Paste the link from another device's \"Pair a device\" screen or the host's `pair` command."
    const val INSTALL_TITLE = "1. Get the Meridian app on the phone"
    const val INSTALL_BODY = "Scan with the phone's camera to download the Android app, then scan the pairing code below."

    /** The latest Android release; the APK is attached to every GitHub release. */
    const val APP_DOWNLOAD_URL = "https://github.com/oculairmedia/letta-mobile/releases/latest"
    const val POLL_INTERVAL_MS = 3_000L

    fun deviceName(): String =
        runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Desktop"
}
