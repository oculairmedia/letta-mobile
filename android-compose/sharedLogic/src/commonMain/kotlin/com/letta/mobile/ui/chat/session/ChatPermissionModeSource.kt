package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.chat.runtime.PermissionModeGateway
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * A chat host's permission-mode extension point, read by a session port for
 * the composer's mode chip. [permissionMode] emits null where the conversation's runtime cannot read
 * or change its mode (no chip).
 */
interface ChatPermissionModeSource {
    val permissionMode: Flow<ChatPermissionModeUiState?>

    /** Asks the conversation's runtime for [mode]; the chip shows it pending until the server echoes it. */
    fun setPermissionMode(mode: AppServerPermissionMode)
}

/** One conversation's runtime on a gateway that may govern permission modes. */
data class ChatPermissionModeTarget(
    val gateway: PermissionModeGateway,
    val runtime: AppServerRuntimeScope,
)

/**
 * The shared [ChatPermissionModeSource] over a [PermissionModeGateway]: the chip follows whichever
 * conversation [targets] names, re-syncs a relaying host's mode on open, and
 * names the runtime it governs. [current] is the target a mode change goes to.
 */
class GatewayPermissionModes(
    private val scope: CoroutineScope,
    targets: Flow<ChatPermissionModeTarget?>,
    private val current: () -> ChatPermissionModeTarget?,
) : ChatPermissionModeSource {
    @OptIn(ExperimentalCoroutinesApi::class)
    override val permissionMode: Flow<ChatPermissionModeUiState?> =
        targets.distinctUntilChanged().flatMapLatest { target -> target?.let(::chip) ?: flowOf(null) }

    override fun setPermissionMode(mode: AppServerPermissionMode) {
        val target = current() ?: return
        if (target.gateway.permissionModes == null) return
        scope.launch { target.gateway.setPermissionMode(target.runtime, mode) }
    }

    private fun chip(target: ChatPermissionModeTarget): Flow<ChatPermissionModeUiState?> {
        val modes = target.gateway.permissionModes ?: return flowOf(null)
        return modes.observe(target.runtime)
            .map { it.toUiState(runtime = target.gateway.permissionModeRuntime) }
            .onStart { scope.launch { target.gateway.syncPermissionMode(target.runtime) } }
    }
}
