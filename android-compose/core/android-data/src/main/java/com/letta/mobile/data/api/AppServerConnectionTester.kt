package com.letta.mobile.data.api

import com.letta.mobile.data.transport.appserver.AppServerProbe
import com.letta.mobile.data.transport.appserver.AppServerProbeResult
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Test connection" for a self-hosted App Server (letta-mobile-bzvro.1, F01): binds the shared
 * [AppServerProbe] to an OkHttp client. The probe is HTTP only and never opens `/ws`, so it
 * cannot disturb a live session.
 */
@Singleton
open class AppServerConnectionTester @Inject constructor() {
    private val probe: AppServerProbe by lazy {
        AppServerProbe(HttpClient(OkHttp) { expectSuccess = false })
    }

    /**
     * The classified probe result, or null when the URL is not one this probe can test (an Iroh
     * ticket, or a URL without a ws/wss/http/https scheme).
     */
    open suspend fun test(serverUrl: String, accessToken: String?): AppServerProbeResult? {
        val url = serverUrl.trim()
        if (IrohChannelTransport.isIrohUrl(url) || !url.isProbeableScheme()) return null
        return withContext(Dispatchers.IO) { probe.probe(url, accessToken) }
    }

    private fun String.isProbeableScheme(): Boolean =
        PROBEABLE_SCHEMES.any { startsWith(it, ignoreCase = true) }

    private companion object {
        val PROBEABLE_SCHEMES = listOf("ws://", "wss://", "http://", "https://")
    }
}
