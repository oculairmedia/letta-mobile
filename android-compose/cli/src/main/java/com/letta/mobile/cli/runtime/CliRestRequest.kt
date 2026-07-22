package com.letta.mobile.cli.runtime

import com.github.ajalt.clikt.core.UsageError
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

internal data class CliQueryParam(
    val name: String,
    val value: String,
)

internal data class CliHeaderParam(
    val name: String,
    val value: String,
)

internal fun buildRestUrl(
    baseUrl: String,
    path: String,
    queryParams: List<CliQueryParam>,
): String {
    val normalizedBase = baseUrl.trimEnd('/')
    val normalizedPath = path.trimStart('/')
    val baseWithPath = if (normalizedPath.isBlank()) normalizedBase else "$normalizedBase/$normalizedPath"
    if (queryParams.isEmpty()) return baseWithPath
    val query = queryParams.joinToString("&") { param ->
        "${encodeUrlComponent(param.name)}=${encodeUrlComponent(param.value)}"
    }
    return "$baseWithPath?$query"
}

internal fun parseQueryParams(rawParams: List<String>): List<CliQueryParam> =
    rawParams.map { raw ->
        val index = raw.indexOf('=')
        if (index <= 0) {
            throw UsageError("--query values must be name=value")
        }
        val name = raw.substring(0, index).trim()
        val value = raw.substring(index + 1)
        if (name.isBlank()) {
            throw UsageError("--query values must include a non-empty name")
        }
        CliQueryParam(name, value)
    }

internal fun parseHeaderParams(rawParams: List<String>): List<CliHeaderParam> =
    rawParams.map { raw ->
        val index = raw.indexOf('=')
        if (index <= 0) {
            throw UsageError("--header values must be name=value")
        }
        val name = raw.substring(0, index).trim()
        val value = raw.substring(index + 1)
        if (name.isBlank()) {
            throw UsageError("--header values must include a non-empty name")
        }
        CliHeaderParam(name, value)
    }

internal fun resolveRequestBody(
    inlineBody: String?,
    bodyFile: String?,
): String? {
    if (inlineBody != null && bodyFile != null) {
        throw UsageError("Use only one of --body or --body-file")
    }
    return when {
        inlineBody != null -> inlineBody
        bodyFile != null -> try {
            String(Files.readAllBytes(Paths.get(bodyFile)), Charsets.UTF_8)
        } catch (error: IOException) {
            throw UsageError("Unable to read --body-file '$bodyFile': ${error.message}")
        } catch (error: RuntimeException) {
            throw UsageError("Unable to read --body-file '$bodyFile': ${error.message}")
        }
        else -> null
    }
}

internal fun encodeUrlComponent(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
