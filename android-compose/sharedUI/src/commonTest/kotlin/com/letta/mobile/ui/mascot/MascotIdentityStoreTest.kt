package com.letta.mobile.ui.mascot

import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotPalette
import com.letta.mobile.avatar.core.MascotShape
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MascotIdentityStoreTest {
    private val chosen = MascotIdentity(MascotShape.HEXAGON, MascotPalette.TEAL, rotationDegrees = 30)
    private fun agent(metadata: MascotIdentity? = null) = Agent(
        id = AgentId("agent-42"),
        name = "Forty-two",
        metadata = metadata?.withinAgentMetadata(null).orEmpty(),
    )

    @Test
    fun `the identity on the agent wins over the device cache and the generated one`() {
        val cached = MascotIdentity(MascotShape.PILL, MascotPalette.RED).encode()
        assertEquals(chosen, resolveMascotIdentity("agent-42", agent(chosen), cached))
    }

    @Test
    fun `the device cache stands in when the agent carries no identity`() {
        val cached = MascotIdentity(MascotShape.PILL, MascotPalette.RED)
        assertEquals(cached, resolveMascotIdentity("agent-42", agent(), cached.encode()))
    }

    @Test
    fun `an agent nobody chose an identity for draws the one generated from its id`() {
        assertEquals(MascotIdentity.seeded("agent-42"), resolveMascotIdentity("agent-42", agent(), null))
        assertEquals(MascotIdentity.seeded("agent-42"), resolveMascotIdentity("agent-42", null, null))
        assertNull(chosenMascotIdentity(agent(), null))
    }

    @Test
    fun `a turned identity survives the round trip through agent metadata`() {
        assertEquals(chosen, agent(chosen).mascotIdentity())
    }
}
