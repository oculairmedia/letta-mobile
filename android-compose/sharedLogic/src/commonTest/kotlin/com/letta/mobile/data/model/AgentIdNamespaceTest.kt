package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentIdNamespaceTest {

    @Test
    fun `normalizeToBareId strips leading letta_ prefix`() {
        assertEquals(
            "agent-c356b54a-8b37-4d53-b9d0-b43164749b6f",
            AgentIdNamespace.normalizeToBareId("letta_agent-c356b54a-8b37-4d53-b9d0-b43164749b6f")
        )
        assertEquals("custom_agent", AgentIdNamespace.normalizeToBareId("letta_custom_agent"))
    }

    @Test
    fun `normalizeToBareId leaves already-bare ID untouched`() {
        assertEquals(
            "agent-c356b54a-8b37-4d53-b9d0-b43164749b6f",
            AgentIdNamespace.normalizeToBareId("agent-c356b54a-8b37-4d53-b9d0-b43164749b6f")
        )
        assertEquals("bare-123", AgentIdNamespace.normalizeToBareId("bare-123"))
        assertEquals("", AgentIdNamespace.normalizeToBareId(""))
    }

    @Test
    fun `toMatrixId adds leading letta_ prefix when absent`() {
        assertEquals(
            "letta_agent-c356b54a-8b37-4d53-b9d0-b43164749b6f",
            AgentIdNamespace.toMatrixId("agent-c356b54a-8b37-4d53-b9d0-b43164749b6f")
        )
        assertEquals("letta_custom", AgentIdNamespace.toMatrixId("custom"))
        assertEquals("letta_", AgentIdNamespace.toMatrixId(""))
    }

    @Test
    fun `toMatrixId leaves already namespaced ID untouched`() {
        assertEquals(
            "letta_agent-c356b54a-8b37-4d53-b9d0-b43164749b6f",
            AgentIdNamespace.toMatrixId("letta_agent-c356b54a-8b37-4d53-b9d0-b43164749b6f")
        )
    }
}
