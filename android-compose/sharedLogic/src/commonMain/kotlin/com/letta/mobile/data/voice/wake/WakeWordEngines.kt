package com.letta.mobile.data.voice.wake

import com.letta.mobile.data.voice.WakeWordEngineId
import com.letta.mobile.data.voice.wake.assets.ReadyWakeWordAssets
import com.letta.mobile.data.voice.wake.assets.WakeWordAssetState
import com.letta.mobile.data.voice.wake.assets.WakeWordAssets

/** An engine and the provisioning of its assets. */
data class WakeWordBinding(
    /** The engine. */
    val engine: WakeWordEngine,
    /** Its assets. */
    val assets: WakeWordAssets,
)

/** An engine selection passed over, and why. */
data class SkippedEngine(
    /** The engine. */
    val id: WakeWordEngineId,
    /** Why it was skipped. */
    val reason: WakeWordOpenFailure,
)

/** The result of [WakeWordEngines.open]. */
sealed interface WakeWordSelection {
    /** Engines passed over before the result, in the order tried. */
    val skipped: List<SkippedEngine>

    /** An engine opened. */
    data class Opened(
        /** The engine that opened. */
        val binding: WakeWordBinding,
        /** Its session. */
        val session: WakeWordSession,
        override val skipped: List<SkippedEngine>,
    ) : WakeWordSelection

    /** No engine could open; voice continues with push-to-talk. */
    data class Unavailable(override val skipped: List<SkippedEngine>) : WakeWordSelection
}

/**
 * The registry of wake-word engines. The preferred engine is tried first, then the rest in
 * registration order. An engine whose assets are not provisioned is skipped as ASSET_MISSING
 * without downloading anything; an engine that fails to open is skipped with its reason.
 */
class WakeWordEngines {
    private val bindings = mutableListOf<WakeWordBinding>()

    /** Registers [binding]; a second registration under the same id replaces the first. */
    fun register(binding: WakeWordBinding) {
        bindings.removeAll { it.engine.descriptor.id == binding.engine.descriptor.id }
        bindings += binding
    }

    /** The registered engines' ids, in registration order. */
    val ids: List<WakeWordEngineId> get() = bindings.map { it.engine.descriptor.id }

    /** Opens the first engine that can run, preferring [preferred]. */
    suspend fun open(preferred: WakeWordEngineId?): WakeWordSelection {
        val skipped = mutableListOf<SkippedEngine>()
        for (binding in ordered(preferred)) {
            when (val result = tryOpen(binding)) {
                is WakeWordSessionResult.Opened -> return WakeWordSelection.Opened(binding, result.session, skipped)
                is WakeWordSessionResult.Failed -> skipped += SkippedEngine(binding.engine.descriptor.id, result.failure)
            }
        }
        return WakeWordSelection.Unavailable(skipped)
    }

    private fun ordered(preferred: WakeWordEngineId?): List<WakeWordBinding> =
        bindings.sortedBy { if (it.engine.descriptor.id == preferred) 0 else 1 }

    private suspend fun tryOpen(binding: WakeWordBinding): WakeWordSessionResult {
        val required = binding.engine.descriptor.requiredAssets
        if (required.isEmpty()) return binding.engine.open(ReadyWakeWordAssets())
        val ready = (binding.assets.state.value as? WakeWordAssetState.Ready)?.assets
        if (ready?.covers(required) != true) return WakeWordSessionResult.Failed(WakeWordOpenFailure.ASSET_MISSING)
        return binding.engine.open(ready)
    }
}
