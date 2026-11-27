package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.model.LettaConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the window's backend is the desktop's own node, decided in one place for everything that depends on it: the
 * node transport the shell binds, the session graph's routing, and the controls that act on this computer.
 *
 * A LOCAL config is served by the desktop's own node unless a developer App Server override is set; under the override
 * the desktop dials that App Server directly, so the session is a directly dialled one and nothing is local.
 */
internal object DesktopLocalNodeRoute {
    private val attachedState = MutableStateFlow(false)

    /**
     * Whether the window's active backend is the desktop's own node right now. Controls that act on this computer
     * (folder creation, the OS folder dialog, "show in file manager", the Windows automatic-dream switch) show only
     * while it is: a remote node's paths are another machine's, even while the bundled runtime runs in the background.
     */
    val attached: StateFlow<Boolean> = attachedState.asStateFlow()

    /** Whether the desktop reaches [config] through its own node: LOCAL, with no developer [appServerOverride]. */
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

    /** Publishes whether [config] is the active backend served by the desktop's own node. */
    fun publish(config: LettaConfig?, appServerOverride: String?) {
        attachedState.value = attaches(config, appServerOverride)
    }

    /**
     * Whether the files the shell browses are this computer's: the active backend is the desktop's own node
     * ([onLocalNode]), its bundled runtime is up ([runtimeActive]), and no directly dialled App Server (which the
     * workspace route prefers) is installed ([hasDirectClient]).
     */
    fun filesAreLocal(runtimeActive: Boolean, onLocalNode: Boolean, hasDirectClient: Boolean): Boolean =
        runtimeActive && onLocalNode && !hasDirectClient
}
