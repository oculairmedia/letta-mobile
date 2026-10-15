package com.letta.mobile.data.api

sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class ApiError(val code: Int, val message: String) : ApiResult<Nothing>
    data class NetworkError(val exception: Throwable) : ApiResult<Nothing>
}

/**
 * Thrown by [MessageApi.streamConversation] when the conversation has no active run
 * to subscribe to. The caller (e.g. TimelineSyncLoop's subscriber coroutine) is
 * expected to back off and retry — a run will eventually start when any client
 * posts into the conversation. See letta-mobile-mge5.
 *
 * letta-mobile-t8q7: this is hot-path control flow — every idle poll on every
 * background subscriber throws this. fillInStackTrace is overridden to skip
 * the JVM stack walk; with N warmup subscribers each idling at the 32 s cap,
 * the stack-capture allocation was producing GC bursts that landed between
 * frames and dropped taps on the timeline / Settings.
 */
class NoActiveRunException(val conversationId: String) :
    Exception("No active runs for conversation $conversationId") {
    override fun fillInStackTrace(): Throwable = this
}
