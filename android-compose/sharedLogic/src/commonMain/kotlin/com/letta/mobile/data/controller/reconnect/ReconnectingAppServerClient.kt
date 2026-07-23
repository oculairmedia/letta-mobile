package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerConnectionState
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One connection generation minted by [ReconnectingAppServerClient.connect]:
 * a live client bound to a single dual-socket generation, the generation's
 * readiness state, and a close handle that tears the generation down.
 */
class AppServerClientGeneration(
    val client: AppServerClient,
    val connectionState: StateFlow<AppServerConnectionState>,
    val close: (reason: String?) -> Unit,
)

/** Lifecycle callbacks the supervisor fires around generation transitions. */
interface ReconnectingClientListener {
    /**
     * The active generation failed. Fired exactly once per generation, before
     * any reconnect attempt. Invalidate runtime caches here — canonical scopes
     * from the dead generation must not survive into the next one.
     */
    suspend fun onDisconnected(reason: String?) {}

    /**
     * A new generation reached Ready. Reattach runtimes (runtime_start),
     * re-register external tools, and sync with approval/device recovery here.
     * Throwing fails the recovery: the generation is closed and the supervisor
     * backs off and retries.
     */
    suspend fun onRecovered(client: AppServerClient) {}

    /** The supervisor stopped permanently (terminal failure or attempts exhausted). */
    suspend fun onGaveUp(reason: String?) {}
}

/** Supervisor lifecycle state, observable by UIs and gates. */
sealed interface ReconnectingClientState {
    data class Connecting(val attempt: Int) : ReconnectingClientState

    /**
     * Sockets are open and the reattach/re-register/sync recovery flow is
     * running. Client calls are admitted (the recovery flow itself issues
     * them through this client), but [ReconnectingAppServerClient.isConnected]
     * stays false until recovery completes.
     */
    data object Recovering : ReconnectingClientState

    /** Generation is Ready and post-recovery reconciliation completed. */
    data object Ready : ReconnectingClientState

    data class BackingOff(val attempt: Int, val delayMs: Long, val reason: String?) : ReconnectingClientState

    /** Terminal: policy/auth failure or the bounded attempt budget is exhausted. */
    data class GaveUp(val reason: String?) : ReconnectingClientState

    data object Stopped : ReconnectingClientState
}

class AppServerNotConnectedException(message: String) : IllegalStateException(message)

/**
 * [AppServerClient] facade that survives socket loss and App Server process
 * restarts by re-minting transport generations behind a stable reference
 * (lgns8.5). Controllers, turn engines, and admin handlers keep one client;
 * underneath, each generation is a fresh dual-socket transport whose pending
 * requests fail promptly on loss (the lgns8.3 registry's failAll) and whose
 * recovery runs the caller's reattach/sync flow before the client reports
 * Ready again.
 *
 * Loss intolerance: while no generation is Ready, every call fails immediately
 * with [AppServerNotConnectedException] — nothing queues, nothing blind-replays.
 * Whether a failed call may be retried is the caller's decision via
 * [com.letta.mobile.data.transport.appserver.AppServerCommandRetryClass];
 * ambiguous writes must reconcile against the committed
 * transcript first (see [AmbiguousTurnReconciler]).
 *
 * Terminal handshake failures (the transport's `Failed(terminal = true)`:
 * policy violation, auth rejection) stop the supervisor without retries —
 * retrying an unauthorized connection is never correct.
 */
