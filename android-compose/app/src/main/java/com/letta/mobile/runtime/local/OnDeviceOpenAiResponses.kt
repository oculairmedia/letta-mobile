package com.letta.mobile.runtime.local

import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The model id a bridge response names (the request's, else the served one). */
@JvmInline
internal value class ResponseModelId(val value: String)

/** OpenAI `finish_reason` values the bridge sends. */
internal enum class FinishReason(val wire: String) {
    Stop("stop"),
    /** The output cap or the timeout cut the reply: the client sees it was truncated. */
    Length("length"),
    ToolCalls("tool_calls"),
}

/**
 * The OpenAI-shaped chat completion bodies the on-device bridge answers with, as one JSON body or
 * as an SSE stream. Split out of [LocalOpenAiOnDeviceBridge] (letta-mobile-by4vm.121) so its
 * session only handles requests. A reply's tool calls become OpenAI `tool_calls`, each with its
 * own id (LiteRT-LM calls carry none), alongside any text the model wrote (by4vm.121.16).
 */
internal interface OpenAiCompletionWriter {
    fun completionBody(reply: OnDeviceReply, model: ResponseModelId, finishReason: FinishReason): JsonObject

    fun OutputStream.writeStreamingCompletion(reply: OnDeviceReply, model: ResponseModelId, finishReason: FinishReason)
}

internal class OpenAiCompletions(private val json: Json) : OpenAiCompletionWriter {
    /** One streamed completion: its id, creation time and model, shared by all its chunks. */
    private class CompletionStream(val model: ResponseModelId) {
        val id = "chatcmpl-${UUID.randomUUID()}"
        val created = Instant.now().epochSecond
    }

    override fun completionBody(reply: OnDeviceReply, model: ResponseModelId, finishReason: FinishReason): JsonObject =
        completion(model, message = buildJsonObject {
            put("role", "assistant")
            if (reply.text.isEmpty() && reply.toolCalls.isNotEmpty()) put("content", null as String?) else put("content", reply.text)
            if (reply.toolCalls.isNotEmpty()) put("tool_calls", toolCallsJson(reply.toolCalls))
        }, finishReason = finishReason)

    override fun OutputStream.writeStreamingCompletion(reply: OnDeviceReply, model: ResponseModelId, finishReason: FinishReason) {
        val stream = CompletionStream(model)
        writeSseHeaders()
        if (reply.text.isNotEmpty() || reply.toolCalls.isEmpty()) {
            writeChunk(stream, buildJsonObject {
                put("role", "assistant")
                put("content", reply.text)
            }, finishReason = null)
        }
        // pi-ai's parser accepts each call's whole arguments string in one delta.
        if (reply.toolCalls.isNotEmpty()) {
            writeChunk(stream, buildJsonObject {
                put("role", "assistant")
                put("tool_calls", toolCallsJson(reply.toolCalls))
            }, finishReason = null)
        }
        writeChunk(stream, buildJsonObject {}, finishReason)
        writeDone()
    }

    private fun completion(model: ResponseModelId, message: JsonObject, finishReason: FinishReason): JsonObject =
        buildJsonObject {
            put("id", "chatcmpl-${UUID.randomUUID()}")
            put("object", "chat.completion")
            put("created", Instant.now().epochSecond)
            put("model", model.value)
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("index", 0)
                            put("message", message)
                            put("finish_reason", finishReason.wire)
                        },
                    )
                },
            )
        }

    private fun toolCallsJson(calls: List<OnDeviceToolCall>): JsonArray = buildJsonArray {
        calls.forEachIndexed { index, call ->
            add(
                buildJsonObject {
                    put("index", index)
                    put("id", call.id ?: "call_${UUID.randomUUID()}")
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject {
                            put("name", call.name)
                            put("arguments", call.argumentsJson)
                        },
                    )
                },
            )
        }
    }

    /** A chunk's `finish_reason` is null until the last one, which carries an empty delta. */
    private fun OutputStream.writeChunk(stream: CompletionStream, delta: JsonObject, finishReason: FinishReason?) {
        val body = buildJsonObject {
            put("id", stream.id)
            put("object", "chat.completion.chunk")
            put("created", stream.created)
            put("model", stream.model.value)
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("index", 0)
                            put("delta", delta)
                            put("finish_reason", finishReason?.wire)
                        },
                    )
                },
            )
        }
        write("data: ${json.encodeToString(JsonObject.serializer(), body)}\n\n".toByteArray(Charsets.UTF_8))
    }

    private fun OutputStream.writeSseHeaders() {
        write(
            "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.UTF_8),
        )
    }

    private fun OutputStream.writeDone() {
        write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
        flush()
    }
}

/**
 * letta-mobile-by4vm.121: letta-code retries a local provider's 5xx (up to 3 times), so a timeout
 * or a degenerate loop answered with 503 ran the same doomed generation again. A 4xx without retry
 * wording in its message ends the turn instead.
 */
internal const val NON_RETRYABLE_STATUS = 422

/** Tool calls end the turn as `tool_calls`; a reply the output cap or the timeout cut is `length`. */
internal fun finishReasonOf(reply: OnDeviceReply, generation: OnDeviceGeneration): FinishReason = when {
    reply.toolCalls.isNotEmpty() -> FinishReason.ToolCalls
    generation.stopReason == OnDeviceStopReason.MaxTokens || generation.stopReason == OnDeviceStopReason.Timeout -> FinishReason.Length
    else -> FinishReason.Stop
}
