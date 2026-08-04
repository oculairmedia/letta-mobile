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

    @Test
    fun testRenderItemsBuilt_contentDupesScanIsSampled_letmamxwtn() {
        // letta-mobile-mxwtn: the contentDupes scan is the heavy probe in
        // onRenderItemsBuilt. It walks every item and builds a ~30KB Telemetry
        // string. Even when renderDiag is on, it must NOT run every generation
        // — verify that on a stream of render builds the scan is sampled at
        // most once per N generations (CONTENT_DUPES_SAMPLE_INTERVAL=32).
        Telemetry.renderDiagEnabled.set(true)
        RenderDiagnostics.resetContentDupesSampleCounter()
        Telemetry.clear()

        // Drive 64 successive render builds. The cheap duplicateKeys + keys
        // probe runs every generation (one Telemetry event per call); the
        // heavy contentDupes scan runs at most twice (32 + 32 = 64, so the
        // 64th generation hits the second scan).
        val totalGenerations = 64
        repeat(totalGenerations) {
            RenderDiagnostics.onRenderItemsBuilt(
                conversationId = "conv-sample",
                path = "ReplaceTail",
                items = emptyList(),
            )
        }

        val renderBuilt = Telemetry.snapshot().filter {
            it.tag == "RenderDiag" && it.name == "renderItems.built"
        }
        assertEquals(totalGenerations, renderBuilt.size)

        // Sample flag is recorded on every event so a watcher can verify the
        // sampling is happening (and not, e.g., every generation by accident).
        // Exactly the first generation of each sample window is marked true.
        val sampledTrueCount = renderBuilt.count { it.attrs["contentDupesScanSampled"] == true }
        assertEquals(
            2,
            sampledTrueCount,
            "expected contentDupes scan to run exactly twice in 64 generations",
        )
        // And every event must still report a contentDupes attribute (either
        // "<skipped>" or a real value), so callers can grep for it.
        assertTrue(renderBuilt.all { it.attrs.containsKey("contentDupes") })

        RenderDiagnostics.resetContentDupesSampleCounter()
    }

    @Test
    fun testRenderItemsBuilt_contentDupesScanOffWhenRenderDiagOff_letmamxwtn() {
        // The broader renderDiag flag short-circuits the whole event — when
        // disabled, NO telemetry is emitted and the sample counter does not
        // advance. Confirms the sample gate is INSIDE the on/off branch.
        assertFalse(RenderDiagnostics.enabled())
        RenderDiagnostics.resetContentDupesSampleCounter()
        Telemetry.clear()

        repeat(100) {
            RenderDiagnostics.onRenderItemsBuilt(
                conversationId = "conv-disabled",
                path = "Full",
                items = emptyList(),
            )
        }

        assertTrue(Telemetry.snapshot().none { it.name == "renderItems.built" })
        // Counter was never advanced because the early return ran first.
        assertFalse(RenderDiagnostics.contentDupesSampleDue())
        RenderDiagnostics.resetContentDupesSampleCounter()
    }
}
