package com.letta.mobile.data.session

import android.content.Context
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.routedBackendKind
import com.letta.mobile.data.repository.usesAppServerChannel
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.iroh.IrohConnectConfig
import com.letta.mobile.data.transport.iroh.NotebookPeerProvisioning
import com.letta.mobile.data.transport.appserver.AppServerWebSocketDial
import com.letta.mobile.data.transport.iroh.DirectAppServerDialer
import com.letta.mobile.data.transport.iroh.openDirectAppServerDial
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * Hilt-owned transport binder for a session graph generation.
 *
 * Every Android backend is an App Server reached through one [IrohChannelTransport] (shared
 * [routedBackendKind]; mode is authoritative over leftover `iroh://` URLs, as on desktop):
 * - IROH dials the host over QUIC;
 * - LOCAL_RUNTIME dials the on-device runtime's loopback server through [LocalRuntimeDialer]
 *   (letta-mobile-by4vm.110.6);
 * - DIRECT_WS dials the Settings "Remote host" ws:// address (the testability seam) directly.
 * A missing config or an http(s) address (another backend family's seam) gets a [NoOpChannelTransport].
 */
@Singleton
class SessionChannelTransportFactory @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val externalToolRegistry: ExternalToolRegistry? = null,
    /** Shares canvases through the Iroh host; attached to each Iroh transport this makes. */
    private val canvasClient: com.letta.mobile.data.transport.iroh.IrohCanvasRelayClient? = null,
    private val notebookStore: com.letta.mobile.data.canvas.NotebookLocalStore? = null,
    /** Lazy: resolving it builds the runtime host, which only the first LOCAL_RUNTIME dial needs. */
    private val localRuntimeDialer: dagger.Lazy<LocalRuntimeDialer>? = null,
) {
    fun create(
        scope: CoroutineScope,
        activeConfig: LettaConfig?,
        settingsRepository: ISettingsRepository?,
    ): IChannelTransport {
        fun reportChoice(chosen: String) {
            com.letta.mobile.util.Telemetry.event(
                "IrohSelect", "transport", "chosen" to chosen,
                "mode" to activeConfig?.mode?.name, "url" to activeConfig?.serverUrl,
            )
        }
        return when (activeConfig.routedBackendKind()) {
            BackendKind.IROH -> {
                reportChoice("iroh")
                IrohChannelTransport(
                    scope = scope,
                    onConnect = { com.letta.mobile.runtime.iroh.IrohAndroidInit.install(appContext) },
                    // d6e8g.9 / dpv7m.2: a stable device NodeId so reconnects reuse
                    // the identity a host paired; sealed with a Keystore key.
                    secretKeyStore = com.letta.mobile.data.security.KeystoreIrohIdentityStore.forApp(appContext),
                    externalToolRegistry = externalToolRegistry,
                    notebookStore = notebookStore,
                    // by4vm.50: cadence syncs only on unmetered + (charging or screen on).
                    syncBudget = AndroidSyncBudgetPolicy(appContext),
                    notebookPeers = {
                        NotebookPeerProvisioning.read(appContext.filesDir.toPath().resolve("notebooks/peers.json"))
                    },
                    activeConfigProvider = { settingsRepository?.activeConfig?.value?.toConnectConfig() },
                ).also { transport ->
                    // Canvases ride beside the App Server on the same host; ends with this graph.
                    canvasClient?.attach(transport.readyHandle, scope)
                }
            }
            BackendKind.LOCAL_RUNTIME -> {
                // No Iroh host behind this backend: canvases stay on this device, and say so.
                canvasClient?.detach()
                reportChoice("local-runtime")
                val dialer = localRuntimeDialer?.get() ?: return NoOpChannelTransport()
                directTransport(scope, settingsRepository, dialer)
            }
            BackendKind.DIRECT_WS -> {
                canvasClient?.detach()
                // An http(s) address is another backend family's (the remote-source seam), not a socket.
                val serverUrl = activeConfig?.serverUrl?.trim()?.takeIf { activeConfig.usesAppServerChannel() }
                    ?: return NoOpChannelTransport().also { reportChoice("noop-no-app-server") }
                reportChoice("direct-ws")
                directTransport(scope, settingsRepository) { _ ->
                    openDirectAppServerDial(AppServerWebSocketDial(serverUrl, activeConfig.accessToken), scope)
                }
            }
        }
    }

    /** The channel over a direct ws:// App Server: the same transport as Iroh, dialled by [dialer]. */
    private fun directTransport(
        scope: CoroutineScope,
        settingsRepository: ISettingsRepository?,
        dialer: DirectAppServerDialer,
    ): IChannelTransport = IrohChannelTransport(
        scope = scope,
        externalToolRegistry = externalToolRegistry,
        directDialer = dialer,
        activeConfigProvider = { settingsRepository?.activeConfig?.value?.toConnectConfig() },
    )
}

private fun LettaConfig.toConnectConfig() = IrohConnectConfig(
    baseShimUrl = serverUrl,
    token = accessToken.orEmpty(),
    deviceId = "android-letta-mobile",
    clientVersion = "android-iroh-active-config",
)
