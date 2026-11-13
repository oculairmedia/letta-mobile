package com.letta.mobile.data.voice.session

import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceLoopConfig
import com.letta.mobile.data.voice.activation.VoiceActivationKind
import com.letta.mobile.data.voice.loop.VoiceLoop
import com.letta.mobile.data.voice.loop.VoiceLoopRuntime
import com.letta.mobile.data.voice.responder.VoiceChatSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/** What a press of the voice button did. */
enum class VoicePress {
    /** Voice mode started listening. */
    STARTED,

    /** Voice mode was on and is stopping. */
    STOPPED,

    /** The setup is incomplete: open the Voice settings. Nothing started. */
    OPEN_SETUP,

    /** The microphone permission is being asked for; voice mode starts if it is granted. */
    ASKED_PERMISSION,

    /** The controller was shut down; nothing happened. */
    IGNORED,
}

/**
 * Voice mode for one chat page: builds and owns one [VoiceLoop] at a time over the platform's audio,
 * the stored endpoint setup and the chat session [sessions] opens, and publishes a small [state] for
 * the chat bar.
 *
 * A press checks the setup first (a gap opens the Voice settings and starts nothing), then the
 * microphone permission (a refusal settles in [VoiceModeState.NeedsMicPermission]), then starts. The
 * microphone and the speaker are released when: [stop] is called (a second press), the session's
 * conversation changes to another one, or [shutdown] runs (the page leaves, the window closes; hosts
 * also call [stop] when the app goes to the background). A failure keeps the session so [retry] can
 * hand the ear its kept capture; the loop has already closed the microphone by then.
 *
 * Commands are applied in order by one coroutine in [runtime]'s scope and dispatcher; [sessions] is
 * opened on the thread that presses (the UI thread), so a host may read its own UI state there.
 * Nothing here logs audio, transcripts, addresses or keys.
 */
