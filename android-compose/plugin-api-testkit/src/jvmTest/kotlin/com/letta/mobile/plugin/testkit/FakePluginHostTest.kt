package com.letta.mobile.plugin.testkit

import com.letta.mobile.plugin.api.ElementFallback
import com.letta.mobile.plugin.api.ElementQuery
import com.letta.mobile.plugin.api.EmitRefusal
import com.letta.mobile.plugin.api.PlaceElement
import com.letta.mobile.plugin.api.PluginEmit
import com.letta.mobile.plugin.api.PluginHostException
import com.letta.mobile.plugin.api.PluginHttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The fake host on its own, as a plugin's unit tests use it. */
class FakePluginHostTest {
    private val host = FakePluginHost(SamplePlugin.manifest, secrets = mapOf("apiToken" to "t0ken-value"))

    private fun card(label: String) = PlaceElement(
        kind = "card",
        v = 2,
        props = buildJsonObject { put("label", JsonPrimitive(label)) },
        fallback = ElementFallback(label),
    )

    @Test
    fun `emits place valid elements and refuse the rest by index`() = runBlocking {
        val receipt = host.emit(PluginEmit(place = listOf(card("a"), card("b").copy(kind = "poster"))))
        assertEquals(listOf("element-1"), receipt.placed)
        assertEquals(listOf(1), receipt.refused.map(EmitRefusal::index))
        assertEquals(listOf("ext:letta.sample/card"), host.readElements(ElementQuery()).map { it.type })
        assertEquals(setOf(ConformanceRule.EMIT), host.violations.map { it.rule }.toSet())
    }

    @Test
    fun `settings default from the manifest and secrets come from the host`() {
        assertEquals(JsonObject(mapOf("greeting" to JsonPrimitive("hello"))), host.settings)
        assertEquals("t0ken-value", host.secret("apiToken"))
    }

    @Test
    fun `assets are stored by sha256 ref`() = runBlocking {
        val ref = host.putAsset("image/png", byteArrayOf(1, 2, 3))
        assertTrue(ref.startsWith("sha256:") && ref.length == "sha256:".length + 64, ref)
        assertEquals(3, host.assets.getValue(ref).size)
    }

    @Test
    fun `the http client answers allowed origins and refuses others`() = runBlocking {
        val answering = FakePluginHost(SamplePlugin.manifest, httpHandler = { PluginHttpResponse(204, emptyMap(), ByteArray(0)) })
        assertEquals(204, answering.httpClient.get("https://api.sample.test/x").status)
        val refused = assertFailsWith<PluginHostException> { answering.httpClient.get("https://api.sample.test.evil/x") }
        assertEquals(PluginHostException.ORIGIN_DENIED, refused.code)
        assertEquals(2, answering.httpRequests.size)
    }

    @Test
    fun `a closed host refuses every use`() {
        host.close()
        val refused = assertFailsWith<PluginHostException> { host.secret("apiToken") }
        assertEquals(PluginHostException.DEACTIVATED, refused.code)
        assertEquals(setOf(ConformanceRule.LIFECYCLE), host.violations.map { it.rule }.toSet())
    }
}
