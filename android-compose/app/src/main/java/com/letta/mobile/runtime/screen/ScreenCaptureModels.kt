package com.letta.mobile.runtime.screen

import kotlinx.serialization.Serializable

@Serializable
data class ScreenCaptureResult(
    val imageBase64: String,
    val width: Int,
    val height: Int,
    val mimeType: String,
)

interface ScreenCaptureProvider {
    fun captureOwnWindow(maxDimension: Int = AndroidScreenCaptureProvider.DEFAULT_MAX_DIMENSION): ScreenCaptureResult
}
