package com.letta.mobile.runtime.clipboard

import kotlinx.serialization.Serializable

@Serializable
data class ClipboardReadResponse(
    val success: Boolean,
    val text: String?,
    val reason: String,
)

@Serializable
data class ClipboardWriteResponse(
    val success: Boolean,
    val reason: String,
)

interface ClipboardProvider {
    fun readText(): ClipboardReadResponse
    fun writeText(text: String): ClipboardWriteResponse
}
