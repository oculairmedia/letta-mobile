package com.letta.mobile.data.transport.iroh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.runtime.iroh.IrohAndroidInit
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Device-side live Iroh receive probe.
 *
 * This runs inside the Android instrumentation process, not on the host JVM. It
 * dials a real Iroh wrapper ticket, sends one turn through [IrohChannelTransport],
 * and asserts the Android process receives live AssistantMessage + TurnDone
 * frames. It is the red/green test for the bug where the wrapper writes
 * stream_delta frames but the dev APK never logs `IrohTransport.frame.recv`.
 *
 * Run against the live wrapper:
 *
 * ```
 * TICKET=$(grep 'Ticket:' /tmp/iroh-wrapper-live.log | sed 's/.*Ticket: //' | tail -1)
 * JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/opt/android-sdk \
 *   ./gradlew :app:connectedRootDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.letta.mobile.data.transport.iroh.IrohLiveReceiveDeviceTest \
 *   -Pandroid.testInstrumentationRunnerArguments.irohTicket="$TICKET" \
 *   -Pandroid.testInstrumentationRunnerArguments.agentId=agent-ca46df7f-c16a-4599-8e2d-3dc145c3e433 \
 *   -Pandroid.testInstrumentationRunnerArguments.conversationId=conv-8d4b6225-a2f6-47a7-8f73-664d56143bbd
 * ```
 */
class IrohLiveReceiveDeviceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun liveIrohTurnEmitsAssistantAndTurnDoneFrames() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val ticket = args.getString("irohTicket").orEmpty()
        assumeTrue("Pass -Pandroid.testInstrumentationRunnerArguments.irohTicket=<ticket>", ticket.isNotBlank())

        val agentId = args.getString("agentId") ?: DEFAULT_AGENT_ID
        val conversationId = args.getString("conversationId") ?: DEFAULT_CONVERSATION_ID
        val message = args.getString("message") ?: "device live iroh receive probe ${UUID.randomUUID()}"
        val timeoutMs = args.getString("timeoutMs")?.toLongOrNull() ?: DEFAULT_TIMEOUT_MS

        val transport = IrohChannelTransport(
            scope = scope,
            onConnect = { IrohAndroidInit.install(context) },
            forcedIrohUrl = "iroh://$ticket",
        )
        val frames = mutableListOf<ServerFrame>()
        val collector = scope.launch {
            transport.events.collect { frame -> frames += frame }
        }

        try {
            transport.connect(
                baseShimUrl = "iroh://$ticket",
                token = "",
                deviceId = "instrumentation-device",
                clientVersion = "iroh-live-receive-test",
            )

            val accepted = transport.send(
                agentId = agentId,
                conversationId = conversationId,
                text = message,
                otid = "cm-instrumentation-${UUID.randomUUID()}",
                contentParts = null,
                startNewConversation = false,
            )
            assertTrue("IrohChannelTransport.send should accept the turn", accepted)

            withTimeout(timeoutMs) {
                while (true) {
                    val gotAssistant = frames.any { it is ServerFrame.AssistantMessage }
                    val gotDone = frames.any { it is ServerFrame.TurnDone }
                    if (gotAssistant && gotDone) break
                    delay(100)
                }
            }
        } finally {
            collector.cancel()
            runCatching { transport.disconnect() }
        }

        assertTrue(
            "Expected live AssistantMessage over Iroh. Frames=${frames.map { it::class.simpleName }}",
            frames.any { it is ServerFrame.AssistantMessage },
        )
        assertTrue(
            "Expected live TurnDone over Iroh. Frames=${frames.map { it::class.simpleName }}",
            frames.any { it is ServerFrame.TurnDone },
        )
    }

    private companion object {
        const val DEFAULT_AGENT_ID = "agent-ca46df7f-c16a-4599-8e2d-3dc145c3e433"
        const val DEFAULT_CONVERSATION_ID = "conv-8d4b6225-a2f6-47a7-8f73-664d56143bbd"
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }
}
