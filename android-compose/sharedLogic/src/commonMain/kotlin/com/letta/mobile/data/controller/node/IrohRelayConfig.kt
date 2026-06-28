package com.letta.mobile.data.controller.node

/**
 * Configuration for iroh relay behavior.
 *
 * Iroh uses relays to facilitate connections when direct connectivity is not possible
 * (e.g., behind NAT or firewall). By default, iroh uses the public n0 relay infrastructure.
 * For privacy-focused deployments, you can run your own relay server.
 *
 * See https://docs.iroh.computer/add-a-relay for how to set up a self-hosted relay.
 *
 * @see IrohRelayConfig.Default for development with public n0 relays
 * @see IrohRelayConfig.Custom for production with a self-hosted relay
 * @see IrohRelayConfig.Disabled for direct-only connections (no relay)
 */
sealed class IrohRelayConfig {
    /**
     * Use the default iroh n0 public relay infrastructure.
     * Suitable for development and testing.
     */
    data object Default : IrohRelayConfig()

    /**
     * Use a custom/self-hosted relay server.
     *
     * To run your own relay:
     * 1. Deploy an iroh relay server (see https://docs.iroh.computer/add-a-relay)
     * 2. Point your nodes at it with Custom(listOf("https://your-relay.example.com"))
     *
     * @param urls List of relay server URLs (e.g., ["https://relay.example.com:443"])
     */
    data class Custom(val urls: List<String>) : IrohRelayConfig() {
        init {
            require(urls.isNotEmpty()) { "Custom relay config must have at least one URL" }
        }
    }

    /**
     * Disable relay entirely — direct connections only.
     * Nodes will only be able to connect if they can establish a direct QUIC connection.
     * This will fail if both peers are behind NAT without hole-punching support.
     */
    data object Disabled : IrohRelayConfig()
}
