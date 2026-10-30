package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillAdminHandlersTest {

    private val expectedErrorMessage = "capability_unavailable: skill.install requires skill_path (or name) for App Server v2 skill_enable; agent-scoped shim install is retired"

    @Test
    fun `skill install requires skill_path or name - wrong param`() = runTest {
        val router = AdminRpcRouter()
        SkillAdminHandlers.register(router)

        val invocation = AdminRpcInvocation(
            requestId = "req-1",
            method = "skill.install",
            params = buildJsonObject {
                put("wrong_param", "value")
            }
        )

        val result = router.dispatch(invocation)
        val json = Json.parseToJsonElement(result).jsonObject

        assertEquals("false", json["success"]?.jsonPrimitive?.content)
        assertEquals(expectedErrorMessage, json["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `skill install requires skill_path or name - blank skill_path`() = runTest {
        val router = AdminRpcRouter()
        SkillAdminHandlers.register(router)

        val invocation = AdminRpcInvocation(
            requestId = "req-2",
            method = "skill.install",
            params = buildJsonObject {
                put("skill_path", "  ")
            }
        )

        val result = router.dispatch(invocation)
        val json = Json.parseToJsonElement(result).jsonObject

        assertEquals("false", json["success"]?.jsonPrimitive?.content)
        assertEquals(expectedErrorMessage, json["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `skill install requires skill_path or name - blank name`() = runTest {
        val router = AdminRpcRouter()
        SkillAdminHandlers.register(router)

        val invocation = AdminRpcInvocation(
            requestId = "req-3",
            method = "skill.install",
            params = buildJsonObject {
                put("name", "")
            }
        )

        val result = router.dispatch(invocation)
        val json = Json.parseToJsonElement(result).jsonObject

        assertEquals("false", json["success"]?.jsonPrimitive?.content)
        assertEquals(expectedErrorMessage, json["error"]?.jsonPrimitive?.content)
    }
}
