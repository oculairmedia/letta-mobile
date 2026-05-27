package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.letta.mobile.cli.runtime.buildRestUrl
import com.letta.mobile.cli.runtime.cliHttpClient
import com.letta.mobile.cli.runtime.executeJsonRestRequest
import com.letta.mobile.cli.runtime.formatJsonResponse
import com.letta.mobile.cli.runtime.parseHeaderParams
import com.letta.mobile.cli.runtime.parseQueryParams
import com.letta.mobile.cli.runtime.resolveRequestBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking

internal class RestCommand : CliktCommand(
    name = "rest",
) {
    override fun run() = Unit
}

internal abstract class RestVerbCommand(
    name: String,
    private val verb: String,
) : AdminShimCommand(
    name = name,
    help = "$verb a Letta REST endpoint.",
) {
    private val path by argument("path")
    private val query by option("--query", "-q", help = "Query parameter as name=value. Repeatable.").multiple()
    private val headers by option("--header", "-H", help = "Request header as name=value. Repeatable.").multiple()
    private val body by option("--body", help = "Raw JSON request body.")
    private val bodyFile by option("--body-file", help = "Path to a JSON request body file.")
    private val compact by option("--compact", help = "Print compact JSON response.").flag(default = false)
    private val raw by option("--raw", help = "Print response body without JSON formatting.").flag(default = false)
    private val allowError by option("--allow-error", help = "Do not fail the process on non-2xx HTTP responses.").flag(default = false)

    override fun run() = runBlocking {
        val requestBody = resolveRequestBody(body, bodyFile)
        val url = buildRestUrl(baseUrl, path, parseQueryParams(query))
        val client = cliHttpClient()
        try {
            val response = client.executeJsonRestRequest(verb, url, token, requestBody, parseHeaderParams(headers))
            val text = response.bodyAsText()
            if (response.status.value !in 200..299) {
                System.err.println("[rest] HTTP ${response.status.value} ${response.status.description}")
                if (!allowError) {
                    if (text.isNotBlank()) System.err.println(text)
                    throw IllegalStateException("REST request failed: HTTP ${response.status.value}")
                }
            }
            formatJsonResponse(text, compact, raw)?.let(::println)
        } finally {
            client.close()
        }
        Unit
    }
}

internal class RestGetCommand : RestVerbCommand("get", "GET")
internal class RestPostCommand : RestVerbCommand("post", "POST")
internal class RestPutCommand : RestVerbCommand("put", "PUT")
internal class RestPatchCommand : RestVerbCommand("patch", "PATCH")
internal class RestDeleteCommand : RestVerbCommand("delete", "DELETE")
