package com.letta.mobile.data.session

import android.content.Context
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.routedBackendKind
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.iroh.IrohConnectConfig
import com.letta.mobile.data.transport.iroh.NotebookPeerProvisioning
import com.letta.mobile.runtime.LocalLettaBackend
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * Hilt-owned transport binder for a session graph generation.
 *
 * Selects Iroh QUIC or NoOp via shared [routedBackendKind] — mode is
 * authoritative over leftover `iroh://` URLs (same policy as desktop). The
 * legacy shim WebSocket transport is gone (g70jb.3): REST/HTTP configs get a
 * [NoOpChannelTransport] and talk to the server over REST only.
 */
@Singleton
class SessionChannelTransportFactory @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val externalToolRegistry: ExternalToolRegistry? = null,
    /** Shares canvases through the Iroh host; attached to each Iroh transport this makes. */
    private val canvasClient: com.letta.mobile.data.transport.iroh.IrohCanvasRelayClient? = null,
    private val notebookStore: com.letta.mobile.data.canvas.NotebookLocalStore? = null,
) {
    fun create(
        scope: CoroutineScope,
        activeConfig: LettaConfig?,
        localRuntimeBackend: LocalLettaBackend?,
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
                    // the identity a host paired; sealed with a Keystore key (the
                    // old plaintext key file is migrated in place, same NodeId).
                    secretKeyStore = com.letta.mobile.data.security.KeystoreIrohIdentityStore.forApp(appContext),
                    externalToolRegistry = externalToolRegistry,
                    notebookStore = notebookStore,
                    // by4vm.50: cadence syncs only on unmetered + (charging or screen on).
                    syncBudget = AndroidSyncBudgetPolicy(appContext),
                    notebookPeers = {
                        NotebookPeerProvisioning.read(appContext.filesDir.toPath().resolve("notebooks/peers.json"))
                    },
                    activeConfigProvider = {
                        settingsRepository?.activeConfig?.value?.let { config ->
                            IrohConnectConfig(
                                baseShimUrl = config.serverUrl,
                                token = config.accessToken.orEmpty(),
                                deviceId = "android-letta-mobile",
                                clientVersion = "android-iroh-active-config",
                            )
                        }
                    },
                ).also { transport ->
                    // Canvases ride beside the App Server on the same host; ends with this graph.
                    canvasClient?.attach(transport.readyHandle, scope)
                }
            }
            BackendKind.LOCAL_RUNTIME -> {
                // No Iroh host behind this backend: canvases stay on this device, and say so.
                canvasClient?.detach()
                reportChoice("noop-local")
                NoOpChannelTransport()
            }
            BackendKind.DIRECT_WS -> {
                canvasClient?.detach()
                reportChoice(if (localRuntimeBackend != null) "noop-local" else "noop-rest")
                NoOpChannelTransport()
            }
        }
    }
}
