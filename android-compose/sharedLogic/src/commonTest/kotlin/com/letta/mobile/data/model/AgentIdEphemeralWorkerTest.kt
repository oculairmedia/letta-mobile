package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentIdEphemeralWorkerTest {

    @Test
    fun agentLocalIdsAreEphemeralLettaCodeWorkers() {
        assertTrue(AgentId("agent-local-worker-123").isLettaCodeEphemeralWorker())
    }

    @Test
    fun persistedLocalAgentIdsAreNotEphemeralLettaCodeWorkers() {
        assertFalse(AgentId("agent-local-07f5b235-633f-44be-a08f-32373247f541").isLettaCodeEphemeralWorker())
        assertFalse(AgentId("local-agent-device-123").isLettaCodeEphemeralWorker())
    }

    @Test
    fun ordinaryAndBlankIdsAreNotEphemeralLettaCodeWorkers() {
        assertFalse(AgentId("agent-123").isLettaCodeEphemeralWorker())
        assertFalse(AgentId("").isLettaCodeEphemeralWorker())
        assertFalse(AgentId("unrelated-agent-123").isLettaCodeEphemeralWorker())
    }
}