class ReconnectingAppServerClient(
    private val connect: suspend () -> AppServerClientGeneration,
    private val listener: ReconnectingClientListener = object : ReconnectingClientListener {},
    private val backoff: FullJitterBackoff = FullJitterBackoff(),
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : AppServerClient {
    private val _state = MutableStateFlow<ReconnectingClientState>(ReconnectingClientState.Stopped)
    val state: StateFlow<ReconnectingClientState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = EVENT_BUFFER)
    override val events: Flow<AppServerReceivedFrame> = _events.asSharedFlow()
    override val isConnected: Flow<Boolean> = _state.map { it is ReconnectingClientState.Ready }

    private var current: AppServerClientGeneration? = null

    /**
     * Runs the supervise loop until a terminal state or scope cancellation.
     * Call once; the returned [Job] owns every generation minted by [connect].
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        var attempt = 0
        try {
            while (isActive) {
                _state.value = ReconnectingClientState.Connecting(attempt)
                val outcome = runGeneration(this, attempt)
                when (outcome) {
                    is GenerationOutcome.Served -> {
                        attempt = 0
                        listener.onDisconnected(outcome.reason)
                    }
                    is GenerationOutcome.NeverReady -> Unit
                    is GenerationOutcome.Terminal -> {
                        giveUp(outcome.reason)
                        return@launch
                    }
                }
                if (attempt >= maxAttempts) {
                    giveUp("reconnect attempts exhausted after $maxAttempts tries: ${outcome.reason}")
                    return@launch
                }
                val delayMs = backoff.delayMs(attempt, random)
                _state.value = ReconnectingClientState.BackingOff(attempt, delayMs, outcome.reason)
                Telemetry.event(
                    "AppServerReconnect",
                    "backoff",
                    "attempt" to attempt,
                    "delayMs" to delayMs,
                    "reason" to (outcome.reason ?: ""),
                )
                attempt += 1
                sleep(delayMs)
            }
        } finally {
            current?.close("supervisor stopped")
            current = null
            if (_state.value !is ReconnectingClientState.GaveUp) {
                _state.value = ReconnectingClientState.Stopped
            }
        }
    }

    private sealed interface GenerationOutcome {
        val reason: String?

        /** Generation reached Ready, recovery ran, and it later failed. */
        data class Served(override val reason: String?) : GenerationOutcome

        /** Generation failed retryably before serving (connect error, recovery error, never Ready). */
        data class NeverReady(override val reason: String?) : GenerationOutcome

        /** Generation failed terminally (policy/auth); do not retry. */
        data class Terminal(override val reason: String?) : GenerationOutcome
    }

    private suspend fun runGeneration(scope: CoroutineScope, attempt: Int): GenerationOutcome {
        val generation = try {
            connect()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return GenerationOutcome.NeverReady("connect failed: ${e.message}")
        }

        val settled = generation.connectionState.first {
            it is AppServerConnectionState.Ready || it is AppServerConnectionState.Failed
        }
        if (settled is AppServerConnectionState.Failed) {
            generation.close(settled.reason)
            return if (settled.terminal) {
                GenerationOutcome.Terminal(settled.reason)
            } else {
                GenerationOutcome.NeverReady(settled.reason)
            }
        }

        // Reattach runtimes / re-register tools / sync BEFORE reporting Ready,
        // so external callers never observe a half-recovered generation. The
        // recovery flow's own calls are admitted via the Recovering state.
        current = generation
        _state.value = ReconnectingClientState.Recovering
        try {
            listener.onRecovered(generation.client)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            current = null
            generation.close("recovery failed")
            return GenerationOutcome.NeverReady("recovery failed: ${e.message}")
        }

        val pipe = scope.launch { generation.client.events.collect { _events.emit(it) } }
        _state.value = ReconnectingClientState.Ready
        Telemetry.event("AppServerReconnect", "generation.ready", "attempt" to attempt)

        val failed = generation.connectionState.first { it is AppServerConnectionState.Failed }
            as AppServerConnectionState.Failed
        current = null
        pipe.cancel()
        generation.close(failed.reason)
        return if (failed.terminal) {
            GenerationOutcome.Terminal(failed.reason)
        } else {
            GenerationOutcome.Served(failed.reason)
        }
    }

    private suspend fun giveUp(reason: String?) {
        _state.value = ReconnectingClientState.GaveUp(reason)
        Telemetry.event("AppServerReconnect", "gave_up", "reason" to (reason ?: ""))
        listener.onGaveUp(reason)
    }

    private fun ready(): AppServerClient =
        current
            ?.takeIf {
                _state.value is ReconnectingClientState.Ready ||
                    _state.value is ReconnectingClientState.Recovering
            }
            ?.client
            ?: throw AppServerNotConnectedException(
                "App Server connection is not ready (state=${_state.value}); " +
                    "callers must not queue writes across generations",
            )

    override suspend fun auth(command: AppServerCommand.Auth): AppServerInboundFrame.AuthResponse =
        ready().auth(command)

    override suspend fun runtimeStart(
        command: AppServerCommand.RuntimeStart,
    ): AppServerInboundFrame.RuntimeStartResponse = ready().runtimeStart(command)

    override suspend fun input(command: AppServerCommand.Input) = ready().input(command)

    override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
        ready().sync(command)

    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
        ready().abort(command)

    override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
        ready().adminRpc(command)

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) =
        ready().sendExternalToolResponse(command)

    override suspend fun agentList(command: AppServerCommand.AgentList) = ready().agentList(command)

    override suspend fun agentRetrieve(command: AppServerCommand.AgentRetrieve) = ready().agentRetrieve(command)

    override suspend fun agentCreate(command: AppServerCommand.AgentCreate) = ready().agentCreate(command)

    override suspend fun agentUpdate(command: AppServerCommand.AgentUpdate) = ready().agentUpdate(command)

    override suspend fun agentDelete(command: AppServerCommand.AgentDelete) = ready().agentDelete(command)

    override suspend fun conversationList(command: AppServerCommand.ConversationList) = ready().conversationList(command)

    override suspend fun conversationRetrieve(command: AppServerCommand.ConversationRetrieve) =
        ready().conversationRetrieve(command)

    override suspend fun conversationCreate(command: AppServerCommand.ConversationCreate) =
        ready().conversationCreate(command)

    override suspend fun conversationUpdate(command: AppServerCommand.ConversationUpdate) =
        ready().conversationUpdate(command)

    override suspend fun conversationMessagesList(command: AppServerCommand.ConversationMessagesList) =
        ready().conversationMessagesList(command)

    override suspend fun listModels(command: AppServerCommand.ListModels) = ready().listModels(command)

    override suspend fun skillEnable(command: AppServerCommand.SkillEnable) = ready().skillEnable(command)

    override suspend fun skillDisable(command: AppServerCommand.SkillDisable) = ready().skillDisable(command)
    override suspend fun cronList(command: AppServerCommand.CronList) = ready().cronList(command)

    override suspend fun cronAdd(command: AppServerCommand.CronAdd) = ready().cronAdd(command)

    override suspend fun cronGet(command: AppServerCommand.CronGet) = ready().cronGet(command)

    override suspend fun cronRuns(command: AppServerCommand.CronRuns) = ready().cronRuns(command)

    override suspend fun cronTrigger(command: AppServerCommand.CronTrigger) = ready().cronTrigger(command)

    override suspend fun cronUpdate(command: AppServerCommand.CronUpdate) = ready().cronUpdate(command)

    override suspend fun cronDelete(command: AppServerCommand.CronDelete) = ready().cronDelete(command)

    override suspend fun cronDeleteAll(command: AppServerCommand.CronDeleteAll) = ready().cronDeleteAll(command)


    companion object {
        private const val EVENT_BUFFER = 256
        const val DEFAULT_MAX_ATTEMPTS = 10
    }
}
