package com.letta.mobile.runtime.local

import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.runtime.host.LocalAppServerHost
import com.letta.mobile.data.session.LocalRuntimeDialer
import com.letta.mobile.data.session.ProcessScope
import com.letta.mobile.data.transport.appserver.AppServerReadinessExpectation
import com.letta.mobile.data.transport.appserver.AppServerWebSocketDial
import com.letta.mobile.data.transport.iroh.DirectAppServerDial
import com.letta.mobile.data.transport.iroh.IrohConnectConfig
import com.letta.mobile.data.transport.iroh.openDirectAppServerDial
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * letta-mobile-by4vm.110.6: how the session's App Server channel reaches the on-device runtime. Each
 * dial takes a lease on the one [LocalAppServerHost] (the first starts node: asset extraction on a
 * new install, then ~3 s to listen), points the runtime's model provider at the configured model,
 * and dials `ws://127.0.0.1:<port>` with the lease's capability token. Closing the dial releases
 * the lease; the in-process node keeps running for the app process (decision D3).
 */
@Singleton
class AndroidLocalRuntimeDialer @Inject constructor(
    private val host: LocalAppServerHost,
    private val launchSource: AndroidLocalAppServerLaunchSource,
    private val settingsRepository: ISettingsRepository,
    @param:ProcessScope private val scope: CoroutineScope,
) : LocalRuntimeDialer {
    override suspend fun dial(config: IrohConnectConfig): DirectAppServerDial {
        // Once node's one launch is over the host refuses without launching (a terminal failure:
        // the channel supervisor stops redialling, letta-mobile-by4vm.121.11).
        val lease = host.acquire()
        val backendDir = runCatching { launchSource.prepareModel(settingsRepository.activeConfig.value) }
            .onFailure { lease.close() }
            .getOrThrow()
        return openDirectAppServerDial(
            target = AppServerWebSocketDial(
                serverUrl = lease.serverUrl,
                bearerToken = lease.bearerToken,
                readiness = AppServerReadinessExpectation.LocalBackend,
                requestTimeoutMs = LOCAL_REQUEST_TIMEOUT_MS,
            ),
            scope = scope,
            localBackendDir = backendDir.absolutePath,
            release = lease::close,
        )
    }

    private companion object {
        /** agent_create runs memfs git on the phone: ~30–40 s on a Pixel 9 Pro. */
        const val LOCAL_REQUEST_TIMEOUT_MS = 90_000L
    }
}
