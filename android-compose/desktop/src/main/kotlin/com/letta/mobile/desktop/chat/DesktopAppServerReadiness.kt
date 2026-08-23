package com.letta.mobile.desktop.chat

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerCompatibilityRequirement
import com.letta.mobile.data.transport.appserver.AppServerConnectionState
import com.letta.mobile.data.transport.appserver.AppServerInfoData
import com.letta.mobile.data.transport.appserver.requireCompatibleWith
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

internal data class DesktopAppServerReadinessExpectation(
    val expectedBackend: String? = null,
    val requiredCapabilities: Set<String> = emptySet(),
)

/**
 * Makes one App Server connection generation prove protocol compatibility
 * before Desktop publishes a gateway backed by it.
 */
internal suspend fun awaitDesktopAppServerReadiness(
    connectionState: StateFlow<AppServerConnectionState>,
    client: AppServerClient,
    expectation: DesktopAppServerReadinessExpectation,
    timeoutMs: Long = DESKTOP_APP_SERVER_READINESS_TIMEOUT_MS,
    requestIdFactory: () -> String = { "desktop-info-${UUID.randomUUID()}" },
): AppServerInfoData = withTimeoutOrNull(timeoutMs.milliseconds) {
    when (val settled = connectionState.first { it.isReady || it is AppServerConnectionState.Failed }) {
        AppServerConnectionState.Ready -> Unit
        is AppServerConnectionState.Failed -> error(
            "Desktop App Server connection failed before readiness: " +
                settled.reason.orEmpty().ifBlank { "unknown transport failure" },
        )
        else -> error("Desktop App Server reached an invalid readiness state: $settled")
    }

    val response = client.appServerInfo(AppServerCommand.AppServerInfo(requestIdFactory()))
    if (!response.success) {
        error("Desktop App Server handshake failed: ${response.error ?: "unknown protocol failure"}")
    }
    val info = response.info
        ?.requireCompatibleWith(expectation.toCompatibilityRequirement())
        ?: error("Desktop App Server handshake returned no server information")
    check(connectionState.value.isReady) {
        "Desktop App Server connection was lost during protocol handshake"
    }
    info
} ?: error("Desktop App Server readiness timed out after ${timeoutMs}ms")

private fun DesktopAppServerReadinessExpectation.toCompatibilityRequirement() =
    AppServerCompatibilityRequirement(
        protocolVersion = SUPPORTED_APP_SERVER_PROTOCOL_VERSION,
        expectedBackend = expectedBackend,
        requiredCapabilities = requiredCapabilities + "runtime_start",
        requiredDisabledCapabilities = setOf("split_channels"),
    )

internal val localDesktopAppServerExpectation = DesktopAppServerReadinessExpectation(
    expectedBackend = "local",
    requiredCapabilities = setOf(
        "agent_management",
        "conversation_management",
        "memory_management",
    ),
)

private const val SUPPORTED_APP_SERVER_PROTOCOL_VERSION = 1
private const val DESKTOP_APP_SERVER_READINESS_TIMEOUT_MS = 15_000L
