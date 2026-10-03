package com.letta.mobile.plugin.testkit

import com.letta.mobile.plugin.api.PluginHostException
import com.letta.mobile.plugin.api.PluginHttpClient
import com.letta.mobile.plugin.api.PluginHttpResponse
import com.letta.mobile.plugin.testkit.ConformanceManifest.Companion.NET_CONNECT
import java.net.URI

/**
 * The host's HTTP client as a jvm plugin sees it (plan section 3.3): it needs `net:connect`, and a
 * request goes out only to an origin the manifest's `net.connect` names (exactly, or under a
 * `*.` wildcard). Every request is recorded, refused or not.
 */
internal class AllowlistHttpClient(
    private val manifest: ConformanceManifest,
    private val guard: (String) -> Unit,
    private val violation: (ConformanceRule, String) -> Unit,
    private val recorded: MutableList<PluginHttpRequest>,
    private val handler: suspend (PluginHttpRequest) -> PluginHttpResponse,
) : PluginHttpClient {
    override suspend fun get(url: String, headers: Map<String, String>): PluginHttpResponse =
        send(PluginHttpRequest("GET", url, headers))

    override suspend fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String>): PluginHttpResponse =
        send(PluginHttpRequest("POST", url, headers, contentType))

    private suspend fun send(request: PluginHttpRequest): PluginHttpResponse {
        guard("httpClient")
        recorded += request
        refusal(request.url)?.let { (code, reason) ->
            violation(ConformanceRule.CAPABILITY, reason)
            throw PluginHostException(code, reason)
        }
        return handler(request)
    }

    private fun refusal(url: String): Pair<String, String>? = when {
        !manifest.has(NET_CONNECT) -> PluginHostException.CAPABILITY_DENIED to "the network needs the $NET_CONNECT capability"
        !allowed(originOf(url)) -> PluginHostException.ORIGIN_DENIED to "${originOf(url)} is not one of the manifest's net.connect origins"
        else -> null
    }

    private fun allowed(origin: String): Boolean = manifest.net.connect.any { declared -> matches(declared, origin) }

    private fun matches(declared: String, origin: String): Boolean {
        if (declared == origin) return true
        val scheme = declared.substringBefore("://")
        val wildcardHost = declared.substringAfter("://").removePrefix(WILDCARD)
        return declared.contains("://$WILDCARD") && origin.startsWith("$scheme://") && origin.endsWith(".$wildcardHost")
    }

    private fun originOf(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}" + (if (uri.port == -1) "" else ":${uri.port}")
    }.getOrDefault(url)

    private companion object {
        const val WILDCARD = "*."
    }
}
