package com.letta.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class AmbientShaderAgentBackgroundTest {

    @Test
    fun `maps active status aliases to running ambient state`() {
        assertEquals(AmbientAgentStatus.Running, AmbientAgentStatus.from("Running"))
        assertEquals(AmbientAgentStatus.Running, AmbientAgentStatus.from("working"))
        assertEquals(AmbientAgentStatus.Running, AmbientAgentStatus.from("STREAMING"))
        assertEquals(AmbientAgentStatus.Active, AmbientAgentStatus.from("live"))
    }

    @Test
    fun `maps terminal status aliases to terminal ambient states`() {
        assertEquals(AmbientAgentStatus.Failed, AmbientAgentStatus.from("error"))
        assertEquals(AmbientAgentStatus.Failed, AmbientAgentStatus.from("failure"))
        assertEquals(AmbientAgentStatus.Completed, AmbientAgentStatus.from("done"))
        assertEquals(AmbientAgentStatus.Completed, AmbientAgentStatus.from("success"))
    }

    @Test
    fun `unknown and blank statuses are idle`() {
        assertEquals(AmbientAgentStatus.Idle, AmbientAgentStatus.from(""))
        assertEquals(AmbientAgentStatus.Idle, AmbientAgentStatus.from("paused"))
    }
}
