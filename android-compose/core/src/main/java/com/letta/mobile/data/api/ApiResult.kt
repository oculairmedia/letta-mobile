package com.letta.mobile.data.api

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText

sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class ApiError(val code: Int, val message: String) : ApiResult<Nothing>
    data class NetworkError(val exception: Throwable) : ApiResult<Nothing>
}

class ApiException(val code: Int, message: String) : Exception(message)

/**
 * Thrown by [MessageApi.streamConversation] when the conversation has no active run
 * to subscribe to. The caller (e.g. TimelineSyncLoop's subscriber coroutine) is
 * expected to back off and retry — a run will eventually start when any client
 * posts into the conversation. See letta-mobile-mge5.
 */
class NoActiveRunException(val conversationId: String) :
    Exception("No active runs for conversation $conversationId")

suspend inline fun <reified T> safeApiCall(crossinline call: suspend () -> HttpResponse): ApiResult<T> {
    return try {
        val response = call()
        if (response.status.value in 200..299) {
            ApiResult.Success(response.body<T>())
        } else {
            val errorBody = try { response.bodyAsText() } catch (_: Exception) { "Unknown error" }
            ApiResult.ApiError(response.status.value, errorBody)
        }
    } catch (e: java.io.IOException) {
        ApiResult.NetworkError(e)
    } catch (e: io.ktor.serialization.JsonConvertException) {
        ApiResult.NetworkError(e)
    } catch (e: Exception) {
        ApiResult.NetworkError(e)
    }
}
