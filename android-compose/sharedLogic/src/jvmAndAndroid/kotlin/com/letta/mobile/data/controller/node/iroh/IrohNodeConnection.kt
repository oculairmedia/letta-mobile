package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.runtime.ConversationId
import computer.iroh.BiStream
import computer.iroh.Connection
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handles a single Iroh connection serving the App Server protocol.
 * 
 * Accepts two bi-directional streams (control + stream) and routes incoming
 * App Server v2 frames to the provided controller.
 */
class IrohNodeConnection(
    private val connection: Connection,
    private val controller: AppServerController,
    private val alpn: ByteArray,
) {
    suspend fun serve() = coroutineScope {
        try {
            val controlBiStream = connection.acceptBi()
            val streamBiStream = connection.acceptBi()

            val controlJob = launch {
                serveControlChannel(controlBiStream)
            }

            val streamJob = launch {
                serveStreamChannel(streamBiStream)
            }

            controlJob.join()
            streamJob.cancelAndJoin()
        } catch (e: Exception) {
            // Connection error - silently close
        } finally {
            runCatching { connection.close() }
        }
    }

    private suspend fun serveControlChannel(biStream: BiStream) = coroutineScope {
        val sendStream = biStream.send()

        try {
            val recvStream = biStream.recv()
            val buffer = mutableListOf<Byte>()
            
            while (true) {
                val chunk = runCatching {
                    recvStream.read(8192u)
                }.getOrNull() ?: break
                
                if (chunk.isEmpty()) break

                // Process incoming data line by line
                for (byte in chunk) {
                    if (byte == '\n'.code.toByte()) {
                        if (buffer.isNotEmpty()) {
                            val frameJson = buffer.toByteArray().decodeToString()
                            buffer.clear()
                            
                            // Handle the frame in a separate coroutine
                            launch {
                                val response = handleControlFrame(frameJson)
                                sendStream.write(response.toByteArray())
                                sendStream.write("\n".toByteArray())
                            }
                        }
                    } else {
                        buffer.add(byte)
                    }
                }
            }
        } finally {
            runCatching { sendStream.finish() }
        }
    }

    private suspend fun handleControlFrame(frameJson: String): String {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val element = json.parseToJsonElement(frameJson)
            val obj = element.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            val requestId = obj["request_id"]?.jsonPrimitive?.content
            
            when (type) {
                "runtime_start" -> {
                    // Parse the runtime_start command
                    val agentId = obj["agent_id"]?.jsonPrimitive?.content
                    val conversationId = obj["conversation_id"]?.jsonPrimitive?.content
                    val cwd = obj["cwd"]?.jsonPrimitive?.content
                    
                    if (requestId == null) {
                        """{"type":"runtime_start_response","success":false,"error":"request_id is required"}"""
                    } else if (agentId == null || conversationId == null) {
                        """{"type":"runtime_start_response","request_id":"$requestId","success":false,"error":"agent_id and conversation_id are required"}"""
                    } else {
                        // Try to start the runtime via the controller
                        try {
                            val runtime = controller.startRuntime(
                                agentId = AgentId(agentId),
                                conversationId = ConversationId(conversationId),
                                cwd = cwd,
                                mode = AppServerPermissionMode.Unrestricted,
                                recoverApprovals = false,
                                forceDeviceStatus = false,
                            )
                            
                            // Return success response with runtime scope
                            """{"type":"runtime_start_response","request_id":"$requestId","success":true,"runtime":{"agent_id":"${runtime.scope.agentId}","conversation_id":"${runtime.scope.conversationId}"}}"""
                        } catch (e: Exception) {
                            """{"type":"runtime_start_response","request_id":"$requestId","success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
                        }
                    }
                }
                "input" -> {
                    """{"type":"error","message":"input command not yet implemented in Iroh node"}"""
                }
                "sync" -> {
                    if (requestId == null) {
                        """{"type":"sync_response","success":false,"error":"request_id is required"}"""
                    } else {
                        """{"type":"sync_response","request_id":"$requestId","success":false,"error":"sync not yet implemented in Iroh node"}"""
                    }
                }
                else -> {
                    """{"type":"error","message":"Unknown command type: $type"}"""
                }
            }
        } catch (e: Exception) {
            """{"type":"error","message":"Failed to parse frame: ${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    private suspend fun serveStreamChannel(biStream: BiStream) = coroutineScope {
        val sendStream = biStream.send()

        try {
            val recvStream = biStream.recv()
            // The stream channel is receive-only from the server's perspective
            // Just wait for the client's ready signal (newline)
            runCatching { recvStream.read(1u) }
        } finally {
            runCatching { sendStream.finish() }
        }
    }
}
