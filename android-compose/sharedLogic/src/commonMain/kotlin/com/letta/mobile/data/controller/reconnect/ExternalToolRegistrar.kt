package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope

/**
 * Hook for re-registering external tools after App Server restart.
 *
 * External tools are startup-bound on runtime_start, so they must be
 * re-registered when the App Server process restarts and we reconnect.
 *
 * This interface provides the re-registration hook that [ReconnectCoordinator]
 * calls after each runtime_start on reconnect. The actual implementation is
 * provided by the external-tools subsystem (bead .7).
 */
interface ExternalToolRegistrar {
    /**
     * Re-registers all external tools for the given runtime.
     *
     * This method is called after runtime_start on reconnect to restore the
     * external tool definitions that were previously registered before the
     * App Server restart.
     *
     * @param runtime The runtime scope to re-register tools for
     */
    suspend fun reRegisterAll(runtime: AppServerRuntimeScope)
}

/**
 * Default no-op implementation of [ExternalToolRegistrar].
 *
 * This is used when external tools are not enabled or when no external tools
 * have been registered.
 */
class NoOpExternalToolRegistrar : ExternalToolRegistrar {
    override suspend fun reRegisterAll(runtime: AppServerRuntimeScope) {
        // No-op: external tools not enabled
    }
}
