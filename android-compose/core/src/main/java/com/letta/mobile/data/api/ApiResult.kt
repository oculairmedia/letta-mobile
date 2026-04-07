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
