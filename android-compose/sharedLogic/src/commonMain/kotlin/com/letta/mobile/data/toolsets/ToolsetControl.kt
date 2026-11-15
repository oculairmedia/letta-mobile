package com.letta.mobile.data.toolsets

import com.letta.mobile.data.flow.DerivedStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/** The conversation whose host advertises and changes toolsets. */
data class ToolsetRuntime(val agentId: String, val conversationId: String)

/** One host-advertised choice; its id is sent unchanged when selected. */
data class ToolsetOption(val id: String, val displayName: String)

/** The host's preference and choices, with the current request's progress and error. */
data class ToolsetState(
    val available: List<ToolsetOption> = emptyList(),
    val preference: String? = null,
    val changing: Boolean = false,
    val error: String? = null,
)

/** No toolset status has been observed on this connection. */
val EmptyToolsets: StateFlow<Map<ToolsetRuntime, ToolsetState>> =
    MutableStateFlow<Map<ToolsetRuntime, ToolsetState>>(emptyMap()).asStateFlow()

/**
 * One open picker's toolset control. A selection sends once and waits for the same runtime's
 * device status to echo the preference. An RPC acknowledgement alone never changes selection;
 * the resolved preset can differ from the preference when the preference is auto.
 */
class ToolsetControl(
    private val scope: CoroutineScope,
    private val runtime: ToolsetRuntime,
    private val statuses: StateFlow<Map<ToolsetRuntime, ToolsetState>>,
    private val send: suspend (String) -> Unit,
) {
    private val request = MutableStateFlow(ToolsetState())

    /** Host-confirmed selection and options, with a visible failure when a request fails. */
    val state: StateFlow<ToolsetState> = DerivedStateFlow(
        changes = combine(statuses, request, ::project),
        current = { project(statuses.value, request.value) },
    )

    private fun project(hosts: Map<ToolsetRuntime, ToolsetState>, progress: ToolsetState): ToolsetState =
        (hosts[runtime] ?: ToolsetState()).copy(changing = progress.changing, error = progress.error)

    /** Starts one request; repeated clicks while it is pending and the current selection are ignored. */
    fun select(preference: String) {
        val current = statuses.value[runtime] ?: return
        if (request.value.changing) return
        if (current.preference == preference) return
        if (current.available.none { it.id == preference }) return
        request.value = ToolsetState(changing = true)
        scope.launch {
            request.value = runCatching { awaitConfirmation(preference) }.fold(
                onSuccess = { ToolsetState() },
                onFailure = { error ->
                    when (error) {
                        is TimeoutCancellationException -> ToolsetState(error = "The host did not confirm the toolset change")
                        is CancellationException -> throw error
                        else -> ToolsetState(error = error.message ?: "Could not change toolset")
                    }
                },
            )
        }
    }

    private suspend fun awaitConfirmation(preference: String) = coroutineScope {
        val confirmed = async(start = CoroutineStart.UNDISPATCHED) {
            statuses.first { it[runtime]?.preference == preference }
        }
        try {
            withTimeout(10.seconds) {
                send(preference)
                confirmed.await()
            }
        } finally {
            confirmed.cancel()
        }
    }
}
