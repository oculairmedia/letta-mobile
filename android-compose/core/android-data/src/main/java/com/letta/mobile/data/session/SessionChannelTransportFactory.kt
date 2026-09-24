package com.letta.mobile.data.session

import android.content.Context
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.iroh.IrohConnectConfig
import com.letta.mobile.runtime.LocalLettaBackend
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * Hilt-owned transport binder for a session graph generation.
 *
 * Selects Iroh QUIC or NoOp via shared [sessionBackendBinding] — mode is
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
) {
    fun create(
        scope: CoroutineScope,
        activeConfig: LettaConfig?,
        localRuntimeBackend: LocalLettaBackend?,
        settingsRepository: ISettingsRepository?,
    ): IChannelTransport {
        val forceIroh = IrohChannelTransport.shouldUseIroh(activeConfig?.serverUrl)
        fun reportChoice(chosen: String) {
            com.letta.mobile.util.Telemetry.event(
                "IrohSelect", "transport", "chosen" to chosen,
                "mode" to activeConfig?.mode?.name, "url" to activeConfig?.serverUrl,
            )
        }
        return when (activeConfig.sessionBackendBinding(forceIroh = forceIroh)) {
            SessionBackendBinding.Iroh -> {
                reportChoice("iroh")
                IrohChannelTransport(
                    scope = scope,
                    onConnect = { com.letta.mobile.runtime.iroh.IrohAndroidInit.install(appContext) },
                    // d6e8g.9: persist a stable device NodeId (0600 key file in
                    // the app-private filesDir) so reconnects reuse one identity
                    // — required for server-side pairing to bind this device.
                    secretKeyStore = com.letta.mobile.data.controller.node.iroh.FileIrohSecretKeyStore(
                        java.io.File(appContext.filesDir, "iroh-client-identity.key").path,
                    ),
                    externalToolRegistry = externalToolRegistry,
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
            SessionBackendBinding.LocalRuntime -> {
                // No Iroh host behind this backend: canvases stay on this device, and say so.
                canvasClient?.detach()
                reportChoice("noop-local")
                NoOpChannelTransport()
            }
            SessionBackendBinding.RemoteHttpOrWs -> {
                canvasClient?.detach()
                reportChoice(if (localRuntimeBackend != null) "noop-local" else "noop-rest")
                NoOpChannelTransport()
            }
        }
    }
}
