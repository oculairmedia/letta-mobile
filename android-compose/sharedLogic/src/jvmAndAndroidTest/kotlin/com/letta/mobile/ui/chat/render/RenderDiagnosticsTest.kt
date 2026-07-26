package com.letta.mobile.ui.chat.render

import com.letta.mobile.util.Telemetry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderDiagnosticsTest {

    @BeforeTest
    fun setUp() {
        Telemetry.clear()
        Telemetry.renderDiagEnabled.set(false)
    }

    @AfterTest
    fun tearDown() {
        Telemetry.clear()
        Telemetry.renderDiagEnabled.set(false)
    }

    @Test
    fun testDiagnosticsDisabledByDefaultAndIsNoOp() {
        assertFalse(RenderDiagnostics.enabled())
        val initialEvents = Telemetry.snapshot().size

        RenderDiagnostics.onProjectionCompleted(
            conversationId = "conv-1",
            durationNs = 1_000_000L,
            groupCount = 5,
            callCount = 10,
            changedCallCount = 1,
        )
        RenderDiagnostics.onCounts(
            conversationId = "conv-1",
            totalMessages = 100,
            totalGroups = 5,
            totalCalls = 10,
        )
        RenderDiagnostics.onToolCallChanged(
            conversationId = "conv-1",
            callKey = "call:123",
            previousState = "Running",
            newState = "Succeeded",
        )
        RenderDiagnostics.onToolStateTransition(
            conversationId = "conv-1",
            entityKey = "group:456",
            fromState = "Running",
            toState = "Succeeded",
        )
        RenderDiagnostics.onKeyCollision(
            conversationId = "conv-1",
            key = "call:123#1",
            dupIndex = 1,
        )
        RenderDiagnostics.onKeyFallback(
            conversationId = "conv-1",
            key = "call::fallback-0",
            fallbackType = "call_fallback",
            index = 0,
        )
        RenderDiagnostics.onLegacyFallback(
            conversationId = "conv-1",
            callKey = "call:img-1",
            fallbackReason = "image_card",
        )
        RenderDiagnostics.onVisibleGroups(
            conversationId = "conv-1",
            totalGroups = 5,
            visibleGroups = 3,
        )
        RenderDiagnostics.onToolRowRecomposed(
            conversationId = "conv-1",
            callKey = "call:123",
            state = "Succeeded",
            isExpanded = false,
        )

        val measuredResult = RenderDiagnostics.measureProjection("conv-1") {
            "test_output"
        }
        assertEquals("test_output", measuredResult)

        val eventsAfter = Telemetry.snapshot()
        assertEquals(initialEvents, eventsAfter.size)
    }

    @Test
    fun testDiagnosticsEmitsEventsWhenEnabled() {
        Telemetry.renderDiagEnabled.set(true)
        assertTrue(RenderDiagnostics.enabled())
        Telemetry.clear()

        RenderDiagnostics.onProjectionCompleted(
            conversationId = "conv-bench",
            durationNs = 2_500_000L,
            groupCount = 20,
            callCount = 25,
            changedCallCount = 2,
        )
        RenderDiagnostics.onCounts(
            conversationId = "conv-bench",
            totalMessages = 101,
            totalGroups = 20,
            totalCalls = 25,
        )
        RenderDiagnostics.onToolCallChanged(
            conversationId = "conv-bench",
            callKey = "call:active-1",
            previousState = "Running",
            newState = "Succeeded",
        )
        RenderDiagnostics.onToolStateTransition(
            conversationId = "conv-bench",
            entityKey = "group:active",
            fromState = "Running",
            toState = "Succeeded",
        )
        RenderDiagnostics.onKeyCollision(
            conversationId = "conv-bench",
            key = "call:dup#1",
            dupIndex = 1,
        )
        RenderDiagnostics.onKeyFallback(
            conversationId = "conv-bench",
            key = "call::fallback-0",
            fallbackType = "call_fallback",
            index = 0,
        )
        RenderDiagnostics.onLegacyFallback(
            conversationId = "conv-bench",
            callKey = "call:subagent-1",
            fallbackReason = "subagent_dispatch",
        )
        RenderDiagnostics.onVisibleGroups(
            conversationId = "conv-bench",
            totalGroups = 20,
            visibleGroups = 20,
        )
        RenderDiagnostics.onToolRowRecomposed(
            conversationId = "conv-bench",
            callKey = "call:active-1",
            state = "Succeeded",
            isExpanded = true,
        )

        val events = Telemetry.snapshot().filter { it.tag == "RenderDiag" }
        assertEquals(9, events.size)

        val names = events.map { it.name }
        assertTrue("toolProjection.completed" in names)
        assertTrue("toolTimeline.counts" in names)
        assertTrue("toolCall.changed" in names)
        assertTrue("toolState.transition" in names)
        assertTrue("key.collision" in names)
        assertTrue("key.fallback" in names)
        assertTrue("legacy.fallback" in names)
        assertTrue("visibleGroups.projected" in names)
        assertTrue("toolRow.recomposed" in names)
    }

    @Test
    fun testDiagnosticsNeverLogPayloads() {
        Telemetry.renderDiagEnabled.set(true)
        Telemetry.clear()

        val secretArgumentPayload = """{"secret_password":"super_secret_token_12345"}"""
        val secretResultPayload = "TOP_SECRET_DATABASE_EXPORT_DATA"
        val secretMessageContent = "Private confidential conversation text"

        RenderDiagnostics.onProjectionCompleted(
            conversationId = "conv-secure",
            durationNs = 1_000_000L,
            groupCount = 5,
            callCount = 10,
            changedCallCount = 0,
        )
        RenderDiagnostics.onToolCallChanged(
            conversationId = "conv-secure",
            callKey = "call:sec-1",
            previousState = "Running",
            newState = "Succeeded",
        )

        val events = Telemetry.snapshot()
        for (event in events) {
            for ((key, value) in event.attrs) {
                val valueStr = value.toString()
                assertFalse(valueStr.contains("secret_password"), "Telemetry attribute $key contains argument payload")
                assertFalse(valueStr.contains("super_secret"), "Telemetry attribute $key contains secret token")
                assertFalse(valueStr.contains("TOP_SECRET"), "Telemetry attribute $key contains result payload")
                assertFalse(valueStr.contains("confidential"), "Telemetry attribute $key contains message content payload")
            }
        }
    }
}
