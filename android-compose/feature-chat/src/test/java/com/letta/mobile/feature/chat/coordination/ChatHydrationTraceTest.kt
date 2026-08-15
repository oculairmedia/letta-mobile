package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.util.Telemetry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatHydrationTraceTest {
    @Before
    fun enableTrace() {
        Telemetry.clear()
        Telemetry.chatHydrationTraceEnabled.set(true)
        ChatHydrationTrace.clearForTest()
    }

    @After
    fun disableTrace() {
        Telemetry.chatHydrationTraceEnabled.set(false)
        Telemetry.clear()
        ChatHydrationTrace.clearForTest()
    }

    @Test
    fun `stale generation event retains its own generation identity`() {
        val identity = ChatHydrationTrace.Identity(
            agentId = "agent-a",
            conversationId = "conversation-a",
            backendId = "remote-letta:a",
            runtimeId = "remote-letta:a",
        )
        val oldGeneration = ChatHydrationTrace.begin(identity)
        val currentGeneration = ChatHydrationTrace.begin(identity)

        ChatHydrationTrace.presentationPublished(
            oldGeneration,
            commitReason = "AppendTail",
            messageCount = 4,
            missingOptionalSources = "none",
        )

        val stale = Telemetry.snapshot().single { it.attrs["isStale"] == true }
        assertEquals("presentation_published", stale.name)
        assertEquals(oldGeneration.id, stale.attrs["generation"])
        assertTrue(oldGeneration.id != currentGeneration.id)
        assertEquals("agent-a", stale.attrs["agentId"])
        assertEquals("conversation-a", stale.attrs["conversationId"])
        assertEquals(1, stale.attrs["staleCount"])
    }

    @Test
    fun `initial source publication layout and scroll settle in order`() {
        val generation = ChatHydrationTrace.begin(
            ChatHydrationTrace.Identity("agent-fixture", "conv-fixture", "remote-letta:fixture", "remote-letta:fixture"),
        )

        ChatHydrationTrace.sourceReady(generation, source = "timeline", count = 42)
        ChatHydrationTrace.presentationPublished(generation, "Full", messageCount = 42, missingOptionalSources = "a2ui")
        ChatHydrationTrace.firstLayout(generation, renderItemCount = 42)
        ChatHydrationTrace.scrollInitialized(generation, correction = "conversation_reset")

        assertEquals(
            listOf("hydration.started", "source_ready", "presentation_published", "first_layout", "scroll_initialized", "settled"),
            Telemetry.snapshot().asReversed().map { it.name },
        )
    }

    @Test
    fun `trace records counts but never message or tool payload`() {
        val generation = ChatHydrationTrace.begin(
            ChatHydrationTrace.Identity("agent-a", "conversation-a", "remote-letta:a", "runtime-a"),
        )
        val body = "secret message body with tool args {token: do-not-log}"

        ChatHydrationTrace.sourceReady(generation, source = body, count = 2)
        ChatHydrationTrace.presentationPublished(
            generation = generation,
            commitReason = body,
            messageCount = 2,
            missingOptionalSources = body,
        )
        ChatHydrationTrace.activityChanged(generation, active = false, reason = body)

        val traceText = Telemetry.snapshot().joinToString("\n") { event ->
            "${event.name} ${event.attrs}"
        }
        assertFalse(traceText.contains(body))
        assertFalse(traceText.contains("do-not-log"))
        assertTrue(Telemetry.snapshot().all { event ->
            event.attrs.keys.none { key -> key.contains("body", ignoreCase = true) || key.contains("payload", ignoreCase = true) }
        })
    }
}
