package com.letta.mobile.data.model

/**
 * What the backend settings form says about a server URL before it is saved,
 * the same on every client. Null means the URL is acceptable for the mode.
 */
object BackendServerUrlCheck {
    private val NETWORK_SCHEMES = listOf("https://", "http://", "wss://", "ws://")

    fun problem(mode: LettaConfig.Mode, serverUrl: String): String? {
        val url = serverUrl.trim()
        return when (mode) {
            // The local runtime has no network address to check.
            LettaConfig.Mode.LOCAL -> null
            LettaConfig.Mode.SELF_HOSTED -> selfHostedProblem(url)
        }
    }

    private fun selfHostedProblem(url: String): String? = when {
        url.isEmpty() -> "Enter the App Server address."
        isIrohBackendUrl(url) -> IrohServerAddress.problem(url)
        hasNetworkScheme(url) -> null
        IrohServerAddress.looksLikeIrohIdentity(url) ->
            "This looks like an Iroh endpoint id or ticket: enter it as iroh://$url"
        else -> SCHEME_PROBLEM
    }

    private fun hasNetworkScheme(url: String): Boolean = NETWORK_SCHEMES.any { url.startsWith(it, ignoreCase = true) }

    private const val SCHEME_PROBLEM = "The server URL must start with iroh://, https://, http://, wss:// or ws://."
}
