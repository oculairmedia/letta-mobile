package com.letta.mobile.runtime.local.modelcatalog

import io.ktor.http.Url

private const val HUGGING_FACE_HOST = "huggingface.co"

fun String.isHuggingFaceUrl(): Boolean = runCatching {
    val host = Url(this).host.lowercase()
    host == HUGGING_FACE_HOST || host.endsWith(".$HUGGING_FACE_HOST")
}.getOrDefault(false)

fun embeddedModelDownloadFailureMessage(url: String, statusCode: Int): String =
    if (url.isHuggingFaceUrl() && statusCode in setOf(401, 403)) {
        "Requires Hugging Face access token."
    } else {
        "Download failed with HTTP $statusCode."
    }
