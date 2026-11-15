@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.transport.appserver.AppServerBackgroundProcess
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.snapshot
import kotlin.uuid.Uuid
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Projects runtime snapshots into the existing composer shelf on every host. Requests are coalesced
 * until the host removes the process; acceptance alone never manufactures a stopped state.
 * Switching runtimes cancels the prior controls and retires callbacks retained by a previous view.
 */
fun backgroundProcessHost(
    scope: CoroutineScope,
    frames: Flow<AppServerReceivedFrame>,
    runtimes: Flow<AppServerRuntimeScope?>,
    stop: suspend (AppServerRuntimeScope, String) -> Unit,
): StateFlow<ChatComposerHostInputs> {
    val host = MutableStateFlow(ChatComposerHostInputs())
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        runtimes.distinctUntilChanged().collectLatest { runtime ->
            host.value = ChatComposerHostInputs()
            if (runtime == null) return@collectLatest
            followBackgroundProcesses(host, frames, runtime, stop)
        }
    }
    return host
}

/** Follows both the selected host and conversation, retiring controls when either changes. */
fun backgroundProcessHost(
    scope: CoroutineScope,
    targets: Flow<Pair<ChatGateway, AppServerRuntimeScope>?>,
    current: () -> Pair<ChatGateway, AppServerRuntimeScope>?,
): StateFlow<ChatComposerHostInputs> {
    val host = MutableStateFlow(ChatComposerHostInputs())
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        targets.distinctUntilChanged().collectLatest { target ->
            host.value = ChatComposerHostInputs()
            if (target == null) return@collectLatest
            coroutineScope {
                val (gateway, runtime) = target
                backgroundProcessHost(this, gateway.deviceStatusFrames, flowOf(runtime)) { selected, id ->
                    if (current() == target) gateway.stopBackgroundProcess(selected, id)
                }.collect { host.value = it }
            }
        }
    }
    return host
}

/** The process shelf of a fixed conversation, bound to its captured gateway. */
fun backgroundProcessHost(
    scope: CoroutineScope,
    gateway: ChatGateway,
    target: TimelineChatTarget,
): StateFlow<ChatComposerHostInputs> = backgroundProcessHost(
    scope, gateway.deviceStatusFrames,
    flowOf(AppServerRuntimeScope(target.agentId, target.conversationId)),
    gateway.stopBackgroundProcess,
)

private suspend fun followBackgroundProcesses(
    host: MutableStateFlow<ChatComposerHostInputs>,
    frames: Flow<AppServerReceivedFrame>,
    runtime: AppServerRuntimeScope,
    stop: suspend (AppServerRuntimeScope, String) -> Unit,
) = coroutineScope {
    val lock = SynchronizedObject()
    val pending = mutableMapOf<String, Uuid>()
    var active = true
    var generation: Long? = null
    var processes = emptyList<AppServerBackgroundProcess>()
    val stopProcess: (String) -> Unit = stopProcess@{ id ->
        val request = synchronized(lock) { claimBackgroundProcess(id, active, processes, pending) } ?: return@stopProcess
        launch {
            stopBackgroundProcessRequest({ stop(runtime, id) }) { error ->
                synchronized(lock) {
                    if (pending[id] == request) {
                        if (error != null) pending.remove(id)
                        host.value = host.value.copy(backgroundProcessError = error)
                    }
                }
            }
        }
    }
    try {
        frames.collect { received ->
            val status = received.deviceStatusFor(runtime) ?: return@collect
            synchronized(lock) {
                val incoming = received.connectionGeneration
                if (isOlderConnectionSnapshot(incoming, generation)) return@synchronized
                if (incoming != generation) {
                    pending.clear()
                    generation = incoming
                }
                processes = status.snapshot.backgroundProcesses
                pending.keys.retainAll(processes.map { it.id }.toSet())
                host.value = host.value.copy(
                    backgroundProcesses = processes,
                    stopBackgroundProcess = stopProcess,
                    clearBackgroundProcessError = {
                        synchronized(lock) { if (active) host.value = host.value.copy(backgroundProcessError = null) }
                    },
                )
            }
        }
    } finally {
        synchronized(lock) {
            active = false
            pending.clear()
        }
    }
}

private suspend fun stopBackgroundProcessRequest(stop: suspend () -> Unit, complete: (String?) -> Unit) {
    runCatching { stop() }
        .onSuccess { complete(null) }
        .onFailure { failure ->
            if (failure is CancellationException || failure !is Exception) throw failure
            complete(failure.message ?: "Could not stop background process")
        }
}

private fun claimBackgroundProcess(
    id: String,
    active: Boolean,
    processes: List<AppServerBackgroundProcess>,
    pending: MutableMap<String, Uuid>,
): Uuid? {
    if (!active) return null
    if (id in pending) return null
    if (processes.none { it.id == id }) return null
    return Uuid.random().also { pending[id] = it }
}

private fun isOlderConnectionSnapshot(incoming: Long?, current: Long?): Boolean =
    current != null && (incoming == null || incoming < current)

private fun AppServerReceivedFrame.deviceStatusFor(runtime: AppServerRuntimeScope): AppServerInboundFrame.UpdateDeviceStatus? =
    (frame as? AppServerInboundFrame.UpdateDeviceStatus)?.takeIf { it.runtime == runtime }
