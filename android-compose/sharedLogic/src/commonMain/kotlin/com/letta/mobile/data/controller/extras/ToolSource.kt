package com.letta.mobile.data.controller.extras

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A set of external tools that can change while the host runs (letta-mobile-s416w.27).
 *
 * [ExternalToolRegistry] reads [tools] whenever it advertises or dispatches, so a source changes
 * what agents are offered by publishing a new list; the registry's `toolsChanged` signal then makes
 * the controller re-advertise to every active runtime. A plugin host is one source per plugin; the
 * fixed tools a registry is built with (the host's canvas tools among them) are the static part.
 */
interface ToolSource {
    /** Unique within one registry; [ExternalToolRegistry.removeSource] takes it. */
    val id: String

    /** The tools this source offers right now. Names must not collide with the registry's fixed tools. */
    val tools: StateFlow<List<ExternalTool>>

    companion object {
        /** A source whose tools never change, e.g. a fixed set added after the registry was built. */
        fun static(id: String, tools: List<ExternalTool>): ToolSource = StaticToolSource(id, tools)
    }
}

/** A [ToolSource] whose list is published by its owner: [publish] replaces the whole set. */
class MutableToolSource(
    override val id: String,
    initial: List<ExternalTool> = emptyList(),
) : ToolSource {
    private val state = MutableStateFlow(initial)

    override val tools: StateFlow<List<ExternalTool>> = state.asStateFlow()

    fun publish(tools: List<ExternalTool>) {
        state.value = tools
    }
}

private class StaticToolSource(
    override val id: String,
    tools: List<ExternalTool>,
) : ToolSource {
    override val tools: StateFlow<List<ExternalTool>> = MutableStateFlow(tools).asStateFlow()
}

/**
 * Whether a change to the advertised tools is still on its way to the App Server.
 *
 * letta-code snapshots an agent's tool list when a turn starts, so even once the re-advertisement
 * has landed a change is visible from the agent's NEXT turn. [pending] is true from the change until
 * every active runtime has been re-advertised; a settings screen shows it as "takes effect on the
 * next conversation turn".
 */
data class ToolAdvertisementState(val pending: Boolean = false)
