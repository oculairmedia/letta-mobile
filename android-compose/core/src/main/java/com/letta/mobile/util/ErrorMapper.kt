package com.letta.mobile.util

import com.letta.mobile.data.api.ApiException

fun mapErrorToUserMessage(e: Exception, fallback: String = "Something went wrong"): String {
    return when (e) {
        is ApiException -> when (e.code) {
            401 -> "Authentication failed. Check your API key."
            403 -> "Access denied. You don't have permission."
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
        else -> fallback
    }
}
