package com.letta.mobile.data.plugin.wire

import com.letta.mobile.plugin.api.ActionResult
import com.letta.mobile.plugin.api.ElementFallback
import com.letta.mobile.plugin.api.ElementQuery
import com.letta.mobile.plugin.api.LcpMethod
import com.letta.mobile.plugin.api.LogLevel
import com.letta.mobile.plugin.api.PlaceElement
import com.letta.mobile.plugin.api.PlaceImage
import com.letta.mobile.plugin.api.PluginEmit
import com.letta.mobile.plugin.api.SnapshotSource
import com.letta.mobile.plugin.api.UpdateElement
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginSecrets
import com.letta.mobile.data.plugin.SecretScrubber
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Capability enforcement and the secret discipline at the wire's boundary (letta-mobile-s416w.25). */
class LcpHostGuardsTest {
    private val place = PluginEmit(place = listOf(PlaceElement("widget", 2, JsonObject(emptyMap()), ElementFallback("Job"))))
    private val image = PluginEmit(placeImages = listOf(PlaceImage(SnapshotSource.Asset("sha256:abc"))))
    private val secrets = PluginSecrets().with("apiToken", "tok-s3cret-123")

    private suspend fun refusal(block: suspend () -> Unit): LcpCallException = assertFailsWith<LcpCallException> { block() }

    @Test
    fun theHostRefusesCallsThePluginHasNoCapabilityFor() = runTest {
        val rig = LcpTestRig(backgroundScope, capabilities = setOf(PluginCapability.CANVAS_PLACE)).ready()
        assertEquals(listOf("el-1"), rig.pluginSession.emit(place).placed)
        val cases = mapOf<PluginCapability, suspend () -> Unit>(
            PluginCapability.ASSETS_WRITE to { rig.pluginSession.emit(image) },
            PluginCapability.CANVAS_READ to { rig.pluginSession.readElements(ElementQuery()) },
        )
        cases.forEach { (capability, call) ->
            val refused = refusal(call)
            assertEquals(LcpErrorCode.CAPABILITY_REFUSED, refused.code)
            assertEquals(capability.wire, refused.error.data?.jsonObject?.get("capability")?.jsonPrimitive?.content)
        }
        assertEquals(LcpErrorCode.CAPABILITY_REFUSED, refusal { rig.pluginSession.putAsset("image/png", byteArrayOf(1)) }.code)
        assertEquals(listOf(place), rig.host.emits, "nothing refused reached the host")
    }

    @Test
    fun anEmitNeedsCapabilitiesByWhatItCarries() {
        val bytes = PluginEmit(update = listOf(UpdateElement("el-1", snapshot = SnapshotSource.Bytes("image/png", byteArrayOf(1)))))
        val needs = { emit: PluginEmit -> LcpCapabilityGuard.required(LcpMethod.EMIT, LcpCalls.EMIT.encodeParams(emit)) }
        assertEquals(setOf(PluginCapability.CANVAS_PLACE), needs(place))
        assertEquals(setOf(PluginCapability.ASSETS_WRITE), needs(image))
        assertEquals(setOf(PluginCapability.CANVAS_PLACE, PluginCapability.ASSETS_WRITE), needs(bytes))
        assertEquals(setOf(PluginCapability.CANVAS_READ), LcpCapabilityGuard.required(LcpMethod.READ_ELEMENTS, JsonObject(emptyMap())))
    }

    @Test
    fun aLogLineKeepsItsShapeWithTheSecretReplaced() = runTest {
        val rig = LcpTestRig(backgroundScope, secrets = secrets).ready()
        rig.pluginSession.log(LogParams(LogLevel.WARN, "auth with tok-s3cret-123", mapOf("token" to "tok-s3cret-123", "status" to "401")))
        assertEquals(
            LogParams(LogLevel.WARN, SecretScrubber.REFUSED, mapOf("token" to SecretScrubber.REFUSED, "status" to "401")),
            rig.host.logged.receive(),
        )
    }

    @Test
    fun anEmitHoldingASecretIsRefusedWhole() = runTest {
        val rig = LcpTestRig(backgroundScope, secrets = secrets).ready()
        val leaking = PluginEmit(update = listOf(UpdateElement("el-1", props = buildJsonObject { put("label", "tok-s3cret-123") })))
        assertEquals(LcpErrorCode.SECRET_REFUSED, refusal { rig.pluginSession.emit(leaking) }.code)
        assertEquals(emptyList(), rig.host.emits)
    }

    @Test
    fun anActionResultHoldingASecretInAnyEncodingIsRefused() = runTest {
        val rig = LcpTestRig(backgroundScope, secrets = secrets).ready()
        rig.plugin.invoke = { ActionResult.Ok("done", structured = buildJsonObject { put("debug", "dG9rLXMzY3JldC0xMjM=") }) }
        assertEquals(LcpErrorCode.SECRET_REFUSED, refusal { rig.hostSession.invoke(LcpTestRig.agentInvoke("start")) }.code)
    }

    @Test
    fun anActionErrorIsScrubbed() = runTest {
        val rig = LcpTestRig(backgroundScope, secrets = secrets).ready()
        rig.plugin.invoke = { throw LcpPluginSession.actionFailed("auth", "token tok-s3cret-123 rejected") }
        assertEquals(ActionResult.Error("auth", SecretScrubber.REFUSED), rig.hostSession.invoke(LcpTestRig.agentInvoke("start")))
    }

    @Test
    fun theHostNeverSendsASecretOverTheWire() = runTest {
        val rig = LcpTestRig(backgroundScope, secrets = secrets)
        val leaking = buildJsonObject { put("apiToken", "tok-s3cret-123") }
        assertEquals(LcpErrorCode.SECRET_REFUSED, refusal { rig.initialize(leaking) }.code)
        assertEquals(LcpSessionState.UNINITIALIZED, rig.pluginSession.state.value, "the plugin never saw it")
        assertEquals(LcpSessionState.CLOSED, rig.hostSession.state.value, "the host fails closed")
    }
}
