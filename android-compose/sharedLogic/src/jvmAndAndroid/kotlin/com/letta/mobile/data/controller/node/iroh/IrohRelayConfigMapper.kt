package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.IrohRelayConfig
import computer.iroh.IrohException
import computer.iroh.RelayMode

/**
 * Converts platform-independent [IrohRelayConfig] to iroh's JVM [RelayMode] API.
 *
 * Internal utility for wiring the relay configuration into the iroh endpoint.
 */
internal object IrohRelayConfigMapper {
    /**
     * Converts [IrohRelayConfig] to iroh's [RelayMode].
     *
     * @throws IrohException if the relay URLs are invalid (e.g., malformed URL)
     */
    @Throws(IrohException::class)
    fun toRelayMode(config: IrohRelayConfig): RelayMode {
        return when (config) {
            is IrohRelayConfig.Default -> RelayMode.Companion.defaultMode()
            is IrohRelayConfig.Custom -> RelayMode.Companion.customFromUrls(config.urls)
            is IrohRelayConfig.Disabled -> RelayMode.Companion.disabled()
        }
    }
}
