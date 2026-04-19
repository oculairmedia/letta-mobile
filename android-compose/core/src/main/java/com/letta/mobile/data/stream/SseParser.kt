package com.letta.mobile.data.stream

import com.letta.mobile.data.model.LettaMessage
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

object SseParser {
    private data class ProcessedEvent(
        val message: LettaMessage? = null,
        val isDone: Boolean = false,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(channel: ByteReadChannel): Flow<LettaMessage> = flow {
        val buffer = StringBuilder()
        val lineReader = Utf8LineReader(channel)
        var isDone = false

        while (!isDone) {
            val line = lineReader.readLine() ?: break

            if (line.isEmpty()) {
                val event = buffer.toString()
                buffer.clear()

                if (event.isNotBlank()) {
                    val processed = processEvent(event)
                    if (processed.isDone) {
                        isDone = true
                    } else {
                        processed.message?.let { emit(it) }
                    }
                }
            } else {
                buffer.append(line).append("\n")
            }
        }

        if (!isDone && buffer.isNotBlank()) {
            processEvent(buffer.toString()).message?.let { emit(it) }
        }
    }

    private fun processEvent(event: String): ProcessedEvent {
        val lines = event.lines()

        for (line in lines) {
            if (line.startsWith("data: ")) {
                val data = line.substring(6).trim()

                if (data == "[DONE]") {
                    return ProcessedEvent(isDone = true)
                }

                if (data.startsWith("{")) {
                    // The Letta server's /stream endpoint (used by the resume-stream
                    // subscriber, letta-mobile-mge5) emits partial tool-call-delta
                    // frames during token streaming that lack a `message_type` field
                    // (they are the raw LLM tool-use delta shape). These are
                    // INCREMENTAL and the complete envelope arrives later in the
                    // stream as a normal `tool_call_message`. Skip them silently
                    // rather than logging a warning storm.
                    if (!data.contains("\"message_type\"")) {
                        return ProcessedEvent()
                    }
                    return try {
                        val message = json.decodeFromString<LettaMessage>(data)
                        if (message.messageType != "ping") {
                            // Temporary instrumentation (letta-mobile-mge5
                            // garbled-content debug) — log the first 300 chars
                            // of every non-ping success path. This helps verify
                            // whether the raw JSON frame arriving at the phone
                            // matches what the server is authoritatively storing.
                            // Log the whole frame — logcat truncates at ~4KB
                            // per line on its own, so this is safe enough.
                            android.util.Log.d(
                                "SseParser",
                                "RX type=${message.messageType} FULL=${data}",
                            )
                        }
                        if (message.messageType == "ping") ProcessedEvent() else ProcessedEvent(message = message)
                    } catch (e: Exception) {
                        android.util.Log.w("SseParser", "Failed to parse SSE event: ${data.take(100)}", e)
                        ProcessedEvent()
                    }
                }
            }
        }

        return ProcessedEvent()
    }
}
