package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SkillAdminHandlersTest {

    @Test
    fun `skill install requires skill_path or name`() = runTest {
        val router = AdminRpcRouter()
        SkillAdminHandlers.register(router)

        val invocation = AdminRpcInvocation(
            requestId = "req-1",
            method = "skill.install",
            params = buildJsonObject {
                put("wrong_param", "value")
            }
        )

        // AdminRpcRouter catches exceptions thrown by handlers and wraps them in an error JSON response.
        // We want to assert on the actual exception type as requested.
        // Since adminError() throws IllegalArgumentException, let's trigger it directly
        // by reflecting on the internal handlers map and calling the handler directly.
        val handlersField = AdminRpcRouter::class.java.getDeclaredField("handlers")
        handlersField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val handlers = handlersField.get(router) as Map<String, suspend (kotlinx.serialization.json.JsonObject?, AdminRpcRequestContext) -> kotlinx.serialization.json.JsonElement>

        val installHandler = handlers["skill.install"] ?: throw IllegalStateException("skill.install handler not registered")

        val exception = assertFailsWith<IllegalArgumentException> {
            installHandler(invocation.params, invocation.context)
        }

        assertEquals(
            "capability_unavailable: skill.install requires skill_path (or name) for App Server v2 skill_enable; agent-scoped shim install is retired",
            exception.message
        )
    }
}
