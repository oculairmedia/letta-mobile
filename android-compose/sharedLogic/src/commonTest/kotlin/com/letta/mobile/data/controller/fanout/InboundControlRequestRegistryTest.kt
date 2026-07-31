package com.letta.mobile.data.controller.fanout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InboundControlRequestRegistryTest {
    @Test
    fun registerAcceptsFirstRequestIdAndDeduplicates() {
        val registry = InboundControlRequestRegistry()
        val first = registry.register(
            InboundControlRequestRegistry.RegisterRequest(
                requestId = "ext-1",
                kind = InboundControlRequestRegistry.Kind.ExternalTool,
                connectionGeneration = 1L,
                toolCallId = "tc-1",
            ),
        )
        assertIs<InboundControlRequestRegistry.RegisterResult.Accepted>(first)

        val second = registry.register(
            InboundControlRequestRegistry.RegisterRequest(
                requestId = "ext-1",
                kind = InboundControlRequestRegistry.Kind.ExternalTool,
                connectionGeneration = 1L,
                toolCallId = "tc-1",
            ),
        )
        assertIs<InboundControlRequestRegistry.RegisterResult.Duplicate>(second)
        assertEquals(1, registry.pendingCount())
    }

    @Test
    fun tryClaimIsExclusivePerRequestId() {
        val registry = InboundControlRequestRegistry()
        registry.register(
            InboundControlRequestRegistry.RegisterRequest(
                requestId = "ext-1",
                kind = InboundControlRequestRegistry.Kind.ExternalTool,
                connectionGeneration = 2L,
            ),
        )
        assertTrue(registry.tryClaim(controlRef("ext-1"), leaseToken = 10L, connectionGeneration = 2L))
        assertFalse(registry.tryClaim(controlRef("ext-1"), leaseToken = 11L, connectionGeneration = 2L))
        // Owning lease also cannot claim twice — delivery is once-only.
        assertFalse(registry.tryClaim(controlRef("ext-1"), leaseToken = 10L, connectionGeneration = 2L))
        assertTrue(registry.ownsClaim(controlRef("ext-1"), leaseToken = 10L, connectionGeneration = 2L))
    }

    @Test
    fun failGenerationRemovesEntriesSoSameRequestIdCanReregister() {
        val registry = InboundControlRequestRegistry()
        registry.register(
            InboundControlRequestRegistry.RegisterRequest(
                requestId = "perm-1",
                kind = InboundControlRequestRegistry.Kind.Approval,
                connectionGeneration = 3L,
            ),
        )
        registry.failGeneration(3L)
        assertEquals(0, registry.pendingCount())
        assertFalse(registry.tryClaim(controlRef("perm-1"), leaseToken = 1L, connectionGeneration = 3L))
        assertIs<InboundControlRequestRegistry.RegisterResult.GenerationFailed>(
            registry.register(
                InboundControlRequestRegistry.RegisterRequest(
                    requestId = "perm-2",
                    kind = InboundControlRequestRegistry.Kind.Approval,
                    connectionGeneration = 3L,
                ),
            ),
        )
        // Same request_id on the successor generation must be accepted.
        assertIs<InboundControlRequestRegistry.RegisterResult.Accepted>(
            registry.register(
                InboundControlRequestRegistry.RegisterRequest(
                    requestId = "perm-1",
                    kind = InboundControlRequestRegistry.Kind.Approval,
                    connectionGeneration = 4L,
                ),
            ),
        )
    }

    @Test
    fun releaseClaimAllowsRetryAfterFailedSend() {
        val registry = InboundControlRequestRegistry()
        registry.register(
            InboundControlRequestRegistry.RegisterRequest(
                requestId = "ext-1",
                kind = InboundControlRequestRegistry.Kind.ExternalTool,
                connectionGeneration = 1L,
            ),
        )
        assertTrue(registry.tryClaim(controlRef("ext-1"), leaseToken = 7L, connectionGeneration = 1L))
        registry.releaseClaim(controlRef("ext-1"), leaseToken = 7L, connectionGeneration = 1L)
        assertTrue(registry.isDeliverableTo(controlRef("ext-1"), leaseToken = 7L, connectionGeneration = 1L))
        assertTrue(registry.tryClaim(controlRef("ext-1"), leaseToken = 7L, connectionGeneration = 1L))
        registry.markAnswered(controlRef("ext-1"), connectionGeneration = 1L)
        assertFalse(registry.isDeliverableTo(controlRef("ext-1"), leaseToken = 7L, connectionGeneration = 1L))
        assertFalse(registry.tryClaim(controlRef("ext-1"), leaseToken = 7L, connectionGeneration = 1L))
    }

    @Test
    fun dispatchedRemainsDeliverableAndClaimableUntilAnswered() {
        val registry = InboundControlRequestRegistry()
        registry.register(
            InboundControlRequestRegistry.RegisterRequest(
                requestId = "ext-1",
                kind = InboundControlRequestRegistry.Kind.ExternalTool,
                connectionGeneration = 5L,
            ),
        )
        registry.markDispatched(controlRef("ext-1"), connectionGeneration = 5L)
        assertTrue(registry.isDeliverableTo(controlRef("ext-1"), leaseToken = 1L, connectionGeneration = 5L))
        assertTrue(registry.tryClaim(controlRef("ext-1"), leaseToken = 1L, connectionGeneration = 5L))
        assertFalse(registry.isDeliverableTo(controlRef("ext-1"), leaseToken = 1L, connectionGeneration = 5L))
    }

    /**
     * letta-mobile-lgns8.22.4.1.3 — App Server v2 declares
     * `external_tool_call_request` idempotency as `request_id_and_tool_call_id`.
     * Keying on request_id alone classified a reused request_id carrying a NEW
     * tool_call_id as a Duplicate, so that call was never executed nor answered
     * and the server turn blocked on it forever.
     */
    @Test
    fun reusedRequestIdWithDistinctToolCallIdIsANewExternalToolEntry() {
        val registry = InboundControlRequestRegistry()
        assertIs<InboundControlRequestRegistry.RegisterResult.Accepted>(
            registry.register(
                InboundControlRequestRegistry.RegisterRequest(
                    requestId = "ext-shared",
                    kind = InboundControlRequestRegistry.Kind.ExternalTool,
                    connectionGeneration = 1L,
                    toolCallId = "tc-a",
                ),
            ),
        )
        assertIs<InboundControlRequestRegistry.RegisterResult.Accepted>(
            registry.register(
                InboundControlRequestRegistry.RegisterRequest(
                    requestId = "ext-shared",
                    kind = InboundControlRequestRegistry.Kind.ExternalTool,
                    connectionGeneration = 1L,
                    toolCallId = "tc-b",
                ),
            ),
        )
        assertEquals(2, registry.pendingCount())

        // Each identity is independently claimable and answerable.
        assertTrue(registry.tryClaim(controlRef("ext-shared", "tc-a"), leaseToken = 1L, connectionGeneration = 1L))
        assertTrue(registry.tryClaim(controlRef("ext-shared", "tc-b"), leaseToken = 1L, connectionGeneration = 1L))
        registry.markAnswered(controlRef("ext-shared", "tc-a"), connectionGeneration = 1L)
        assertEquals(
            InboundControlRequestRegistry.State.Claimed,
            registry.lookup(controlRef("ext-shared", "tc-b"), 1L)?.state,
            "answering one tool_call_id must not retire the other",
        )
    }

    @Test
    fun approvalEntriesRemainRequestIdKeyed() {
        val registry = InboundControlRequestRegistry()
        registry.register(
            InboundControlRequestRegistry.RegisterRequest(
                requestId = "perm-1",
                kind = InboundControlRequestRegistry.Kind.Approval,
                connectionGeneration = 1L,
            ),
        )
        assertIs<InboundControlRequestRegistry.RegisterResult.Duplicate>(
            registry.register(
                InboundControlRequestRegistry.RegisterRequest(
                    requestId = "perm-1",
                    kind = InboundControlRequestRegistry.Kind.Approval,
                    connectionGeneration = 1L,
                ),
            ),
        )
        assertTrue(registry.tryClaim(controlRef("perm-1"), leaseToken = 1L, connectionGeneration = 1L))
    }

    /**
     * letta-mobile-lgns8.22.4.1.5 — answered identities used to stay in the live
     * entry map forever on a stable connection, so a long tool-heavy session grew
     * it without bound and failGeneration walked every historical key.
     */
    @Test
    fun answeredHistoryIsBoundedAndLiveEntriesDoNotAccumulate() {
        val registry = InboundControlRequestRegistry()
        val total = InboundControlRequestRegistry.MAX_COMPLETED_HISTORY * 2
        repeat(total) { index ->
            registry.register(
                InboundControlRequestRegistry.RegisterRequest(
                    requestId = "ext-$index",
                    kind = InboundControlRequestRegistry.Kind.ExternalTool,
                    connectionGeneration = 1L,
                    toolCallId = "tc-$index",
                ),
            )
            registry.markAnswered(controlRef("ext-$index", "tc-$index"), connectionGeneration = 1L)
        }

        assertEquals(0, registry.liveEntryCount(), "answered work must leave the live map")
        assertEquals(0, registry.pendingCount())
        assertEquals(
            InboundControlRequestRegistry.MAX_COMPLETED_HISTORY,
            registry.completedHistoryCount(),
            "answered history is a bounded dedup window",
        )
        // The most recent answer is still deduped; the evicted oldest is not.
        assertEquals(
            InboundControlRequestRegistry.State.Answered,
            registry.lookup(controlRef("ext-${total - 1}", "tc-${total - 1}"), 1L)?.state,
        )
        assertEquals(null, registry.lookup(controlRef("ext-0", "tc-0"), 1L))
    }

    @Test
    fun answeredRequestIsNotRedeliveredWhileInsideTheDedupWindow() {
        val registry = InboundControlRequestRegistry()
        registry.register(
            InboundControlRequestRegistry.RegisterRequest(
                requestId = "ext-1",
                kind = InboundControlRequestRegistry.Kind.ExternalTool,
                connectionGeneration = 1L,
                toolCallId = "tc-1",
            ),
        )
        registry.markAnswered(controlRef("ext-1", "tc-1"), connectionGeneration = 1L)

        val replay = registry.register(
            InboundControlRequestRegistry.RegisterRequest(
                requestId = "ext-1",
                kind = InboundControlRequestRegistry.Kind.ExternalTool,
                connectionGeneration = 1L,
                toolCallId = "tc-1",
            ),
        )
        val duplicate = assertIs<InboundControlRequestRegistry.RegisterResult.Duplicate>(replay)
        assertEquals(InboundControlRequestRegistry.State.Answered, duplicate.entry.state)
        assertFalse(registry.tryClaim(controlRef("ext-1", "tc-1"), leaseToken = 1L, connectionGeneration = 1L))
    }

    @Test
    fun failGenerationAlsoClearsTheAnsweredDedupWindow() {
        val registry = InboundControlRequestRegistry()
        registry.register(
            InboundControlRequestRegistry.RegisterRequest(
                requestId = "ext-1",
                kind = InboundControlRequestRegistry.Kind.ExternalTool,
                connectionGeneration = 1L,
                toolCallId = "tc-1",
            ),
        )
        registry.markAnswered(controlRef("ext-1", "tc-1"), connectionGeneration = 1L)
        registry.failGeneration(1L)
        assertEquals(0, registry.completedHistoryCount())
        assertIs<InboundControlRequestRegistry.RegisterResult.Accepted>(
            registry.register(
                InboundControlRequestRegistry.RegisterRequest(
                    requestId = "ext-1",
                    kind = InboundControlRequestRegistry.Kind.ExternalTool,
                    connectionGeneration = 2L,
                    toolCallId = "tc-1",
                ),
            ),
        )
    }

    /**
     * letta-mobile-lgns8.22.4.1.4 — an answer sent on generation N must retire the
     * generation-N entry only. A recovery replay already registered under the
     * successor generation stays deliverable, because the server may never have
     * received the generation-N decision.
     */
    @Test
    fun markAnsweredOnTheClaimGenerationLeavesTheSuccessorReplayDeliverable() {
        val registry = InboundControlRequestRegistry()
        registry.register(
            InboundControlRequestRegistry.RegisterRequest(
                requestId = "perm-1",
                kind = InboundControlRequestRegistry.Kind.Approval,
                connectionGeneration = 1L,
            ),
        )
        // Reconnect: the server replays the still-pending approval on generation 2.
        registry.register(
            InboundControlRequestRegistry.RegisterRequest(
                requestId = "perm-1",
                kind = InboundControlRequestRegistry.Kind.Approval,
                connectionGeneration = 2L,
            ),
        )

        // The in-flight send from the dead connection completes and marks answered
        // against the generation it was CLAIMED on.
        registry.markAnswered(controlRef("perm-1"), connectionGeneration = 1L)

        assertTrue(
            registry.isDeliverableTo(controlRef("perm-1"), leaseToken = 9L, connectionGeneration = 2L),
            "the successor generation's replay must survive an old-generation answer",
        )
        assertTrue(registry.tryClaim(controlRef("perm-1"), leaseToken = 9L, connectionGeneration = 2L))
    }
}

/** Shorthand for the (request_id, tool_call_id) identity (lgns8.22.4.1.3). */
private fun controlRef(requestId: String, toolCallId: String? = null) =
    InboundControlRequestRegistry.RequestRef(requestId, toolCallId)
