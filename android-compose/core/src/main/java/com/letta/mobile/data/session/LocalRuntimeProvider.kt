package com.letta.mobile.data.session

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.TurnEngine

/**
 * Creates a local runtime backend for a LOCAL LettaConfig.
 *
 * Providers are selected by [supports]. This keeps embedded runtimes pluggable:
 * `local://` can point at the current default engine, while explicit schemes
 * such as `local-koog://` keep experiments addressable without replacing the
 * default path.
 */
interface LocalRuntimeProvider {
    val providerId: String
    val priority: Int

    fun supports(config: LettaConfig): Boolean

    fun descriptor(config: LettaConfig): BackendDescriptor

    fun turnEngine(config: LettaConfig): TurnEngine
}
