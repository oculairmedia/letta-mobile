package com.letta.mobile.desktop.data

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.http.LettaHttpAdminRepositories
import com.letta.mobile.desktop.chat.createDesktopLettaHttpClient
import io.ktor.client.HttpClient

/**
 * Desktop binding for the shared [LettaHttpAdminRepositories]. The platform-
 * neutral caching/TTL/error-flow/request logic lives in commonMain; the desktop
 * module supplies only the JVM Ktor engine and the system clock
 * (letta-mobile-mqzkc).
 */
internal class DesktopLettaHttpAdminRepositories(
    config: LettaConfig,
    httpClient: HttpClient = createDesktopLettaHttpClient(),
    nowMillis: () -> Long = { System.currentTimeMillis() },
) : LettaHttpAdminRepositories(
    config = config,
    httpClient = httpClient,
    nowMillis = nowMillis,
)
