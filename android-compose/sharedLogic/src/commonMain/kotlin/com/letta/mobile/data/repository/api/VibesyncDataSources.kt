package com.letta.mobile.data.repository.api

import io.ktor.utils.io.ByteReadChannel

/**
 * Opens the raw vibesync SSE byte stream used by
 * [com.letta.mobile.data.repository.CachedVibesyncEventStreamRepository].
 */
interface VibesyncEventStreamSource {
    suspend fun openStream(): ByteReadChannel
}

/**
 * Thrown when the backend reports 404 for `/api/events/stream`. Distinct from a
 * transient failure: the endpoint is not deployed and retrying serves no purpose.
 */
class VibesyncStreamEndpointUnavailableException : Exception()

interface VibesyncEventStreamLogger {
    fun info(message: String)

    fun info(message: String, error: Throwable)
}

object NoOpVibesyncEventStreamLogger : VibesyncEventStreamLogger {
    override fun info(message: String) = Unit

    override fun info(message: String, error: Throwable) = Unit
}
