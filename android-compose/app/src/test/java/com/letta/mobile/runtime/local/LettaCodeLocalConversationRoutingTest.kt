package com.letta.mobile.runtime.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the local-vs-remote timeline routing (LocalRoutingTimelineTransport):
 * only genuinely on-device conversations may go to the embedded runtime.
 */
class LettaCodeLocalConversationRoutingTest {
    @Test
    fun onDeviceConversationsRouteLocal() {
        // On-device ids are local-conv-<localAgentId>, and local agents are local-agent-*.
        assertTrue(LettaCodeLocalTimelineTransport.isLocalConversationId("local-conv-local-agent-abc123"))
    }

    @Test
    fun remoteLettaCodeLocalConversationsDoNotRouteLocal() {
        // Regression: the REMOTE letta-code-local backend (Meridian over Iroh) names
        // its conversations local-conv-<n> too. Matching the bare `local-conv-` prefix
        // misrouted follow-up sends on these to the unavailable on-device runtime
        // ("local runtime not available"). They must stay on the remote transport.
        assertFalse(LettaCodeLocalTimelineTransport.isLocalConversationId("local-conv-101"))
        assertFalse(LettaCodeLocalTimelineTransport.isLocalConversationId("local-conv-100"))
        assertFalse(LettaCodeLocalTimelineTransport.isLocalConversationId("local-conv-42"))
    }

    @Test
    fun plainRemoteConversationsDoNotRouteLocal() {
        assertFalse(LettaCodeLocalTimelineTransport.isLocalConversationId("conv-be818444-552c-409a-bda4-f48989fb5f84"))
        assertFalse(LettaCodeLocalTimelineTransport.isLocalConversationId("conv-8d4b6225"))
    }
}
