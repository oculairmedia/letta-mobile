package com.letta.mobile.data.plugin.wire

import com.letta.mobile.plugin.api.ActionCall
import com.letta.mobile.plugin.api.ActionResult
import com.letta.mobile.plugin.api.ElementEvent
import com.letta.mobile.plugin.api.LcpMethod
import com.letta.mobile.plugin.api.PluginEmit
import com.letta.mobile.plugin.api.PluginHealth
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A method with the serializers of its params and, for a request, its result. */
sealed interface LcpCallType<P> {
    val method: LcpMethod
    val params: KSerializer<P>

    /** [value] as the JSON params object of this call. */
    fun encodeParams(value: P): JsonObject = LcpWire.json.encodeToJsonElement(params, value).jsonObject

    /** [json] as typed params, or [LcpErrorCode.INVALID_PARAMS] at the call's boundary. */
    fun decodeParams(json: JsonObject): P = decodeOrRefuse(params, json, "params of ${method.wire}")
}

class LcpRequestType<P, R>(
    override val method: LcpMethod,
    override val params: KSerializer<P>,
    val result: KSerializer<R>,
) : LcpCallType<P> {
    fun encodeResult(value: R): JsonElement = LcpWire.json.encodeToJsonElement(result, value)

    fun decodeResult(json: JsonElement): R = decodeOrRefuse(result, json, "result of ${method.wire}")
}

class LcpNotificationType<P>(override val method: LcpMethod, override val params: KSerializer<P>) : LcpCallType<P>

private fun <T> decodeOrRefuse(serializer: KSerializer<T>, json: JsonElement, what: String): T = try {
    LcpWire.json.decodeFromJsonElement(serializer, json)
} catch (e: SerializationException) {
    throw LcpCallException(LcpErrorCode.INVALID_PARAMS, "invalid $what: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
} catch (e: IllegalArgumentException) {
    throw LcpCallException(LcpErrorCode.INVALID_PARAMS, "invalid $what: ${e.message.orEmpty()}")
}

/**
 * Every method of `:plugin-api`'s [LcpMethod] with its typed params and result (the SPI's DTOs, or
 * the wire-only envelopes of `LcpMessages.kt`): the table a typed host or plugin calls through, and
 * the one the golden transcripts are held to.
 */
object LcpCalls {
    val INITIALIZE = LcpRequestType(LcpMethod.INITIALIZE, InitializeParams.serializer(), InitializeResult.serializer())
    val ACTIVATE = LcpRequestType(LcpMethod.ACTIVATE, LcpEmpty.serializer(), LcpEmpty.serializer())
    val HEALTH = LcpRequestType(LcpMethod.HEALTH, LcpEmpty.serializer(), PluginHealth.serializer())
    val DEACTIVATE = LcpRequestType(LcpMethod.DEACTIVATE, LcpEmpty.serializer(), LcpEmpty.serializer())
    val INVOKE = LcpRequestType(LcpMethod.INVOKE, ActionCall.serializer(), ActionResult.Ok.serializer())
    val ELEMENT_EVENT = LcpNotificationType(LcpMethod.ELEMENT_EVENT, ElementEvent.serializer())
    val SETTINGS_CHANGED = LcpNotificationType(LcpMethod.SETTINGS_CHANGED, SettingsChangedParams.serializer())
    val EMIT = LcpRequestType(LcpMethod.EMIT, PluginEmit.serializer(), EmitResult.serializer())
    val PUT_ASSET_BEGIN = LcpRequestType(LcpMethod.PUT_ASSET_BEGIN, PutAssetBeginParams.serializer(), PutAssetBeginResult.serializer())
    val PUT_ASSET_CHUNK = LcpRequestType(LcpMethod.PUT_ASSET_CHUNK, PutAssetChunkParams.serializer(), LcpEmpty.serializer())
    val PUT_ASSET_END = LcpRequestType(LcpMethod.PUT_ASSET_END, PutAssetEndParams.serializer(), PutAssetEndResult.serializer())
    val READ_ELEMENTS = LcpRequestType(LcpMethod.READ_ELEMENTS, ReadElementsParams.serializer(), ReadElementsResult.serializer())
    val LOG = LcpNotificationType(LcpMethod.LOG, LogParams.serializer())
    val CANCEL = LcpNotificationType(LcpMethod.CANCEL, CancelParams.serializer())

    val all: List<LcpCallType<*>> = listOf(
        INITIALIZE, ACTIVATE, HEALTH, DEACTIVATE, INVOKE, ELEMENT_EVENT, SETTINGS_CHANGED,
        EMIT, PUT_ASSET_BEGIN, PUT_ASSET_CHUNK, PUT_ASSET_END, READ_ELEMENTS, LOG, CANCEL,
    )

    private val byMethod: Map<LcpMethod, LcpCallType<*>> = all.associateBy { it.method }

    fun of(method: LcpMethod): LcpCallType<*> = byMethod.getValue(method)
}
