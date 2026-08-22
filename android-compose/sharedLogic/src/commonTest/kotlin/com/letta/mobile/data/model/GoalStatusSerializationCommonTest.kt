package com.letta.mobile.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalStatusSerializationCommonTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `goal status preserves known wire values`() {
        val decoded = json.decodeFromString<GoalStatus>("""{"status":"active"}""")

        assertEquals(GoalStatusState.Active, decoded.status)
        assertEquals("active", json.encodeToString(decoded).let { Json.parseToJsonElement(it).jsonObject["status"]!!.jsonPrimitive.content })
    }

    @Test
    fun `goal status preserves unknown wire values`() {
        val decoded = json.decodeFromString<GoalStatus>("""{"status":"future"}""")

        assertEquals(GoalStatusState.Unknown("future"), decoded.status)
        assertEquals("future", json.encodeToString(decoded).let { Json.parseToJsonElement(it).jsonObject["status"]!!.jsonPrimitive.content })
    }
}
