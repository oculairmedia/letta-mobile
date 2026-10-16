package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.ModeChangeResult
import com.letta.mobile.data.runtime.PermissionModeRegistry
import com.letta.mobile.data.runtime.setPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope

/**
 * letta-mobile-bzvro.13, ckmub.17.2 (moved from desktop's App Server gateway): the
 * [PermissionModeGateway] of a session that runs the App Server turn engine itself. A change goes
 * through [permissionModes] (shown pending, recorded once the server echoes it) and out as the
 * engine's `change_device_state`; desktop's gateway and the web's chat page both bind it.
 */
class EnginePermissionModes(
    private val engine: AppServerTurnEngine?,
    override val permissionModes: PermissionModeRegistry?,
) : PermissionModeGateway {
    override suspend fun setPermissionMode(runtime: AppServerRuntimeScope, mode: AppServerPermissionMode): Boolean =
        permissionModes?.change(runtime, mode) { requested ->
            engine?.setPermissionMode(runtime, requested) ?: ModeChangeResult.Unconfirmed
        } ?: false
}
