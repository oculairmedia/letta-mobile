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
        assertTrue(registry.tryClaim("ext-1", leaseToken = 10L, connectionGeneration = 2L))
        assertFalse(registry.tryClaim("ext-1", leaseToken = 11L, connectionGeneration = 2L))
        // Owning lease also cannot claim twice — delivery is once-only.
        assertFalse(registry.tryClaim("ext-1", leaseToken = 10L, connectionGeneration = 2L))
        assertTrue(registry.ownsClaim("ext-1", leaseToken = 10L, connectionGeneration = 2L))
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
        assertFalse(registry.tryClaim("perm-1", leaseToken = 1L, connectionGeneration = 3L))
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
        assertTrue(registry.tryClaim("ext-1", leaseToken = 7L, connectionGeneration = 1L))
        registry.releaseClaim("ext-1", leaseToken = 7L, connectionGeneration = 1L)
        assertTrue(registry.isDeliverableTo("ext-1", leaseToken = 7L, connectionGeneration = 1L))
        assertTrue(registry.tryClaim("ext-1", leaseToken = 7L, connectionGeneration = 1L))
        registry.markAnswered("ext-1", connectionGeneration = 1L)
        assertFalse(registry.isDeliverableTo("ext-1", leaseToken = 7L, connectionGeneration = 1L))
        assertFalse(registry.tryClaim("ext-1", leaseToken = 7L, connectionGeneration = 1L))
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
        registry.markDispatched("ext-1", connectionGeneration = 5L)
        assertTrue(registry.isDeliverableTo("ext-1", leaseToken = 1L, connectionGeneration = 5L))
        assertTrue(registry.tryClaim("ext-1", leaseToken = 1L, connectionGeneration = 5L))
        assertFalse(registry.isDeliverableTo("ext-1", leaseToken = 1L, connectionGeneration = 5L))
    }
}
