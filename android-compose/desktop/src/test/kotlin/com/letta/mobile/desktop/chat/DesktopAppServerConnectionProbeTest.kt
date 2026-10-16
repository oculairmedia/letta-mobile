package com.letta.mobile.desktop.chat

import com.letta.mobile.data.transport.appserver.AppServerIdentity
import com.letta.mobile.data.transport.appserver.AppServerProbeResult
import com.letta.mobile.desktop.DesktopSuspendResponder
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.ui.shell.pages.home.connectionFailureHeadline
import com.letta.mobile.data.controller.reconnect.SuspendEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.letta.mobile.data.controller.reconnect.ReconnectingClientListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest

/** letta-mobile-bzvro.1 (F01), .2 (F02) and .4 (F04) desktop bindings. */
class DesktopAppServerConnectionProbeTest {
    private val ok = AppServerProbeResult.Ok(AppServerIdentity("local", "0.33.6", 1))

    @Test
    fun aFirstDialTheProbeRejectsFailsWithTheVerdictAndNeverDials() = runTest {
        // letta-mobile-by4vm.9: the shared reconnect owner runs the probe; the gateway surfaces
        // its verdict with the summary() prefix the Home banner keys on.
        val auth = assertFailsWith<DesktopAppServerUnusableException> {
            openDirect { AppServerProbeResult.Authentication("HTTP 401") }
        }
        assertTrue(auth.message.orEmpty().startsWith("Authentication failed:"))
        assertEquals("The server rejected the access token", connectionFailureHeadline(com.letta.mobile.data.chat.runtime.ChatScreenStatus.BackendOffline(auth.message), auth.message))

        val incompatible = assertFailsWith<DesktopAppServerUnusableException> {
            openDirect { AppServerProbeResult.Incompatible("HTTP 404") }
        }
        assertTrue(incompatible.message.orEmpty().startsWith("Incompatible server:"))

        val down = assertFailsWith<IllegalStateException> {
            openDirect { AppServerProbeResult.Unavailable("refused") }
        }
        assertEquals("probe: refused", down.message, "a down server is reported at once, not retried behind the user's back")
    }

    private suspend fun openDirect(probe: suspend () -> AppServerProbeResult) = coroutineScope {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            DesktopDirectAppServerSession.open(
                dial = DesktopDirectDial(
                    serverUrl = "ws://127.0.0.1:9",
                    bearerToken = null,
                    readiness = com.letta.mobile.data.transport.appserver.AppServerReadinessExpectation(),
                    preflight = probe,
                ),
                scope = scope,
                listener = object : ReconnectingClientListener {},
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun connectionTestReportsIdentityOrReason() = runTest {
        var sentToken: String? = "unset"
        val probe: suspend (String, String?) -> AppServerProbeResult? = { _, token ->
            sentToken = token
            ok
        }

        val finished = assertIs<DesktopConnectionTestState.Finished>(runDesktopConnectionTest(" ws://h:1 ", "  ", probe))
        assertEquals(null, sentToken, "a blank token is not sent")
        assertEquals(
            SettingsStatus("Connected: letta-code 0.33.6 (local backend, protocol 1)", SettingsTone.Positive),
            finished.toSettingsStatus(),
        )

        val failed = runDesktopConnectionTest("ws://h:1", "t", { _, _ -> AppServerProbeResult.Unavailable("refused") })
        assertEquals(SettingsStatus("Server unavailable: refused", SettingsTone.Error), failed.toSettingsStatus())

        assertIs<DesktopConnectionTestState.NotSupported>(runDesktopConnectionTest("", null, probe))
        val iroh = runDesktopConnectionTest("iroh://x", null, { _, _ -> null })
        assertEquals(SettingsTone.Normal, assertIs<DesktopConnectionTestState.NotSupported>(iroh).toSettingsStatus().tone)
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
