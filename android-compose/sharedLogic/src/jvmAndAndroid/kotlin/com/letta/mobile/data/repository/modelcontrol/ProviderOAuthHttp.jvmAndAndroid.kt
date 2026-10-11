package com.letta.mobile.data.repository.modelcontrol

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

/**
 * One process-wide client for provider sign-ins: no default headers, no
 * logging plugin, no Letta credentials. Created on first use.
 */
private val signInHttp: ProviderOAuthHttp by lazy {
    KtorProviderOAuthHttp(
        HttpClient(CIO) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = SIGN_IN_REQUEST_TIMEOUT_MS
                connectTimeoutMillis = SIGN_IN_REQUEST_TIMEOUT_MS
            }
        },
    )
}

private const val SIGN_IN_REQUEST_TIMEOUT_MS = 30_000L

actual fun defaultProviderOAuthHttp(): ProviderOAuthHttp? = signInHttp
