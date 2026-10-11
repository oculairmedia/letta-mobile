package com.letta.mobile.data.meridian

import com.letta.mobile.data.controller.extras.ToolAudience
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The `agent-tools-mode` flag (letta-mobile-jna0o.9): a host default, overridable per agent. */
class AgentToolsModePolicyTest {
    @Test
    fun theFlagParsesAHostDefaultAndPerAgentOverrides() {
        assertEquals(AgentToolsModePolicy(), AgentToolsModePolicy.parse(null, null).getOrThrow())
        assertEquals(AgentToolsModePolicy(), AgentToolsModePolicy.parse(" ", "").getOrThrow())
        val policy = AgentToolsModePolicy.parse("META", "agent-a=native, agent-b = cli ,").getOrThrow()
        assertEquals(AgentToolsMode.META, policy.modeFor(null))
        assertEquals(AgentToolsMode.META, policy.modeFor("agent-z"))
        assertEquals(AgentToolsMode.NATIVE, policy.modeFor("agent-a"))
        assertEquals(AgentToolsMode.CLI, policy.modeFor("agent-b"))
        assertEquals("meta (agent-a=native, agent-b=cli)", policy.describe())
    }

    @Test
    fun aValueThatNamesNoModeIsRefusedWithTheChoices() {
        val failure = AgentToolsModePolicy.parse("sometimes", null).exceptionOrNull()
        assertEquals("agent-tools-mode 'sometimes' is not one of native|cli|meta", failure?.message)
        assertTrue(AgentToolsModePolicy.parse(null, "=meta").isFailure)
        assertTrue(AgentToolsModePolicy.parse(null, "agent-a").isFailure)
    }

    @Test
    fun withoutACliFrontDoorCliIsNative() {
        val policy = AgentToolsModePolicy(AgentToolsMode.CLI, mapOf("agent-a" to AgentToolsMode.CLI, "agent-b" to AgentToolsMode.META)).withoutCli()
        assertEquals(AgentToolsModePolicy(AgentToolsMode.NATIVE, mapOf("agent-a" to AgentToolsMode.NATIVE, "agent-b" to AgentToolsMode.META)), policy)
    }

    @Test
    fun nativeEverywhereLeavesTheRegistryAsItIs() {
        val registry = MeridianTestHost().registry
        assertSame(registry, AgentToolsModePolicy().apply(registry))
        assertSame(registry, AgentToolsModePolicy(perAgent = mapOf("agent-a" to AgentToolsMode.NATIVE)).apply(registry))
        val mixed = AgentToolsModePolicy(perAgent = mapOf("agent-a" to AgentToolsMode.META)).apply(MeridianTestHost(withCanvas = true).registry)
        assertEquals(listOf("meridian"), mixed.offeredTools(ToolAudience("agent-a")).map { it.name })
    }
}
