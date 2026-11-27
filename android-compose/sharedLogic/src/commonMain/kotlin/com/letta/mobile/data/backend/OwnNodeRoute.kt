package com.letta.mobile.data.backend

import com.letta.mobile.data.model.LettaConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a host's window is attached to the node in the host's own process, decided in one place for everything
 * that depends on it: the node transport the shell binds, the session graph's routing, and the controls that act on
 * this computer.
 *
 * A LOCAL config is served by the host's own node unless a developer App Server override is set (each call takes the
 * override address this process was started with, or null); under the override the host dials that App Server
 * directly, so the session is a directly dialled one and nothing is local. A host keeps one instance for its windows.
 */
open class OwnNodeRoute {
    private val attachedState = MutableStateFlow(false)

    /**
     * Whether the window's active backend is the host's own node right now, as last [publish]ed. Controls that act on
     * this computer (folder creation, the OS folder dialog, "show in file manager", an automatic-dream switch for the
     * bundled runtime) show only while it is: a remote node's paths are another machine's, even while the bundled
     * runtime runs in the background.
     */
    val attached: StateFlow<Boolean> = attachedState.asStateFlow()

    /** Whether the host reaches [config] through its own node: LOCAL, with no developer [appServerOverride]. */
    fun attaches(config: LettaConfig?, appServerOverride: String?): Boolean =
        config?.mode == LettaConfig.Mode.LOCAL && appServerOverride == null

    /**
     * [config] as the session graph routes it. Under the developer [appServerOverride] a LOCAL config is the App
     * Server that override names, dialled directly, so the graph binds the directly dialled session's row (agents and
     * memory over that client) instead of a node session no transport serves.
     */
    fun graphConfig(config: LettaConfig?, appServerOverride: String?): LettaConfig? =
        if (config?.mode == LettaConfig.Mode.LOCAL && appServerOverride != null) {
            config.copy(mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = appServerOverride)
        } else {
            config
        }

    /** Publishes whether [config], the window's active backend, is served by the host's own node. */
    fun publish(config: LettaConfig?, appServerOverride: String?) {
        attachedState.value = attaches(config, appServerOverride)
    }

    companion object {
        /**
         * Whether the files the shell browses are this computer's: the active backend is the host's own node
         * ([onOwnNode]), its bundled runtime is up ([runtimeActive]), and no directly dialled App Server (which a
         * workspace route prefers) is installed ([hasDirectClient]).
         */
        fun filesAreLocal(runtimeActive: Boolean, onOwnNode: Boolean, hasDirectClient: Boolean): Boolean =
            runtimeActive && onOwnNode && !hasDirectClient
    }
}
