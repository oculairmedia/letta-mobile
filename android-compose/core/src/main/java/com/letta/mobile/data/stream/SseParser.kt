package com.letta.mobile.data.stream

import com.letta.mobile.data.model.LettaMessage
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

sealed interface SseFrame {
    data class Message(val message: LettaMessage) : SseFrame
    data object Heartbeat : SseFrame
    data object Done : SseFrame
}

object SseParser {
    private data class ProcessedEvent(
        val frame: SseFrame? = null,
        val isDone: Boolean = false,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(channel: ByteReadChannel): Flow<LettaMessage> =
        parseFrames(channel)
            .filterIsInstance<SseFrame.Message>()
            .map { it.message }

    fun parseFrames(channel: ByteReadChannel): Flow<SseFrame> = flow {
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
                        processed.frame?.let { emit(it) }
                    }
                }
            } else {
                buffer.append(line).append("\n")
            }
        }

        if (!isDone && buffer.isNotBlank()) {
            val processed = processEvent(buffer.toString())
            if (!processed.isDone) {
                processed.frame?.let { emit(it) }
            }
        }
    }

    private fun processEvent(event: String): ProcessedEvent {
        val lines = event.lineSequence()
            .filter { it.isNotEmpty() }
            .toList()
        val hasComment = lines.any { it.startsWith(":") }
        val dataLines = lines
            .filter { it.startsWith("data:") }
            .map { line ->
                line.removePrefix("data:").let { data ->
                    if (data.startsWith(" ")) data.drop(1) else data
                }
            }

        if (dataLines.isEmpty()) {
            return if (hasComment) {
                ProcessedEvent(frame = SseFrame.Heartbeat)
            } else {
                ProcessedEvent()
            }
        }

        val data = dataLines.joinToString("\n").trim()

        if (data == "[DONE]") {
            return ProcessedEvent(frame = SseFrame.Done, isDone = true)
        }

        if (!data.startsWith("{")) {
            // Non-JSON data frames are still valid SSE traffic and therefore
            // useful liveness signals for watchdogs, but they are not timeline
            // messages.
            return ProcessedEvent(frame = SseFrame.Heartbeat)
        }

        // The Letta server's /stream endpoint (used by the resume-stream
        // subscriber, letta-mobile-mge5) emits partial tool-call-delta frames
        // during token streaming that lack a `message_type` field (they are
        // the raw LLM tool-use delta shape). These are INCREMENTAL and the
        // complete envelope arrives later in the stream as a normal
        // `tool_call_message`. Treat them as liveness-only frames rather than
        // logging a warning storm or emitting a timeline message.
        if (!data.contains("\"message_type\"")) {
            return ProcessedEvent(frame = SseFrame.Heartbeat)
        }

        return try {
            val message = json.decodeFromString<LettaMessage>(data)
            if (message.messageType == "ping") {
                ProcessedEvent(frame = SseFrame.Heartbeat)
            } else {
                // Temporary instrumentation (letta-mobile-mge5 garbled-content
                // debug) — log the first 300 chars of every non-ping success
                // path. This helps verify whether the raw JSON frame arriving
                // at the phone matches what the server is authoritatively
                // storing. Log the whole frame — logcat truncates at ~4KB per
                // line on its own, so this is safe enough.
                android.util.Log.d(
                    "SseParser",
                    "RX type=${message.messageType} FULL=${data}",
                )
                ProcessedEvent(frame = SseFrame.Message(message))
            }
        } catch (e: Exception) {
            android.util.Log.w("SseParser", "Failed to parse SSE event: ${data.take(100)}", e)
            ProcessedEvent()
        }
    }
}