class VoiceModeController(
    private val sessions: VoiceChatSessions,
    private val store: VoiceSetupStore,
    private val engines: VoiceEngineFactory,
    private val microphone: MicrophoneAccess,
    private val runtime: VoiceLoopRuntime,
    private val config: VoiceLoopConfig = VoiceLoopConfig(),
) {
    // overflow: one consumer applying presses in order; dropping one could lose a stop.
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow<VoiceModeState>(VoiceModeState.Off)
    private val closing = MutableStateFlow(false)
    private var live: LiveVoice? = null
    private val processor: Job = runtime.scope.launch(runtime.dispatcher) {
        for (command in commands) {
            apply(command)
            if (command is Command.Close) break
        }
    }

    /** What the chat bar shows. */
    val state: StateFlow<VoiceModeState> = mutableState.asStateFlow()

    init {
        runtime.scope.launch(runtime.dispatcher) { store.setup.collect { offer(Command.SetupChanged) } }
    }

    /**
     * The voice button: stops voice mode while it is [VoiceModeState.active]; otherwise checks the
     * setup, then the microphone permission, then starts. Call from the UI thread.
     */
    fun press(): VoicePress = when {
        closing.value -> VoicePress.IGNORED
        mutableState.value.active -> VoicePress.STOPPED.also { stop() }
        else -> pressToStart()
    }

    /** Setup first, then the microphone permission, then start. */
    private fun pressToStart(): VoicePress {
        store.setup.value.gap?.let { gap ->
            offer(Command.Idle(VoiceModeState.NeedsSetup(gap)))
            return VoicePress.OPEN_SETUP
        }
        if (microphone.granted()) return VoicePress.STARTED.also { start() }
        microphone.request { allowed -> if (allowed) start() else offer(Command.Idle(VoiceModeState.NeedsMicPermission)) }
        return VoicePress.ASKED_PERMISSION
    }

    /** Starts listening (after a failure, starts over), skipping the permission check. */
    fun start() = offer(Command.Start(sessions.open()))

    /** Stops voice mode and releases the microphone and the speaker. */
    fun stop() {
        commands.trySend(Command.Stop)
    }

    /** After a failure: retries the kept capture, or listens again. */
    fun retry() = offer(Command.Retry(sessions.open()))

    /**
     * Stops voice mode for good: releases the engines (each wait bounded by the loop's release
     * budget) and ends the controller's coroutine. Finishes even if the caller is cancelled, and when
     * the owner's scope was already cancelled it releases in the caller. Later presses are ignored.
     */
    suspend fun shutdown(): Unit = withContext(NonCancellable) {
        closing.value = true
        val done = CompletableDeferred<Unit>()
        if (commands.trySend(Command.Close(done)).isSuccess) {
            processor.invokeOnCompletion { done.complete(Unit) }
            done.await()
        }
        processor.join()
        // The owner's scope ended the processor before it saw the close: release here instead.
        end(VoiceModeState.Off)
        commands.close()
    }

    private fun offer(command: Command) {
        if (!closing.value) commands.trySend(command)
    }

    private suspend fun apply(command: Command) {
        when (command) {
            is Command.Start -> begin(command.session)
            Command.Stop -> end(VoiceModeState.Off)
            is Command.Retry -> retryOrBegin(command.session)
            Command.SetupChanged -> clearResolvedSetup()
            is Command.Idle -> showIdle(command.state)
            is Command.Close -> end(VoiceModeState.Off).also { command.done.complete(Unit) }
        }
    }


    /** Shows why nothing started, unless a session started meanwhile. */
    private fun showIdle(next: VoiceModeState) {
        if (live == null) mutableState.value = next
    }

    private fun clearResolvedSetup() {
        val waitingForSetup = mutableState.value is VoiceModeState.NeedsSetup && live == null
        if (waitingForSetup && store.setup.value.gap == null) mutableState.value = VoiceModeState.Off
    }

    private suspend fun retryOrBegin(session: VoiceChatSession?) {
        val current = live ?: return begin(session)
        val loop = current.loop
        if (loop.state.value.retryableCapture != null) loop.retryCapture() else loop.activate(VoiceActivationKind.TAP)
    }

    private suspend fun begin(opened: VoiceChatSession?) {
        val running = live
        if (running != null && running.loop.state.value.failure == null) return
        end(VoiceModeState.Off)
        val setup = store.setup.value
        setup.gap?.let { gap ->
            mutableState.value = VoiceModeState.NeedsSetup(gap)
            return
        }
        val session = opened?.let(::BoundVoiceChatSession)
        if (session == null) {
            mutableState.value = VoiceModeState.NeedsConversation
            return
        }
        val started = launchLoop(session, setup) ?: return
        live = started
        mutableState.value = VoiceModeState.Listening
        started.loop.activate(VoiceActivationKind.TAP)
    }

    private fun launchLoop(session: BoundVoiceChatSession, setup: VoiceSetup): LiveVoice? {
        val job = SupervisorJob(runtime.scope.coroutineContext[Job])
        val scope = runtime.scope + job + runtime.dispatcher
        val request = VoiceEngineRequest(session, setup, store.apiKey(), { store.setup.value.grants }, scope)
        val built = runCatching { engines.create(request) }
        val error = built.exceptionOrNull()
        if (error is CancellationException) throw error
        val set = built.getOrNull()
        if (set == null) {
            job.cancel()
            mutableState.value = VoiceModeState.Error(VoiceErrorCode.PREPARATION_FAILED)
            return null
        }
        val loop = VoiceLoop(set.engines, config, runtime.copy(scope = scope))
        val follower = scope.launch { loop.state.collect { mutableState.value = VoiceModeProjection.of(it, store.setup.value.gap) } }
        val watcher = scope.launch { session.awaitMove { commands.send(Command.Stop) } }
        return LiveVoice(loop, set, job, listOf(follower, watcher))
    }

    /** Releases the live session, if any, and then publishes [next]. Completes even when cancelled. */
    private suspend fun end(next: VoiceModeState) = withContext(NonCancellable) {
        val current = live
        live = null
        if (current != null) {
            current.watchers.forEach { it.cancel() }
            current.loop.shutdown()
            current.set.release()
            current.job.cancelAndJoin()
        }
        mutableState.value = next
    }

    private class LiveVoice(val loop: VoiceLoop, val set: VoiceEngineSet, val job: Job, val watchers: List<Job>)

    private sealed interface Command {
        class Start(val session: VoiceChatSession?) : Command

        data object Stop : Command

        class Retry(val session: VoiceChatSession?) : Command

        data object SetupChanged : Command

        data class Idle(val state: VoiceModeState) : Command

        class Close(val done: CompletableDeferred<Unit>) : Command
    }
}

/**
 * A chat session bound to the conversation it was first seen in. Once the session has moved to
 * another conversation, its [stopRun] does nothing: stopping "the page's run" then would stop a run
 * in the conversation the person switched to.
 */
internal class BoundVoiceChatSession(private val delegate: VoiceChatSession) : VoiceChatSession by delegate {
    @Volatile
    private var bound = delegate.state.value.conversation

    /** True once the session reports a conversation other than the one it was bound to. */
    fun moved(): Boolean {
        val now = delegate.state.value.conversation
        if (bound == null) bound = now
        return bound != null && now != bound
    }

    override fun stopRun() {
        if (!moved()) delegate.stopRun()
    }

    /** Calls [onMove] each time the session reports a conversation other than its own. */
    suspend fun awaitMove(onMove: suspend () -> Unit) {
        state.map { it.conversation }.distinctUntilChanged().collect { if (moved()) onMove() }
    }
}
