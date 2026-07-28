package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeInvalidationPolicyTest {
    @Test
    fun agentUpdateRestartsOnModelContextToolsMemoryAndSkills() {
        assertTrue(RuntimeInvalidationPolicy.agentUpdateRequiresRestart(buildJsonObject { put("model", "x") }))
        assertTrue(
            RuntimeInvalidationPolicy.agentUpdateRequiresRestart(
                buildJsonObject {
                    put("model_settings", buildJsonObject { put("context_window_limit", 128000) })
                },
            ),
        )
        assertTrue(
            RuntimeInvalidationPolicy.agentUpdateRequiresRestart(
                buildJsonObject {
                    put("llm_config", buildJsonObject { put("context_window_limit", 64000) })
                },
            ),
        )
        // Non-object nested values must not throw; top-level key still forces restart.
        assertTrue(
            RuntimeInvalidationPolicy.agentUpdateRequiresRestart(
                buildJsonObject {
                    put("model_settings", "not-an-object")
                },
            ),
        )
        assertTrue(RuntimeInvalidationPolicy.agentUpdateRequiresRestart(buildJsonObject { put("tools", "[]") }))
        assertTrue(RuntimeInvalidationPolicy.agentUpdateRequiresRestart(buildJsonObject { put("skills", "[]") }))
        assertTrue(RuntimeInvalidationPolicy.agentUpdateRequiresRestart(buildJsonObject { put("memory", "{}") }))
        assertTrue(RuntimeInvalidationPolicy.agentUpdateRequiresRestart(buildJsonObject { put("system", "hi") }))
        assertFalse(RuntimeInvalidationPolicy.agentUpdateRequiresRestart(buildJsonObject { put("name", "rename") }))
        assertFalse(RuntimeInvalidationPolicy.agentUpdateRequiresRestart(buildJsonObject { put("description", "d") }))
    }

    @Test
    fun conversationUpdateRestartsOnCapturedOverridesOnly() {
        assertTrue(RuntimeInvalidationPolicy.conversationUpdateRequiresRestart(buildJsonObject { put("model", "x") }))
        assertTrue(RuntimeInvalidationPolicy.conversationUpdateRequiresRestart(buildJsonObject { put("system", "x") }))
        assertFalse(RuntimeInvalidationPolicy.conversationUpdateRequiresRestart(buildJsonObject { put("title", "t") }))
    }
}
