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
            registerRequest("ext-1", InboundControlRequestRegistry.Kind.ExternalTool, 1L, "tc-1"),
        )
        assertIs<InboundControlRequestRegistry.RegisterResult.Accepted>(first)

        val second = registry.register(
            registerRequest("ext-1", InboundControlRequestRegistry.Kind.ExternalTool, 1L, "tc-1"),
        )
        assertIs<InboundControlRequestRegistry.RegisterResult.Duplicate>(second)
        assertEquals(1, registry.pendingCount())
    }

    @Test
    fun tryClaimIsExclusivePerRequestId() {
        val registry = InboundControlRequestRegistry()
        registry.register(
            registerRequest("ext-1", InboundControlRequestRegistry.Kind.ExternalTool, 2L),
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
            registerRequest("perm-1", InboundControlRequestRegistry.Kind.Approval, 3L),
        )
        registry.failGeneration(3L)
        assertEquals(0, registry.pendingCount())
        assertFalse(registry.tryClaim(controlRef("perm-1"), leaseToken = 1L, connectionGeneration = 3L))
        assertIs<InboundControlRequestRegistry.RegisterResult.GenerationFailed>(
            registry.register(
                registerRequest("perm-2", InboundControlRequestRegistry.Kind.Approval, 3L),
            ),
        )
        // Same request_id on the successor generation must be accepted.
        assertIs<InboundControlRequestRegistry.RegisterResult.Accepted>(
            registry.register(
                registerRequest("perm-1", InboundControlRequestRegistry.Kind.Approval, 4L),
            ),
        )
    }

    @Test
    fun releaseClaimAllowsRetryAfterFailedSend() {
        val registry = InboundControlRequestRegistry()
        registry.register(
            registerRequest("ext-1", InboundControlRequestRegistry.Kind.ExternalTool, 1L),
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
            registerRequest("ext-1", InboundControlRequestRegistry.Kind.ExternalTool, 5L),
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
                registerRequest("ext-shared", InboundControlRequestRegistry.Kind.ExternalTool, 1L, "tc-a"),
            ),
        )
        assertIs<InboundControlRequestRegistry.RegisterResult.Accepted>(
            registry.register(
                registerRequest("ext-shared", InboundControlRequestRegistry.Kind.ExternalTool, 1L, "tc-b"),
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
            registerRequest("perm-1", InboundControlRequestRegistry.Kind.Approval, 1L),
        )
        assertIs<InboundControlRequestRegistry.RegisterResult.Duplicate>(
            registry.register(
                registerRequest("perm-1", InboundControlRequestRegistry.Kind.Approval, 1L),
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
                registerRequest("ext-$index", InboundControlRequestRegistry.Kind.ExternalTool, 1L, "tc-$index"),
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
            registerRequest("ext-1", InboundControlRequestRegistry.Kind.ExternalTool, 1L, "tc-1"),
        )
        registry.markAnswered(controlRef("ext-1", "tc-1"), connectionGeneration = 1L)

        val replay = registry.register(
            registerRequest("ext-1", InboundControlRequestRegistry.Kind.ExternalTool, 1L, "tc-1"),
        )
        val duplicate = assertIs<InboundControlRequestRegistry.RegisterResult.Duplicate>(replay)
        assertEquals(InboundControlRequestRegistry.State.Answered, duplicate.entry.state)
        assertFalse(registry.tryClaim(controlRef("ext-1", "tc-1"), leaseToken = 1L, connectionGeneration = 1L))
    }

    @Test
    fun failGenerationAlsoClearsTheAnsweredDedupWindow() {
        val registry = InboundControlRequestRegistry()
        registry.register(
            registerRequest("ext-1", InboundControlRequestRegistry.Kind.ExternalTool, 1L, "tc-1"),
        )
        registry.markAnswered(controlRef("ext-1", "tc-1"), connectionGeneration = 1L)
        registry.failGeneration(1L)
        assertEquals(0, registry.completedHistoryCount())
        assertIs<InboundControlRequestRegistry.RegisterResult.Accepted>(
            registry.register(
                registerRequest("ext-1", InboundControlRequestRegistry.Kind.ExternalTool, 2L, "tc-1"),
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
            registerRequest("perm-1", InboundControlRequestRegistry.Kind.Approval, 1L),
        )
        // Reconnect: the server replays the still-pending approval on generation 2.
        registry.register(
            registerRequest("perm-1", InboundControlRequestRegistry.Kind.Approval, 2L),
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

    // ─────────────────────────────────────────────────────────────────────
    // Detached external-tool claims (PR #1077 review, P1)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * THE DOUBLE-EXECUTION CLASS. An external tool is invoked on a DETACHED job
     * so a slow tool cannot stall frame ingestion for every runtime on the
     * connection. That job outlives its turn — but the turn's `finally` calls
     * [InboundControlRequestRegistry.releaseClaimsForLease], which used to flip
     * the still-running request back to Pending. A replay or successor lease
     * could then claim the same identity and run a NON-IDEMPOTENT tool twice.
     *
     * FAIL-ON-REVERT: drop the `!detached` guard in `shouldReleaseFor` and this
     * test goes red — the entry becomes claimable while the tool is in flight.
     */
    @Test
    fun detachedExternalToolClaimSurvivesItsLeaseRelease() {
        val registry = InboundControlRequestRegistry()
        registry.register(
            registerRequest("ext-1", InboundControlRequestRegistry.Kind.ExternalTool, 1L, "tc-1"),
        )
        assertTrue(registry.tryClaim(controlRef("ext-1", "tc-1"), leaseToken = 7L, connectionGeneration = 1L))
        assertTrue(registry.markDetached(controlRef("ext-1", "tc-1"), leaseToken = 7L, connectionGeneration = 1L))

        // The turn ends while the tool is still running.
        registry.releaseClaimsForLease(leaseToken = 7L, connectionGeneration = 1L)

        assertFalse(
            registry.isDeliverableTo(controlRef("ext-1", "tc-1"), leaseToken = 8L, connectionGeneration = 1L),
            "an in-flight detached invocation must not be redelivered",
        )
        assertFalse(
            registry.tryClaim(controlRef("ext-1", "tc-1"), leaseToken = 8L, connectionGeneration = 1L),
            "a successor lease must not claim a request whose tool is still running",
        )
        assertTrue(
            registry.ownsClaim(controlRef("ext-1", "tc-1"), leaseToken = 7L, connectionGeneration = 1L),
            "the detached invocation still owns its claim",
        )
    }

    /** A non-detached claim is still reopened by its lease exiting (unchanged). */
    @Test
    fun undetachedClaimIsStillReleasedWithItsLease() {
        val registry = InboundControlRequestRegistry()
        registry.register(
            registerRequest("ext-2", InboundControlRequestRegistry.Kind.ExternalTool, 1L, "tc-2"),
        )
        assertTrue(registry.tryClaim(controlRef("ext-2", "tc-2"), leaseToken = 7L, connectionGeneration = 1L))

        registry.releaseClaimsForLease(leaseToken = 7L, connectionGeneration = 1L)

        assertTrue(
            registry.tryClaim(controlRef("ext-2", "tc-2"), leaseToken = 8L, connectionGeneration = 1L),
            "a successor lease must still recover an abandoned claim",
        )
    }

    /** Releasing a detached claim (send failed) hands it back to a successor. */
    @Test
    fun releasingADetachedClaimClearsDetachment() {
        val registry = InboundControlRequestRegistry()
        registry.register(
            registerRequest("ext-3", InboundControlRequestRegistry.Kind.ExternalTool, 1L, "tc-3"),
        )
        registry.tryClaim(controlRef("ext-3", "tc-3"), leaseToken = 7L, connectionGeneration = 1L)
        registry.markDetached(controlRef("ext-3", "tc-3"), leaseToken = 7L, connectionGeneration = 1L)

        registry.releaseClaim(controlRef("ext-3", "tc-3"), leaseToken = 7L, connectionGeneration = 1L)

        assertTrue(
            registry.tryClaim(controlRef("ext-3", "tc-3"), leaseToken = 8L, connectionGeneration = 1L),
            "an explicitly released claim is claimable again",
        )
    }

    /**
     * The other half of the same P1: a detached invocation completing LATE must
     * not retire an identity that now belongs to someone else. Its send failed,
     * it released the claim, a successor re-claimed — then the first completion
     * arrives. Blindly marking answered would delete the successor's live claim
     * and strand its response.
     *
     * FAIL-ON-REVERT: use `markAnswered` instead of `markAnsweredBy` in
     * `guaranteeExternalToolResponse` and the successor's claim disappears.
     */
    @Test
    fun markAnsweredByDoesNotClobberASuccessorClaim() {
        val registry = InboundControlRequestRegistry()
        registry.register(
            registerRequest("ext-4", InboundControlRequestRegistry.Kind.ExternalTool, 1L, "tc-4"),
        )
        registry.tryClaim(controlRef("ext-4", "tc-4"), leaseToken = 7L, connectionGeneration = 1L)
        registry.markDetached(controlRef("ext-4", "tc-4"), leaseToken = 7L, connectionGeneration = 1L)
        // Send failed -> claim returned -> a successor lease picks it up.
        registry.releaseClaim(controlRef("ext-4", "tc-4"), leaseToken = 7L, connectionGeneration = 1L)
        assertTrue(registry.tryClaim(controlRef("ext-4", "tc-4"), leaseToken = 8L, connectionGeneration = 1L))

        // The original detached invocation finally completes.
        assertFalse(
            registry.markAnsweredBy(controlRef("ext-4", "tc-4"), leaseToken = 7L, connectionGeneration = 1L),
            "a stale detached completion must not retire someone else's claim",
        )
        assertTrue(
            registry.ownsClaim(controlRef("ext-4", "tc-4"), leaseToken = 8L, connectionGeneration = 1L),
            "the successor still owns its claim",
        )

        // …and the rightful owner can still answer.
        assertTrue(registry.markAnsweredBy(controlRef("ext-4", "tc-4"), leaseToken = 8L, connectionGeneration = 1L))
    }
}

/** Shorthand for the (request_id, tool_call_id) identity (lgns8.22.4.1.3). */
private fun controlRef(requestId: String, toolCallId: String? = null) =
    InboundControlRequestRegistry.RequestRef(requestId, toolCallId)

/**
 * Every test here registers the same shape of request; only the identity, kind
 * and generation vary. One builder keeps the intent of each test visible.
 */
private fun registerRequest(
    requestId: String,
    kind: InboundControlRequestRegistry.Kind,
    connectionGeneration: Long,
    toolCallId: String? = null,
) = InboundControlRequestRegistry.RegisterRequest(
    requestId = requestId,
    kind = kind,
    connectionGeneration = connectionGeneration,
    toolCallId = toolCallId,
)
