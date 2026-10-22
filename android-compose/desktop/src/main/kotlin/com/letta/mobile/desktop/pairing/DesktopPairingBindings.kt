package com.letta.mobile.desktop.pairing

import com.letta.mobile.data.chat.runtime.BackendConfigPolicy
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.pairing.PairedHostStore
import com.letta.mobile.data.pairing.QrEncoder
import com.letta.mobile.data.pairing.composition.IrohPairingNetwork
import com.letta.mobile.data.pairing.composition.PairingNetwork
import com.letta.mobile.data.pairing.composition.PairingPorts
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.desktop.security.DesktopIrohIdentity
import com.letta.mobile.qr.ZxingQrEncoder
import com.letta.mobile.ui.pairing.AgentChoice
import com.letta.mobile.ui.pairing.composition.PairingShellPorts
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * What the desktop owns for pairing (letta-mobile-dpv7m.3, wisqe.1): its Iroh identity file,
 * its saved-config store ([applyConfig]), the host's admin_rpc and the ZXing encoder. The card
 * and everything it runs are built by shared code
 * ([com.letta.mobile.ui.pairing.composition.installAndPairBindings]).
 */
internal class DesktopPairingPorts(
    private val applyConfig: (LettaConfig) -> Unit,
    override val adminRpc: AdminRpcInvoker?,
) : PairingPorts {
    override suspend fun loadIdentityKey(): ByteArray = DesktopIrohIdentity.loadOrCreate()

    override val network: PairingNetwork = IrohPairingNetwork()

    override val hostStore = PairedHostStore { host ->
        applyConfig(
            LettaConfig(
                id = BackendConfigPolicy.stableConfigId(CONFIG_ID_PREFIX, host.serverUrl),
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = host.serverUrl,
                accessToken = null,
            ),
        )
    }

    override val deviceName: String =
        runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Desktop"

    override val qrEncoder: QrEncoder = ZxingQrEncoder

    override fun nowMs(): Long = System.currentTimeMillis()

    private companion object {
        /** The desktop's saved-config id namespace (the same one desktopConfigIdFor uses). */
        const val CONFIG_ID_PREFIX = "desktop"
    }
}

/** The desktop shell around the card: the roster, deep links held by the workspace, and the clipboard as the share target. */
internal class DesktopPairingShell(roster: List<Agent>, private val workspace: ShellWorkspaceState) : PairingShellPorts {
    override val agents: List<AgentChoice> = roster.map { AgentChoice(it.id.value, it.name) }

    override val pendingLink: String? get() = workspace.pendingPairLink

    override fun consumePendingLink() {
        workspace.pendingPairLink = null
    }

    override fun openChat() {
        workspace.showPage(ShellDestination.Chat())
    }

    override fun shareLink(link: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(link), null)
    }
}
