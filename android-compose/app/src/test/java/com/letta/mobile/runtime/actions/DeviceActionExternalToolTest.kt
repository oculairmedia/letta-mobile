package com.letta.mobile.runtime.actions

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionExternalToolTest {
    @Test
    fun `host registry advertises device action while factory registry stays empty`() {
        val tool = DeviceActionExternalTool(DeviceActionCommandExecutor { "{}" })

        val hostDefinitions = ExternalToolRegistry.hostTools(listOf(tool))
            .advertisedToolsCommandGroups()
            .orEmpty()
            .flatMap { it.tools }

        assertEquals(listOf(DeviceActionExternalTool.NAME), hostDefinitions.map { it.name })
        assertEquals(null, ExternalToolRegistry.factoryDefault().advertisedToolsCommandGroups())
    }

    @Test
    fun `device action passes the command envelope to the real executor seam`() = runTest {
        var received: JsonObject? = null
        val tool = DeviceActionExternalTool(DeviceActionCommandExecutor { commandJson ->
            received = kotlinx.serialization.json.Json.parseToJsonElement(commandJson).jsonObject
            "{\"command\":\"hardware.flashlight\",\"success\":true}"
        })

        val input = buildJsonObject {
            put("command", "hardware.flashlight")
            putJsonObject("input") { put("enabled", true) }
        }
        val result = tool.invoke(input)

        val command = requireNotNull(received)
        assertEquals("hardware.flashlight", command["command"]!!.jsonPrimitive.content)
        assertTrue(command["input"]!!.jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result is ExternalToolResult.Success)
        assertTrue((result as ExternalToolResult.Success).content.contains("\"success\":true"))
    }

    @Test
    fun `schema uses one existing device action envelope`() {
        val schema = DeviceActionExternalTool(DeviceActionCommandExecutor { "{}" }).inputSchema

        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertTrue(schema.toString().contains("command"))
        assertTrue(schema.toString().contains("input"))
        assertFalse(schema.toString().contains("hardware.flashlight_on"))
    }
}
