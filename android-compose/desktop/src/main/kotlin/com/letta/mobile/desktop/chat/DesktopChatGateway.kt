package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.http.LettaHttpChatGateway
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import dev.nucleusframework.nativessl.NativeTrustManager

typealias DesktopChatGateway = ChatGateway

/**
 * Desktop binding for the shared [LettaHttpChatGateway]. The platform-neutral
 * conversations/messages/streaming HTTP logic lives in commonMain; the desktop
 * module supplies only the JVM Ktor CIO engine (letta-mobile-mqzkc).
 */
class DesktopLettaHttpChatGateway(
    config: LettaConfig,
    httpClient: HttpClient = createDesktopLettaHttpClient(),
) : LettaHttpChatGateway(config = config, httpClient = httpClient)

fun createDesktopLettaHttpClient(): HttpClient = HttpClient(CIO) {
    engine {
        https {
            trustManager = NativeTrustManager.trustManager
        }
    }
    install(ContentNegotiation) {
        json(desktopChatJson)
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 60_000
        socketTimeoutMillis = 60_000
    }
}

internal val desktopChatJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
    // The Letta API returns explicit nulls for several non-nullable fields that
    // have defaults (e.g. agent.metadata). Coerce null -> default so agent /
    // conversation deserialization doesn't fail and agent names hydrate.
    coerceInputValues = true
}
