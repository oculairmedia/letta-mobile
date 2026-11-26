package com.letta.mobile.data.controller.channels

import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The enumeration against an App Server that never answers `channels_list`, through the real
 * request registry and the wrapper's production request timeout.
 */
class ChannelRestoreUnansweredEnumerationTest {
    private var telemetry: AutoCloseable? = null
    private val recorder = TelemetryRecorder()

    @BeforeTest
    fun installTelemetry() {
        telemetry = Telemetry.installRecorder(recorder)
    }

    @AfterTest
    fun detachTelemetry() {
        telemetry?.close()
    }

    private fun TestScope.coordinatorOver(server: ListenModeAppServer) = ChannelRestoreCoordinator(
        client = DefaultAppServerClient(
            server.transport,
            requestTimeoutMs = ListenModeAppServer.WRAPPER_REQUEST_TIMEOUT_MS,
            parentScope = backgroundScope,
        ),
        sleep = {},
    )

    @Test
    fun anUnansweredChannelsListFailsWellBeforeTheRequestTimeoutWithANamedReason() = runTest {
        val server = ListenModeAppServer()

        val result = coordinatorOver(server).restore()

        assertTrue(
            currentTime <= 15_000,
            "an unanswered channels_list must give up within 15 s, not the ${currentTime}ms it took",
        )
        val failure = result.failures.single()
        assertEquals(ChannelRestorePhase.LIST_CHANNELS, failure.phase)
        assertTrue(
            "unanswered" in failure.reason && "not routing channel commands" in failure.reason,
            "the failure must name why, not report a bare request timeout: ${failure.reason}",
        )
        val warned = recorder.snapshot().single { it.tag == "ChannelRestore" && it.name == "list_channels_failed" }
        assertEquals(failure.reason, warned.attrs["reason"]?.toString())
    }

    /** Pins the cause: the wrapper's command is exactly what the server's predicate accepts. */
    @Test
    fun theWrapperSendsAWellFormedChannelsListThatTheServerDropsUnanswered() = runTest {
        val server = ListenModeAppServer()

        coordinatorOver(server).restore()

        val sent = server.droppedChannelCommands.single()
        assertEquals(setOf("type", "request_id"), sent.keys)
        assertEquals("channels_list", (sent["type"] as JsonPrimitive).content)
        assertTrue((sent["request_id"] as JsonPrimitive).isString)
    }

    /** The bound costs nothing where the channel service exists: the listing still answers. */
    @Test
    fun aServerWithAChannelServiceStillAnswersTheEnumeration() = runTest {
        val server = ListenModeAppServer(channelServiceRunning = true, channels = listOf("matrix"))

        val result = coordinatorOver(server).restore()

        assertEquals(listOf("matrix"), result.channelIds)
        assertTrue(result.failures.none { it.phase == ChannelRestorePhase.LIST_CHANNELS })
    }
}
