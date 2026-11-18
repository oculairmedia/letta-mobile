package com.letta.mobile.data.voice.host.client

import com.letta.mobile.data.voice.host.HostVoiceWire
import com.letta.mobile.data.voice.host.voiceModelsListContractText
import com.letta.mobile.data.voice.host.voiceSetupClearContractText
import com.letta.mobile.data.voice.host.voiceSetupGetContractText
import com.letta.mobile.data.voice.host.voiceSetupSetContractText
import com.letta.mobile.data.voice.host.voiceSetupTestContractText
import com.letta.mobile.data.voice.host.voiceSpeechContractText
import com.letta.mobile.data.voice.host.voiceTranscribeContractText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** A host that answers each voice request with what the test scripts, and records every request. */
internal class FakeHostVoiceLink(var capabilities: Set<String> = setOf(HostVoiceWire.CAPABILITY)) : HostVoiceLink {
    /** Every request: its method and params, in order. */
    val calls = mutableListOf<Pair<String, JsonObject>>()

    /** The answer to a single request. */
    var answer: (String, JsonObject) -> HostVoiceReply = { method, _ -> error("no answer scripted for $method") }

    /** The frames of a streamed request. */
    var frames: (JsonObject) -> Flow<JsonObject> = { emptyFlow() }

    override val hostCapabilities: Set<String> get() = capabilities

    override suspend fun call(method: String, params: JsonObject): HostVoiceReply {
        calls += method to params
        return answer(method, params)
    }

    override fun stream(method: String, params: JsonObject): Flow<JsonObject> {
        calls += method to params
        return frames(params)
    }

    fun methods(): List<String> = calls.map { it.first }

    /** Answers [method] with [response] (an `admin_rpc_response`), every other method as unscripted. */
    fun answering(method: String, response: JsonObject): FakeHostVoiceLink = apply {
        val previous = answer
        answer = { m, p -> if (m == method) reply(response) else previous(m, p) }
    }
}

/** One fixture case of the voice-endpoint v1 contract. */
internal class VoiceCase(val method: String, val json: JsonObject) {
    val name: String get() = (json.getValue("name") as JsonPrimitive).content
    val params: JsonObject get() = json.getValue("params").jsonObject
    val response: JsonObject get() = json.getValue("expected_response").jsonObject
    val serverFrames: List<JsonObject> get() = (json["server_frames"] as? JsonArray).orEmpty().map { it.jsonObject }
    val succeeded: Boolean get() = (response.getValue("success") as JsonPrimitive).content == "true"
    val error: String? get() = (response["error"] as? JsonPrimitive)?.content

    /** The stream frames, then the final response, as the host writes them. */
    fun stream(): Flow<JsonObject> = flowOf(*(serverFrames + response).toTypedArray())

    override fun toString(): String = "$method: $name"
}

/** The per-method fixtures, by method. */
internal object VoiceFixtures {
    private val json = Json { ignoreUnknownKeys = true }

    private val texts = listOf(
        voiceSetupGetContractText, voiceSetupSetContractText, voiceSetupClearContractText, voiceSetupTestContractText,
        voiceModelsListContractText, voiceTranscribeContractText, voiceSpeechContractText,
    )

    val cases: Map<String, List<VoiceCase>> = texts.map { json.parseToJsonElement(it).jsonObject }.associate { block ->
        val method = (block.getValue("method") as JsonPrimitive).content
        method to block.getValue("cases").jsonArray.map { VoiceCase(method, it.jsonObject) }
    }

    fun of(method: String): List<VoiceCase> = cases.getValue(method)

    fun case(method: String, name: String): VoiceCase = of(method).single { it.name == name }
}

/** [response] (an `admin_rpc_response` frame) as the link's reply. */
internal fun reply(response: JsonObject): HostVoiceReply = HostVoiceReply(
    success = (response.getValue("success") as JsonPrimitive).content == "true",
    result = response["result"],
    error = (response["error"] as? JsonPrimitive)?.content,
)
