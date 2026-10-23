@file:OptIn(ExperimentalWasmJsInterop::class)

package com.letta.mobile.web

import com.letta.mobile.data.pairing.QrEncoder
import com.letta.mobile.data.pairing.composition.PairingNetwork
import com.letta.mobile.data.pairing.composition.PairingPorts
import com.letta.mobile.data.pairing.composition.RelayPairingNetwork
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.ui.pairing.AgentChoice
import com.letta.mobile.ui.pairing.composition.PairingShellPorts
import com.letta.mobile.web.data.RelayInviteAuthenticator
import com.letta.mobile.web.data.WebPairedHostStore
import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlin.time.Clock

/**
 * letta-mobile-wisqe S1b: what the browser owns for pairing. Its persistent Iroh identity (S1a,
 * `iroh-identity.js` through `index.html`), the relay as its only network, the web backend as the
 * paired host's home, and the live session's admin_rpc; the card and everything it runs are built
 * by shared code ([com.letta.mobile.ui.pairing.composition.installAndPairBindings]). The browser
 * has no QR encoder, so invite links are shown and copied as text.
 */
internal class WebPairingPorts(
    private val backend: WebBackend,
    private val workspace: ShellWorkspaceState,
    override val hostStore: WebPairedHostStore,
    override val adminRpc: AdminRpcInvoker?,
) : PairingPorts, PairingShellPorts {
    override suspend fun loadIdentityKey(): ByteArray =
        checkNotNull(irohLoadOrCreateIdentity().await<JsString?>()) {
            "This browser cannot keep an Iroh identity (private window or insecure origin), so it cannot stay paired"
        }.toString().hexToByteArray()

    override val network: PairingNetwork = RelayPairingNetwork(RelayInviteAuthenticator(), nodeIds = { irohNodeIdOf(it.toHexString()) })

    override val deviceName: String = "Browser"

    override val qrEncoder = QrEncoder { error("QR codes are not drawn in the browser") }

    override fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    override val agents: List<AgentChoice> get() = backend.agents.value.map { AgentChoice(it.id.value, it.name) }

    override val pendingLink: String? get() = workspace.pendingPairLink

    override fun consumePendingLink() {
        workspace.pendingPairLink = null
    }

    override fun openChat() {
        workspace.navigate(ShellDestination.Chat())
    }

    override fun shareLink(link: String) {
        browserNavigator.clipboard?.writeText(link)
    }
}

/** This browser's identity secret as hex, created on first use; null when it cannot keep one (`index.html`). */
private external fun irohLoadOrCreateIdentity(): Promise<JsString?>

/** The NodeId a hex identity secret dials as (`index.html`). */
private external fun irohNodeIdOf(secretHex: String): String

/** The page's `navigator`; only its clipboard is read. */
private external interface BrowserNavigator : JsAny {
    /** Absent outside a secure context. */
    val clipboard: BrowserClipboard?
}

private external interface BrowserClipboard : JsAny {
    fun writeText(text: String): Promise<JsAny?>
}

@JsName("navigator")
private external val browserNavigator: BrowserNavigator
