package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.SendStream
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
        } finally {
            runCatching { connection.close() }
        }
    }

    private suspend fun serveControlChannel(biStream: BiStream) = coroutineScope {
        val sendStream = biStream.send()

        try {
            val recvStream = biStream.recv()
            while (true) {
                val chunk = runCatching {
                    recvStream.read(8192u)
                }.getOrNull() ?: break
                
                if (chunk.isEmpty()) break

                launch {
                    val responseJson = """{"type":"error","message":"Server routing not implemented"}"""
                    sendStream.write(responseJson.toByteArray())
                    sendStream.write("\n".toByteArray())
                }
            }
        } finally {
            runCatching { sendStream.finish() }
        }
    }

    private suspend fun serveStreamChannel(biStream: BiStream) = coroutineScope {
        val sendStream = biStream.send()

        try {
            val recvStream = biStream.recv()
            runCatching { recvStream.read(1u) }
        } finally {
            runCatching { sendStream.finish() }
        }
    }
}
