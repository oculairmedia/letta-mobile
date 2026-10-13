package com.letta.mobile.util

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.backend.NoAppServerRouteException
import com.letta.mobile.data.repository.api.ToolUnavailableException

fun mapErrorToUserMessage(e: Throwable, fallback: String = "Something went wrong"): String {
    return when (e) {
        is ToolUnavailableException -> when (e.reason) {
            ToolUnavailableException.Reason.NOT_FOUND -> "Not found. The tool may have been deleted."
            ToolUnavailableException.Reason.NOT_SUPPORTED -> "Not available over Iroh. ${e.message.orEmpty()}".trim()
        }
        is NoAppServerRouteException -> "Not available on this backend. The App Server has no route for this action yet."
        is ApiException -> when (e.code) {
            401 -> "Authentication failed. Check your Letta API key in Settings."
            403 -> "Access denied. This API key does not have permission for that Letta resource."
            404 -> "Not found. The item may have been deleted."
            408 -> "Request timed out. Try again."
            422 -> "Invalid request. Check your input."
            429 -> "Too many requests. Please wait a moment."
            in 500..599 -> "Server error. Try again later."
            else -> fallback
        }
        is java.net.UnknownHostException -> "No internet connection."
        is java.net.SocketTimeoutException -> "Connection timed out. Try again."
        is java.net.ConnectException -> "Cannot reach server. Check your connection."
        is java.io.IOException -> "Network error. Check your connection."
        else -> if (e.message.orEmpty().contains("capability_unavailable")) {
            "Not supported by this host over Iroh."
        } else {
            fallback
        }
    }
}
