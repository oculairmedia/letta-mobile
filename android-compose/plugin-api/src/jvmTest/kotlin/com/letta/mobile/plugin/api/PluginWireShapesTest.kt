package com.letta.mobile.plugin.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** The SPI's DTOs are the LCP wire shapes (plan section 5): their JSON spelling is part of the contract. */
class PluginWireShapesTest {
    private val json = Json { encodeDefaults = false }

    private fun <T> assertWire(serializer: KSerializer<T>, value: T, wire: String) {
        assertEquals(wire, json.encodeToString(serializer, value))
        assertEquals(value, json.decodeFromString(serializer, wire))
    }

    @Test
    fun `an action call carries a type-tagged origin`() {
        val call = ActionCall(
            action = "start",
            input = buildJsonObject { put("label", JsonPrimitive("Render")) },
            context = ActionContext(ActionOrigin.Agent("agent-1", toolCallId = "call-9"), canvasId = "c1"),
        )
        assertWire(
            ActionCall.serializer(),
            call,
            """{"action":"start","input":{"label":"Render"},"context":{"origin":{"type":"agent","agentId":"agent-1","toolCallId":"call-9"},"canvasId":"c1"}}""",
        )
        assertWire(ActionOrigin.serializer(), ActionOrigin.View("el-1", "peer"), """{"type":"view","elementId":"el-1","peerId":"peer"}""")
        assertWire(ActionOrigin.serializer(), ActionOrigin.Host("refresh"), """{"type":"host","reason":"refresh"}""")
    }

    @Test
    fun `health is status-tagged`() {
        assertWire(PluginHealth.serializer(), PluginHealth.Ok, """{"status":"ok"}""")
        assertWire(PluginHealth.serializer(), PluginHealth.Degraded("slow"), """{"status":"degraded","reason":"slow"}""")
        assertWire(PluginHealth.serializer(), PluginHealth.Failed("down"), """{"status":"failed","reason":"down"}""")
    }

    @Test
    fun `snapshot bytes travel as base64`() {
        assertWire(
            SnapshotSource.serializer(),
            SnapshotSource.Bytes("image/png", byteArrayOf(1, 2, 3)),
            """{"type":"bytes","mediaType":"image/png","bytes":"AQID"}""",
        )
        assertWire(SnapshotSource.serializer(), SnapshotSource.Asset("sha256:ab"), """{"type":"asset","ref":"sha256:ab"}""")
    }

    @Test
    fun `an emit and its receipt`() {
        val emit = PluginEmit(
            place = listOf(PlaceElement("widget", 2, buildJsonObject { put("status", JsonPrimitive("idle")) }, ElementFallback("Job"))),
            remove = listOf("el-2"),
        )
        assertWire(
            PluginEmit.serializer(),
            emit,
            """{"place":[{"kind":"widget","v":2,"props":{"status":"idle"},"fallback":{"title":"Job"}}],"remove":["el-2"]}""",
        )
        assertWire(
            EmitReceipt.serializer(),
            EmitReceipt(placed = listOf("el-3"), refused = listOf(EmitRefusal(1, "unknown kind"))),
            """{"placed":["el-3"],"refused":[{"index":1,"reason":"unknown kind"}]}""",
        )
    }

    @Test
    fun `element events and log levels use the wire spellings`() {
        assertWire(
            ElementEvent.serializer(),
            ElementEvent("widget", "el-1", ElementEventType.VIEW_OPENED),
            """{"kind":"widget","elementId":"el-1","event":"viewOpened"}""",
        )
        assertWire(LogLevel.serializer(), LogLevel.WARN, "\"warn\"")
    }
}
