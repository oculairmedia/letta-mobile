package com.letta.mobile.data.health

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.isIrohBackend
import com.letta.mobile.data.transport.appserver.AppServerDiscovery
import com.letta.mobile.data.transport.appserver.AppServerProbeResult

/** Asks an App Server whether it answers: `AppServerProbe.probe` in production, a fake in tests. */
fun interface AppServerReachability {
    suspend fun probe(baseUrl: String, bearerToken: String?): AppServerProbeResult
}

/**
 * letta-mobile-ckmub.11: the backend picker's online dot, from an App Server probe.
 *
 * - The on-device runtime ([LettaConfig.Mode.LOCAL]) is always there.
 * - An `iroh://` host is [ServerHealthState.ONLINE]: its QUIC connection is the real signal, and a
 *   probe would have to dial it (the App Server allows one control session; probing must never
 *   disturb it).
 * - A `ws(s)://` or `http(s)://` App Server is asked for `/app-server-info`. Any answer means the
 *   server is up: a rejected token or an incompatible server is a problem the switch itself
 *   reports, not a reason to refuse the tap. Only no answer at all ([AppServerProbeResult.Unavailable])
 *   is [ServerHealthState.OFFLINE], as is an address that is not an App Server URL.
 */
class ServerHealthProbe(private val appServer: AppServerReachability) {
    suspend fun probe(config: LettaConfig): ServerHealthState {
        if (config.mode == LettaConfig.Mode.LOCAL || config.isIrohBackend()) return ServerHealthState.ONLINE
        val url = config.serverUrl.trim()
        if (!isAppServerUrl(url)) return ServerHealthState.OFFLINE
        return appServer.probe(url, config.accessToken).toServerHealth()
    }

    private fun isAppServerUrl(url: String): Boolean = try {
        AppServerDiscovery.httpOrigin(url)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
}

/** Whether the probed server answered at all. */
fun AppServerProbeResult.toServerHealth(): ServerHealthState =
    if (this is AppServerProbeResult.Unavailable) ServerHealthState.OFFLINE else ServerHealthState.ONLINE
