package com.letta.mobile.data.stream

import com.letta.mobile.data.model.LettaMessage
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

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
                buffer.append(line).append('\n')
            }
        }

        if (!isDone && buffer.isNotBlank()) {
            val processed = processEvent(buffer.toString())
            if (!processed.isDone) {
                processed.frame?.let { emit(it) }
            }
        }
    }

    fun parseRawEvents(channel: ByteReadChannel): Flow<SseFrame.RawEvent> = flow {
        val buffer = StringBuilder()
        val lineReader = Utf8LineReader(channel)
        while (true) {
            val line = lineReader.readLine() ?: break
            if (line.isEmpty()) {
                val event = buffer.toString()
                buffer.clear()
                if (event.isNotBlank()) processRawEvent(event)?.let { emit(it) }
            } else {
                buffer.append(line).append('\n')
            }
        }
        if (buffer.isNotBlank()) processRawEvent(buffer.toString())?.let { emit(it) }
    }

    private fun processRawEvent(event: String): SseFrame.RawEvent? {
        var eventName: String? = null
        var dataBuilder: StringBuilder? = null
        var id: String? = null

        event.lineSequence().forEach { line ->
            if (line.isEmpty()) return@forEach

            if (line.startsWith("data:")) {
                val dataStr = line.substring(5).let { if (it.startsWith(" ")) it.substring(1) else it }
                if (dataBuilder == null) {
                    dataBuilder = StringBuilder(dataStr)
                } else {
                    dataBuilder?.append('\n')?.append(dataStr)
                }
            } else if (line.startsWith("event:")) {
                if (eventName == null) {
                    eventName = line.substring(6).trim().takeIf { it.isNotBlank() }
                }
            } else if (line.startsWith("id:")) {
                if (id == null) {
                    id = line.substring(3).trim().takeIf { it.isNotBlank() }
                }
            }
        }

        if (dataBuilder == null) return null
        val data = dataBuilder!!.toString().trim()
        if (data == "[DONE]") return null

        return SseFrame.RawEvent(
            event = eventName,
            data = data ?: "",
            id = id,
        )
    }

    private fun processEvent(event: String): ProcessedEvent {
        var hasComment = false
        var dataBuilder: StringBuilder? = null

        event.lineSequence().forEach { line ->
            if (line.isEmpty()) return@forEach

            if (line.startsWith(":")) {
                hasComment = true
            } else if (line.startsWith("data:")) {
                val dataStr = line.substring(5).let { if (it.startsWith(" ")) it.substring(1) else it }
                if (dataBuilder == null) {
                    dataBuilder = StringBuilder(dataStr)
                } else {
                    dataBuilder?.append('\n')?.append(dataStr)
                }
            }
        }

        if (dataBuilder == null) {
            return if (hasComment) {
                ProcessedEvent(frame = SseFrame.Heartbeat)
            } else {
                ProcessedEvent()
            }
        }

        val data = dataBuilder?.toString()?.trim() ?: ""

        if (data == "[DONE]") {
            return ProcessedEvent(frame = SseFrame.Done, isDone = true)
        }

        if (!data.startsWith("{")) {
            return ProcessedEvent(frame = SseFrame.Heartbeat)
        }

        if (!data.contains("\"message_type\"")) {
            return ProcessedEvent(frame = SseFrame.Heartbeat)
        }

        return runCatching {
            val message = json.decodeFromString<LettaMessage>(data)
            if (message.messageType == "ping") {
                ProcessedEvent(frame = SseFrame.Heartbeat)
            } else {
                ProcessedEvent(frame = SseFrame.Message(message))
            }
        }.getOrDefault(ProcessedEvent())
    }
}
