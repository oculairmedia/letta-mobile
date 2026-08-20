package com.letta.mobile.util

import com.letta.mobile.data.api.ApiException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorMapperTest {

    @Test
    fun testApiException401() {
        val e = ApiException(401, "msg")
        assertEquals("Authentication failed. Check your Letta API key in Settings.", mapErrorToUserMessage(e))
    }

    @Test
    fun testApiException403() {
        val e = ApiException(403, "msg")
        assertEquals("Access denied. This API key does not have permission for that Letta resource.", mapErrorToUserMessage(e))
    }

    @Test
    fun testApiException404() {
        val e = ApiException(404, "msg")
        assertEquals("Not found. The item may have been deleted.", mapErrorToUserMessage(e))
    }

    @Test
    fun testApiException408() {
        val e = ApiException(408, "msg")
        assertEquals("Request timed out. Try again.", mapErrorToUserMessage(e))
    }

    @Test
    fun testApiException422() {
        val e = ApiException(422, "msg")
        assertEquals("Invalid request. Check your input.", mapErrorToUserMessage(e))
    }

    @Test
    fun testApiException429() {
        val e = ApiException(429, "msg")
        assertEquals("Too many requests. Please wait a moment.", mapErrorToUserMessage(e))
    }

    @Test
    fun testApiException500() {
        val e = ApiException(500, "msg")
        assertEquals("Server error. Try again later.", mapErrorToUserMessage(e))
    }

    @Test
    fun testApiException599() {
        val e = ApiException(599, "msg")
        assertEquals("Server error. Try again later.", mapErrorToUserMessage(e))
    }
    
    @Test
    fun testApiExceptionUnknown() {
        val e = ApiException(418, "I'm a teapot")
        assertEquals("Something went wrong", mapErrorToUserMessage(e))
        assertEquals("Custom fallback", mapErrorToUserMessage(e, "Custom fallback"))
    }

    @Test
    fun testUnknownHostException() {
        val e = UnknownHostException("msg")
        assertEquals("No internet connection.", mapErrorToUserMessage(e))
    }

    @Test
    fun testSocketTimeoutException() {
        val e = SocketTimeoutException("msg")
        assertEquals("Connection timed out. Try again.", mapErrorToUserMessage(e))
    }

    @Test
    fun testConnectException() {
        val e = ConnectException("msg")
        assertEquals("Cannot reach server. Check your connection.", mapErrorToUserMessage(e))
    }

    @Test
    fun testIOException() {
        val e = IOException("msg")
        assertEquals("Network error. Check your connection.", mapErrorToUserMessage(e))
    }

    @Test
    fun testUnknownException() {
        val e = IllegalStateException("msg")
        assertEquals("Something went wrong", mapErrorToUserMessage(e))
        assertEquals("Custom fallback", mapErrorToUserMessage(e, "Custom fallback"))
    }
}
