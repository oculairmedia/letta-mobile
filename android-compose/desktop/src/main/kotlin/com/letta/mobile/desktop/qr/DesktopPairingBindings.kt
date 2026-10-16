package com.letta.mobile.desktop.qr

import com.letta.mobile.data.controller.node.iroh.IrohNodeIdentity
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.pairing.PairedHostStore
import com.letta.mobile.data.pairing.PairingFlow
import com.letta.mobile.data.transport.iroh.IrohPairingRedeemer
import com.letta.mobile.desktop.DesktopAgentFocus
import com.letta.mobile.desktop.DesktopDestination
import com.letta.mobile.desktop.DesktopShellContext
import com.letta.mobile.desktop.selectedDestination
import com.letta.mobile.desktop.data.desktopConfigIdFor
import com.letta.mobile.desktop.security.DesktopIrohIdentity
import com.letta.mobile.qr.ZxingQrEncoder
import com.letta.mobile.ui.pairing.AgentChoice
import com.letta.mobile.ui.pairing.InstallAndPairBindings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop binding for the shared install-and-pair card (letta-mobile-dpv7m.3):
 * this desktop's Iroh identity, its saved-config store, the ZXing encoder, and
 * shell navigation. All UI lives in sharedUI ([com.letta.mobile.ui.pairing.InstallAndPairCard]).
 */
internal fun desktopInstallAndPairBindings(context: DesktopShellContext, focus: DesktopAgentFocus): InstallAndPairBindings {
    val bootstrap = context.core.bootstrap
    val navigator = context.navigator
    return InstallAndPairBindings(
        joinFlow = { desktopPairingFlow(onConfigSaved = bootstrap.applyConfig) },
        qrEncoder = ZxingQrEncoder,
        rpc = bootstrap.bootstrapState.pairingRpc,
        agents = focus.rosterAgents.map { AgentChoice(it.id.value, it.name) },
        onOpenChat = { navigator.selectedDestination = DesktopDestination.Conversations },
        nowMs = System::currentTimeMillis,
        pendingLink = navigator.pendingPairLink,
        onPendingLinkConsumed = { navigator.pendingPairLink = null },
        selfNodeId = ::desktopNodeId,
    )
}

/** This desktop's own NodeId (the identity it pairs and chats with); null if it cannot be derived. */
private suspend fun desktopNodeId(): String? = withContext(Dispatchers.IO) {
    runCatching { IrohNodeIdentity.nodeIdHexFromSecretBytes(DesktopIrohIdentity.loadOrCreate()) }.getOrNull()
}

private fun desktopPairingFlow(onConfigSaved: (LettaConfig) -> Unit): PairingFlow =
    PairingFlow(
        redeemer = IrohPairingRedeemer(IrohSecretKeyStore { DesktopIrohIdentity.loadOrCreate() }),
        store = PairedHostStore { host ->
            onConfigSaved(
                LettaConfig(
                    id = desktopConfigIdFor(host.serverUrl),
                    mode = LettaConfig.Mode.SELF_HOSTED,
                    serverUrl = host.serverUrl,
                    accessToken = null,
                ),
            )
        },
        deviceName = desktopDeviceName(),
        nowMs = System::currentTimeMillis,
    )

internal fun desktopDeviceName(): String =
    runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Desktop"
