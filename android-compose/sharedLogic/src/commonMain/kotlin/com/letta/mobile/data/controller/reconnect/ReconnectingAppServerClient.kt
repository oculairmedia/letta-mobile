package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerConnectionState
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInfoData
import com.letta.mobile.data.transport.appserver.AppServerProbeResult
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerWorkspaceCommand
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.selects.select
import kotlin.time.Duration.Companion.milliseconds

/**
 * One connection generation minted by [ReconnectingAppServerClient.connect]:
 * a live client bound to one connection generation, the generation's
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

    /**
     * Terminal: policy/auth failure or the bounded attempt budget is exhausted. [kind] says which,
     * so a UI can tell "fix your token" from "the server is down" (letta-mobile-bzvro.2, F02).
     */
    data class GaveUp(val reason: String?, val kind: GiveUpKind = GiveUpKind.Exhausted) : ReconnectingClientState

    data object Stopped : ReconnectingClientState
}

/** Why a [ReconnectingAppServerClient] stopped retrying. */
enum class GiveUpKind {
    /** The attempt budget ran out on retryable failures. */
    Exhausted,

    /** The transport reported a terminal close (policy violation, 401/403 upgrade, 426). */
    Rejected,

    /** The pre-attempt probe classified the server as rejecting the token. */
    Authentication,

    /** The pre-attempt probe classified the server as not an App Server this client can use. */
    Incompatible,
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
 *
 * [preflight] (letta-mobile-bzvro.2, F02) runs before every dial — typically the HTTP
 * `GET /app-server-info` [com.letta.mobile.data.transport.appserver.AppServerProbe], which never
 * opens a socket. An `Authentication` or `Incompatible` answer stops the loop with that
 * [GiveUpKind], so a revoked token is reported once instead of retried forever; `Unavailable`
 * backs off without dialling. [resumeNow] (F04) cuts the current backoff short after the system
 * wakes from sleep. [start] may be called again after a terminal stop (the user pressed Retry or
 * changed settings).
 */
class ReconnectingAppServerClient(
    private val connect: suspend () -> AppServerClientGeneration,
    private val listener: ReconnectingClientListener = object : ReconnectingClientListener {},
    private val telemetryComponent: String = "AppServerReconnect",
    private val backoff: FullJitterBackoff = FullJitterBackoff(),
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
    private val sleep: suspend (Long) -> Unit = { delay(it.milliseconds) },
    private val preflight: (suspend () -> AppServerProbeResult)? = null,
) : AppServerClient {
    private val _state = MutableStateFlow<ReconnectingClientState>(ReconnectingClientState.Stopped)
    val state: StateFlow<ReconnectingClientState> = _state.asStateFlow()
    private val emptyServerInfoFlow: StateFlow<AppServerInfoData?> = MutableStateFlow(null)

    private val _events = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = EVENT_BUFFER)
    override val events: Flow<AppServerReceivedFrame> = _events.asSharedFlow()
    override val isConnected: Flow<Boolean> = _state.map { it is ReconnectingClientState.Ready }

    /**
     * Discovered server capabilities from the current generation (lgns8.24).
     *
     * Returns the [AppServerInfoData] from the active generation's client, or
     * null if no generation is ready or discovery hasn't completed yet.
     */
    override val serverInfo: StateFlow<AppServerInfoData?>
        get() = current
            ?.takeIf { _state.value is ReconnectingClientState.Ready || _state.value is ReconnectingClientState.Recovering }
            ?.client?.serverInfo
            ?: emptyServerInfoFlow

    private var current: AppServerClientGeneration? = null
    /**
     * lgns8.22.4: mirrors DefaultAppServerController.connectionGeneration.
     * Allocated with getAndIncrement at each pipe start so every connected
     * transport (including failed recovery) gets a unique stamp. Controller
     * failGeneration must run on NeverReady-after-pipe via onDisconnected.
     */
    private val pipeGenerationSeq = kotlinx.atomicfu.atomic(0L)

