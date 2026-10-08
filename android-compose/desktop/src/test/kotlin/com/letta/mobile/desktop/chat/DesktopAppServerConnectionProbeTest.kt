package com.letta.mobile.desktop.chat

import com.letta.mobile.data.transport.appserver.AppServerIdentity
import com.letta.mobile.data.transport.appserver.AppServerProbeResult
import com.letta.mobile.desktop.DesktopSuspendResponder
import com.letta.mobile.desktop.desktopConnectionTestMessage
import com.letta.mobile.data.controller.reconnect.SuspendEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** letta-mobile-bzvro.1 (F01), .2 (F02) and .4 (F04) desktop bindings. */
class DesktopAppServerConnectionProbeTest {
    private val ok = AppServerProbeResult.Ok(AppServerIdentity("local", "0.33.6", 1))

    @Test
    fun preflightStopsTheDialOnAuthenticationAndIncompatible() = runTest {
        val auth = assertFailsWith<DesktopAppServerUnusableException> {
            preflightDesktopAppServer("ws://h:1", "bad", DesktopAppServerProbe { _, _ -> AppServerProbeResult.Authentication("HTTP 401") })
        }
        assertTrue(auth.message.orEmpty().startsWith("Authentication failed:"))
        assertEquals("The server rejected the access token", failureHeadline(com.letta.mobile.data.chat.runtime.ChatScreenStatus.BackendOffline(auth.message), auth.message))

        assertFailsWith<DesktopAppServerUnusableException> {
            preflightDesktopAppServer("ws://h:1", null, DesktopAppServerProbe { _, _ -> AppServerProbeResult.Incompatible("HTTP 404") })
        }
    }

    @Test
    fun preflightLetsTransientFailuresAndUnprobeableUrlsDial() = runTest {
        var probed = 0
        fun probe(result: AppServerProbeResult?) = DesktopAppServerProbe { _, _ ->
            probed++
            result
        }
        preflightDesktopAppServer("ws://h:1", null, probe(AppServerProbeResult.Unavailable("refused")))
        preflightDesktopAppServer("iroh://ticket", null, probe(null))
        preflightDesktopAppServer("ws://h:1", null, probe(ok))
        // An App Server older than the HTTP discovery route: the socket handshake decides.
        preflightDesktopAppServer("ws://h:1", null, probe(AppServerProbeResult.Incompatible("HTTP 404", endpointMissing = true)))
        assertEquals(4, probed, "every URL was probed and none of the outcomes stopped the dial")
    }

    @Test
    fun connectionTestReportsIdentityOrReason() = runTest {
        var sentToken: String? = "unset"
        val probe = DesktopAppServerProbe { _, token ->
            sentToken = token
            ok
        }

        val finished = assertIs<DesktopConnectionTestState.Finished>(runDesktopConnectionTest(" ws://h:1 ", "  ", probe))
        assertEquals(null, sentToken, "a blank token is not sent")
        assertEquals(
            "Connected: letta-code 0.33.6 (local backend, protocol 1)" to false,
            desktopConnectionTestMessage(finished),
        )

        val failed = runDesktopConnectionTest("ws://h:1", "t", DesktopAppServerProbe { _, _ -> AppServerProbeResult.Unavailable("refused") })
        assertEquals("Server unavailable: refused" to true, desktopConnectionTestMessage(failed))

        assertIs<DesktopConnectionTestState.NotSupported>(runDesktopConnectionTest("", null, probe))
        assertIs<DesktopConnectionTestState.NotSupported>(runDesktopConnectionTest("iroh://x", null, DesktopAppServerProbe { _, _ -> null }))
        assertEquals(null, desktopConnectionTestMessage(DesktopConnectionTestState.Running))
    }

    @Test
    fun wakeRestartsTheRuntimeBeforeTheChatRedials() {
        val calls = mutableListOf<String>()
        val responder = DesktopSuspendResponder(
            onRuntimeSuspended = { calls += "runtime.suspend" },
            onRuntimeResumed = { calls += "runtime.resume" },
            onChatResumed = { calls += "chat.resume" },
        )

        responder.handle(SuspendEvent.Suspended)
        responder.handle(SuspendEvent.Resumed(60_000))

        assertEquals(listOf("runtime.suspend", "runtime.resume", "chat.resume"), calls)
    }
}
