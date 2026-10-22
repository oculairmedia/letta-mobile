@file:OptIn(ExperimentalWasmJsInterop::class)

package com.letta.mobile.web.data

import androidx.compose.runtime.MutableState
import com.letta.mobile.data.model.IrohServerAddress
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.pairing.PairedHost
import com.letta.mobile.data.pairing.PairedHostStore
import com.letta.mobile.data.pairing.PairingFlow
import com.letta.mobile.data.pairing.QrEncoder
import com.letta.mobile.data.pairing.RelayInviteAuthenticator
import com.letta.mobile.data.pairing.RelayPairingRedeemer
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.ui.pairing.InstallAndPairBindings
import com.letta.mobile.web.iroh.IrohWasmAppServerTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Clock

/**
 * The browser's dial for an invite (letta-mobile-wisqe S1b): one Iroh session through the relay
 * with the browser's persistent identity (S1a), torn down after the single auth exchange. Its
 * request id only has to be unique on this one-shot connection.
 */
internal class LiveWebInviteAuthenticator(private val scope: CoroutineScope) : RelayInviteAuthenticator {
    override suspend fun authenticate(
        address: IrohServerAddress,
        inviteToken: String,
        peerName: String,
    ): AppServerInboundFrame.AuthResponse {
        val transport = IrohWasmAppServerTransport.connect(address, scope)
        try {
            transport.isConnected.first { it }
            return DefaultAppServerClient(transport, parentScope = scope).auth(
                AppServerCommand.Auth(
                    requestId = webRequestId(Random.nextLong().toString(), "pair", 1),
                    token = inviteToken,
                    capabilities = listOf(FRAME_PART_CAPABILITY),
                    peerName = peerName,
                ),
            )
        } finally {
            withContext(NonCancellable) { runCatching { transport.close() } }
        }
    }
}

/**
 * Saves a paired host as the web's backend and switches to it. No credential is kept: the invite
 * was one-time and the browser authenticates by its NodeId from then on. It also binds the shared
 * install-and-pair card for the browser, which only joins a host: it mints no invites, so it draws
 * no QR codes.
 */
internal class WebPairedHostStore(
    private val config: MutableState<LettaConfig>,
    private val store: WebSettingsStore,
) : PairedHostStore {
    override suspend fun saveAndActivate(host: PairedHost) {
        val paired = config.value.copy(mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = host.serverUrl, accessToken = null)
        store.saveBackend(paired)
        config.value = paired
    }

    /** The card's bindings: joins run in [scope], and a successful one goes to [onOpenChat]. */
    fun installAndPairBindings(scope: CoroutineScope, onOpenChat: () -> Unit): InstallAndPairBindings {
        val nowMs = { Clock.System.now().toEpochMilliseconds() }
        return InstallAndPairBindings(
            joinFlow = { PairingFlow(RelayPairingRedeemer(LiveWebInviteAuthenticator(scope)), this, BROWSER_DEVICE_NAME, nowMs) },
            qrEncoder = QrEncoder { error("QR codes are not shown in the browser") },
            rpc = null,
            agents = emptyList(),
            onOpenChat = onOpenChat,
            nowMs = nowMs,
            selfNodeId = { irohLocalNodeId().await<JsString?>()?.toString() },
        )
    }

    private companion object {
        const val BROWSER_DEVICE_NAME = "Browser"
    }
}

/** This browser's NodeId, its persistent identity (S1a, `index.html`); null when it has none. */
private external fun irohLocalNodeId(): kotlin.js.Promise<JsString?>