    /** Set by [resumeNow]; consumed by the next backoff, which then redials at once. */
    private val resumeRequested = kotlinx.atomicfu.atomic(false)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    /**
     * The system woke from sleep or the network came back (F04): skip the rest of the current
     * backoff, reset the attempt counter, and redial now. A no-op while a generation is Ready;
     * if that generation turns out to be dead, the next drop already redials from attempt 0.
     */
    fun resumeNow() {
        if (_state.value is ReconnectingClientState.Ready) return
        resumeRequested.value = true
        wakeups.trySend(Unit)
        Telemetry.event(telemetryComponent, "resume_now", "state" to _state.value.toString())
    }

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
                        giveUp(outcome.reason, outcome.kind)
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
                    telemetryComponent,
                    "backoff",
                    "attempt" to attempt,
                    "delayMs" to delayMs,
                    "reason" to (outcome.reason ?: ""),
                )
                attempt += 1
                if (backOffOrResume(delayMs)) attempt = 0
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
        data class Terminal(
            override val reason: String?,
            val kind: GiveUpKind = GiveUpKind.Rejected,
        ) : GenerationOutcome
    }

    /** Sleeps [delayMs] unless [resumeNow] interrupts it. Returns true when resumed. */
    private suspend fun backOffOrResume(delayMs: Long): Boolean {
        if (resumeRequested.getAndSet(false)) return true
        while (wakeups.tryReceive().isSuccess) Unit
        val resumed = coroutineScope {
            val sleeper = async { sleep(delayMs) }
            select {
                sleeper.onAwait { false }
                wakeups.onReceive {
                    sleeper.cancel()
                    true
                }
            }
        }
        val flagged = resumeRequested.getAndSet(false)
        return resumed || flagged
    }

    /** Null when the dial may proceed; otherwise the outcome that replaces it. */
    private suspend fun runPreflight(): GenerationOutcome? {
        val probe = preflight ?: return null
        val result = try {
            probe()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppServerProbeResult.Unavailable(e.message ?: "probe failed")
        }
        return when (result) {
            is AppServerProbeResult.Ok -> null
            is AppServerProbeResult.Authentication -> GenerationOutcome.Terminal(result.detail, GiveUpKind.Authentication)
            is AppServerProbeResult.Incompatible -> GenerationOutcome.Terminal(result.reason, GiveUpKind.Incompatible)
            is AppServerProbeResult.Unavailable -> GenerationOutcome.NeverReady("probe: ${result.detail}")
        }
    }

    private suspend fun runGeneration(scope: CoroutineScope, attempt: Int): GenerationOutcome {
        runPreflight()?.let { return it }
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

        // Pipe generation events BEFORE recovery so collectors (skills catalog,
        // subagent registry) observe runtime_start / sync / skills_updated
        // snapshots emitted during onRecovered. Starting the pipe after recovery
        // drops those frames and leaves skill.list / subagents empty until an
        // unrelated later event.
        val pipeGeneration = pipeGenerationSeq.getAndIncrement()
        val pipe = scope.launch {
            generation.client.events.collect { frame ->
                _events.emit(frame.copy(connectionGeneration = pipeGeneration))
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
            pipe.cancel()
            throw e
        } catch (e: Exception) {
            pipe.cancel()
            current = null
            generation.close("recovery failed")
            // Pipe already stamped this generation; invalidate it so a replay on
            // the retry is not classified Duplicate under the same stamp.
            listener.onDisconnected("recovery failed: ${e.message}")
            return GenerationOutcome.NeverReady("recovery failed: ${e.message}")
        }

        _state.value = ReconnectingClientState.Ready
        resumeRequested.value = false
        Telemetry.event(telemetryComponent, "generation.ready", "attempt" to attempt)

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

    private suspend fun giveUp(reason: String?, kind: GiveUpKind = GiveUpKind.Exhausted) {
        _state.value = ReconnectingClientState.GaveUp(reason, kind)
        Telemetry.event(telemetryComponent, "gave_up", "reason" to (reason ?: ""), "kind" to kind.name)
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

    override suspend fun appServerInfo(command: AppServerCommand.AppServerInfo): AppServerInboundFrame.AppServerInfoResponse =
        ready().appServerInfo(command)

    override suspend fun runtimeStart(
        command: AppServerCommand.RuntimeStart,
    ): AppServerInboundFrame.RuntimeStartResponse = ready().runtimeStart(command)

    override suspend fun input(command: AppServerCommand.Input) = ready().input(command)

    override suspend fun inputAwaitingAcceptance(command: AppServerCommand.Input): AppServerInboundFrame.InputAccepted =
        ready().inputAwaitingAcceptance(command)

    override suspend fun changeDeviceState(command: AppServerCommand.ChangeDeviceState) = ready().changeDeviceState(command)

    override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
        ready().sync(command)

    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
        ready().abort(command)

    override suspend fun resumeQueue(command: AppServerCommand.ResumeQueue): AppServerInboundFrame.ResumeQueueResponse =
        ready().resumeQueue(command)

    override suspend fun removeQueueItem(
        command: AppServerCommand.RemoveQueueItem,
    ): AppServerInboundFrame.RemoveQueueItemResponse = ready().removeQueueItem(command)

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

    override suspend fun conversationFork(command: com.letta.mobile.data.transport.appserver.AppServerConversationFork) =
        ready().conversationFork(command)

    override suspend fun conversationMessagesList(command: AppServerCommand.ConversationMessagesList) =
        ready().conversationMessagesList(command)

    override suspend fun conversationCompact(command: AppServerCommand.ConversationCompact) =
        ready().conversationCompact(command)

    override suspend fun listModels(command: AppServerCommand.ListModels) = ready().listModels(command)

    override suspend fun skillEnable(command: AppServerCommand.SkillEnable) = ready().skillEnable(command)

    override suspend fun listConnectProviders(command: AppServerCommand.ListConnectProviders) =
        ready().listConnectProviders(command)

    override suspend fun connectProvider(command: AppServerCommand.ConnectProvider) = ready().connectProvider(command)

    override suspend fun disconnectProvider(command: AppServerCommand.DisconnectProvider) =
        ready().disconnectProvider(command)

    override suspend fun updateModel(command: AppServerCommand.UpdateModel) = ready().updateModel(command)

    override suspend fun skillDisable(command: AppServerCommand.SkillDisable) = ready().skillDisable(command)

    // bfooy.5: without these forwarders the interface default throws
    // UnsupportedOperationException, so the wrapper's block.update_agent /
    // block.create_agent / block.delete_agent (routed through DualLane's
    // runtime lane, a ReconnectingAppServerClient) could never reach the socket.
    override suspend fun writeMemoryFile(command: AppServerCommand.WriteMemoryFile) = ready().writeMemoryFile(command)

    override suspend fun deleteMemoryFile(command: AppServerCommand.DeleteMemoryFile) = ready().deleteMemoryFile(command)

    override suspend fun cronList(command: AppServerCommand.CronList) = ready().cronList(command)

    override suspend fun cronAdd(command: AppServerCommand.CronAdd) = ready().cronAdd(command)

    override suspend fun cronGet(command: AppServerCommand.CronGet) = ready().cronGet(command)

    override suspend fun cronRuns(command: AppServerCommand.CronRuns) = ready().cronRuns(command)

    override suspend fun cronTrigger(command: AppServerCommand.CronTrigger) = ready().cronTrigger(command)

    override suspend fun cronUpdate(command: AppServerCommand.CronUpdate) = ready().cronUpdate(command)

    override suspend fun cronDelete(command: AppServerCommand.CronDelete) = ready().cronDelete(command)

    override suspend fun cronDeleteAll(command: AppServerCommand.CronDeleteAll) = ready().cronDeleteAll(command)

    override suspend fun getReflectionSettings(command: AppServerCommand.GetReflectionSettings) = ready().getReflectionSettings(command)

    override suspend fun setReflectionSettings(command: AppServerCommand.SetReflectionSettings) = ready().setReflectionSettings(command)

    override suspend fun workspaceRequest(command: AppServerWorkspaceCommand) = ready().workspaceRequest(command)

    // lgns8.23: admitted while Recovering so ChannelRestoreCoordinator can run
    // inside onRecovered, on the same generation whose socket ingress binds to.

    override suspend fun channelsList(command: AppServerCommand.ChannelsList) = ready().channelsList(command)

    override suspend fun channelAccountsList(command: AppServerCommand.ChannelAccountsList) =
        ready().channelAccountsList(command)

    override suspend fun channelStart(command: AppServerCommand.ChannelStart) = ready().channelStart(command)

    override suspend fun channelAccountUpdate(command: AppServerCommand.ChannelAccountUpdate) =
        ready().channelAccountUpdate(command)


    companion object {
        private const val EVENT_BUFFER = 256
        const val DEFAULT_MAX_ATTEMPTS = 10
    }
}
