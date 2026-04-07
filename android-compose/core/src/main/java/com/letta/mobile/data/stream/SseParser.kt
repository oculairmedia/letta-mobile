package com.letta.mobile.data.stream

import com.letta.mobile.data.model.LettaMessage
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

object SseParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(channel: ByteReadChannel): Flow<LettaMessage> = flow {
        val buffer = StringBuilder()

        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break

            if (line.isEmpty()) {
                val event = buffer.toString()
                buffer.clear()

                if (event.isNotBlank()) {
                    processEvent(event)?.let { emit(it) }
                }
            } else {
                buffer.append(line).append("\n")
            }
        }

        if (buffer.isNotBlank()) {
            processEvent(buffer.toString())?.let { emit(it) }
        }
    }

    private fun processEvent(event: String): LettaMessage? {
        val lines = event.lines()

        for (line in lines) {
            if (line.startsWith("data: ")) {
                val data = line.substring(6).trim()

                if (data == "[DONE]") {
                    return null
                }

                if (data.startsWith("{")) {
                    return try {
                        val message = json.decodeFromString<LettaMessage>(data)
                        if (message.messageType == "ping") null else message
                    } catch (e: Exception) {
                        android.util.Log.w("SseParser", "Failed to parse SSE event: ${data.take(100)}", e)
                        null
                    }
                }
            }
        }

        return null
    }
}
