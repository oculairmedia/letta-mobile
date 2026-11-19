package com.letta.mobile.data.voice.session

import com.letta.mobile.data.flow.DerivedStateFlow
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceLoopConfig
import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.activation.VoiceActivationKind
import com.letta.mobile.data.voice.audio.pcm.VoiceLevelMeter
import com.letta.mobile.data.voice.consent.VoiceEndpointGrantSource
import com.letta.mobile.data.voice.host.client.SharedVoiceSetup
import com.letta.mobile.data.voice.host.client.VoiceRoute
import com.letta.mobile.data.voice.local.LocalHandoverReason
import com.letta.mobile.data.voice.loop.VoiceLoop
import com.letta.mobile.data.voice.loop.VoiceLoopRuntime
import com.letta.mobile.data.voice.output.VoiceSessionControls
import com.letta.mobile.data.voice.responder.VoiceChatSession
import com.letta.mobile.data.voice.session.telemetry.VoiceTelemetry
import com.letta.mobile.data.voice.session.telemetry.VoiceTelemetrySink
import com.letta.mobile.data.voice.session.telemetry.diagnosticKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/** What a press of the voice button did. */
enum class VoicePress {
    /** Voice mode started listening. */
    STARTED,

    /** Voice mode was on and is stopping. */
    STOPPED,

    /** The reply was being spoken: it stops and voice mode listens again (it stays on). */
    INTERRUPTED,

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
 * microphone permission (a refusal settles in [VoiceModeState.NeedsMicPermission]), then starts. A
 * press while the reply is being spoken only interrupts it and voice mode listens on;
 * where the microphone is not echo-cancelled (and the person has not asked to interrupt by speaking)
 * that press is the only way to interrupt, since the ear ignores the microphone while the app speaks
 * ([bargeInByVoice]). The microphone and the speaker are released when: [stop] is called (a press
 * while not speaking), the session's conversation changes to another one, or [shutdown] runs (the
 * page leaves, the window closes; hosts also call [stop] when the app goes to the background). A failure keeps the session so [retry] can
 * hand the ear its kept capture; the loop has already closed the microphone by then.
 *
 * A failed utterance (not delivered, unattributed, a failed run, an answer that timed out, a failed
 * segment of speech) does not end voice mode: the loop keeps listening and the state shows the
 * failure ([VoiceModeState.TurnFailed]) until the person says something new. Only the microphone or
 * its permission going away, a missing setup, an engine that ends or will not let go, or a stop end it.
 *
 * Where the connected host shares a speech server ([shared], over the host connection), sessions go
 * through it: whether setup is missing is then decided from the shared setup (and, where the session
 * listens through the server, this device's one-time audio acknowledgement), never from this
 * device's own server, which is used only when the host does not serve the voice-endpoint contract,
 * has no setup, or the person chose this device's own server. A failed read of the shared setup keeps
 * the last one known, so a host setup in force never silently gives way to this device's own.
 *
 * Commands are applied in order by one coroutine in [runtime]'s scope and dispatcher; [sessions] is
 * opened on the thread that presses (the UI thread), so a host may read its own UI state there.
 * Nothing here logs audio, transcripts, addresses or keys; every `VoiceMode` event is timed on
 * [timeSource] from the session's `mode.started` and goes to [telemetrySink].
 */
class VoiceModeController(
    private val sessions: VoiceChatSessions,
    private val store: VoiceSetupStore,
    private val engines: VoiceEngineFactory,
    private val microphone: MicrophoneAccess,
    private val runtime: VoiceLoopRuntime,
    private val config: VoiceLoopConfig = VoiceLoopConfig(),
    timeSource: TimeSource = TimeSource.Monotonic,
    telemetrySink: VoiceTelemetrySink = VoiceTelemetrySink.None,
    private val shared: SharedVoiceSetup? = null,
) {
    private val telemetry = VoiceTelemetry(timeSource, telemetrySink)
    private val routing = SessionRouting(store, engines, shared)

    /**
     * The voice card's controls and what voice mode is saying: the current reply's spoken
     * segments, the current turn and its clock, microphone and speaker mute, and the speech speed
     * (stored in the setup). Mutes are cleared when a session ends.
     */
    val controls: VoiceSessionControls = VoiceSessionControls(
        timeSource,
        DerivedStateFlow(store.setup.map { it.tuning.speechSpeed }) { store.setup.value.tuning.speechSpeed },
        store::setSpeechSpeed,
    )
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

    private val mutableBargeInByVoice = MutableStateFlow(false)

    /**
     * Whether speaking interrupts the reply in the current (or last) session: false where voice mode
     * runs half-duplex and only a press interrupts. The status strip words its hint from it.
     */
    val bargeInByVoice: StateFlow<Boolean> = mutableBargeInByVoice.asStateFlow()

    private val mutableMicrophoneLevel = MutableStateFlow(0f)

    /**
     * How loud the microphone is right now, smoothed, from 0 (silence or a quiet room) to 1, at
     * most about 30 updates a second; for the voice band's visual. It follows the levels the ear
     * measures on the frames it already analyses, reads 0 while the microphone is closed, and reads
     * 0 at once while a half-duplex ear ignores the microphone during the reply. A number only.
     */
    val microphoneLevel: StateFlow<Float> = mutableMicrophoneLevel.asStateFlow()

    private val mutablePlaybackLevel = MutableStateFlow(0f)

    /**
     * How loud the reply being played is right now, smoothed, from 0 to 1, at most about 30 updates
     * a second: the level of each chunk of audio as it is written to the speaker, 0 between and
     * after replies. A number only.
     */
    val playbackLevel: StateFlow<Float> = mutablePlaybackLevel.asStateFlow()

    private val mutablePartialTranscript = MutableStateFlow<VoiceText?>(null)

    /**
     * The words the person is saying right now, as the ear recognises them (ears that recognise as
     * they hear, like the device's own recogniser, report them; others never do); null between
     * turns and once voice mode stops. For display only: they are the person's words, never log them.
     */
    val partialTranscript: StateFlow<VoiceText?> = mutablePartialTranscript.asStateFlow()

    private val mutableHandover = MutableStateFlow<LocalHandoverReason?>(null)

    /**
     * Why listening moved from the device's recogniser to the speech server in the current session,
     * until the session ends; null otherwise. The chat bar tells the person once.
     */
    val handover: StateFlow<LocalHandoverReason?> = mutableHandover.asStateFlow()

    init {
        runtime.scope.launch(runtime.dispatcher) { store.setup.collect { offer(Command.SetupChanged) } }
        shared?.let { host ->
            runtime.scope.launch(runtime.dispatcher) { host.state.collect { offer(Command.SetupChanged) } }
            // Learn the shared setup up front, so the first press already knows whether the host has one.
            runtime.scope.launch(runtime.dispatcher) { host.refresh() }
        }
    }

    /**
     * The voice button (and the status strip's Interrupt): while the reply is being spoken it stops
     * it at once (the speaker is flushed, not drained) and voice mode listens again, without
     * leaving voice mode; nothing happens if the reply has already ended when the press is applied.
     * Otherwise it stops voice mode while it is [VoiceModeState.active], or checks the setup, then
     * the microphone permission, then starts. Call from the UI thread.
     */
    fun press(): VoicePress = when {
        closing.value -> VoicePress.IGNORED
        mutableState.value == VoiceModeState.Speaking -> VoicePress.INTERRUPTED.also { offer(Command.Interrupt) }
        mutableState.value.active -> VoicePress.STOPPED.also { stop() }
        else -> pressToStart()
    }

    /** Setup first, then the microphone permission, then start. */
    private fun pressToStart(): VoicePress {
        val setup = store.setup.value
        // While the host's shared setup is still being read, starting decides once it is known.
        val gap = if (routing.pending) null else routing.gapNow(setup)
        gap?.let {
            offer(Command.Idle(VoiceModeState.NeedsSetup(it)))
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
        commands.trySend(Command.Stop(STOP_REQUESTED))
    }

    /** After a failure: retries the kept capture, sends again the utterance whose answer failed, or listens again. */
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
            is Command.Stop -> end(VoiceModeState.Off, command.reason)
            is Command.Retry -> retryOrBegin(command.session)
            is Command.Close -> end(VoiceModeState.Off, SHUTDOWN).also { command.done.complete(Unit) }
            else -> applyQuick(command)
        }
    }

    /** The commands that neither start nor end a session. */
    private fun applyQuick(command: Command) {
        when (command) {
            Command.Interrupt -> live?.interruptSpeech()
            // A prompt for a setup that is now complete clears, unless a session started meanwhile.
            Command.SetupChanged -> if (live == null && routing.completes(mutableState.value)) mutableState.value = VoiceModeState.Off
            // Shows why nothing started, unless a session started meanwhile.
            is Command.Idle -> if (live == null) mutableState.value = command.state
            else -> Unit
        }
    }



    private suspend fun retryOrBegin(session: VoiceChatSession?) {
        val current = live ?: return begin(session)
        val loop = current.loop
        val state = loop.state.value
        when {
            state.retryableCapture != null -> loop.retryCapture()
            state.replyRetryable -> loop.retryReply()
            else -> loop.activate(VoiceActivationKind.TAP)
        }
    }

    private suspend fun begin(opened: VoiceChatSession?) {
        val running = live
        if (running != null && running.loop.state.value.failure == null) return
        end(VoiceModeState.Off, RESTARTED)
        val setup = store.setup.value
        val route = routing.resolve(setup)
        routing.gap(setup, route)?.let { gap ->
            mutableState.value = VoiceModeState.NeedsSetup(gap)
            return
        }
        val session = opened?.let(::BoundVoiceChatSession)
        if (session == null) {
            mutableState.value = VoiceModeState.NeedsConversation
            return
        }
        val started = launchLoop(session, setup, route) ?: return
        live = started
        mutableBargeInByVoice.value = started.set.bargeInByVoice
        telemetry.started(echoCancelled = started.set.echoCancelled, bargeInByVoice = started.set.bargeInByVoice)
        mutableState.value = VoiceModeState.Listening
        started.loop.activate(VoiceActivationKind.TAP)
    }

    private fun launchLoop(session: BoundVoiceChatSession, setup: VoiceSetup, route: VoiceRoute): LiveVoice? {
        val job = SupervisorJob(runtime.scope.coroutineContext[Job])
        val scope = runtime.scope + job + runtime.dispatcher
        val request = engineRequest(session, setup, route, scope)
        val built = runCatching { engines.create(request) }
        val error = built.exceptionOrNull()
        if (error is CancellationException) throw error
        val set = built.getOrNull()
        if (set == null) {
            job.cancel()
            mutableState.value = VoiceModeState.Error(VoiceErrorCode.PREPARATION_FAILED)
            return null
        }
        val loopConfig = config.copy(voiceInterrupts = config.voiceInterrupts && set.bargeInByVoice)
        val (ear, mouth) = controls.engines(set.engines.ear, set.engines.mouth)
        val loop = VoiceLoop(set.engines.copy(ear = ear, mouth = mouth), loopConfig, runtime.copy(scope = scope, observer = telemetry.loop))
        val follower = scope.launch {
            loop.state.collect { mutableState.value = VoiceModeProjection.of(it, routing.failureGap(setup, route, set)) }
        }
        val tracer = scope.launch { loop.state.distinctUntilChangedBy { it.diagnosticKey() }.collect(telemetry::loopState) }
        val watcher = scope.launch { session.awaitMove { commands.send(Command.Stop(CONVERSATION_CHANGED)) } }
        // Half-duplex, the ear ignores the microphone while the reply plays: the level reads 0 at
        // once rather than decaying from the last thing the person said.
        val muteWhileSpeaking = !set.bargeInByVoice
        val hearing = scope.launch {
            publishLevel(
                loop.state.map { state ->
                    if (muteWhileSpeaking && state.phase == VoicePhase.SPEAKING) VoiceLevelMeter.MUTED else state.inputLevel.value
                },
                mutableMicrophoneLevel,
            )
        }
        val playing = scope.launch { publishLevel(loop.state.map { it.outputLevel.value }, mutablePlaybackLevel) }
        val words = scope.launch {
            loop.state.map { it.partialTranscript }.distinctUntilChanged().collect { mutablePartialTranscript.value = it }
        }
        val handedOver = scope.launch { set.handover.collect { reason -> if (reason != null) mutableHandover.value = reason } }
        val controlled = scope.launch { controls.follow(loop.state) }
        return LiveVoice(loop, set, job, listOf(follower, tracer, watcher, hearing, playing, words, handedOver, controlled))
    }

    /**
     * The request for one session's engines on [route]. Through the host this device's own key is
     * never handed to the engines; the grants are read live, with this device's audio acknowledgement.
     */
    private fun engineRequest(
        session: VoiceChatSession,
        setup: VoiceSetup,
        route: VoiceRoute,
        scope: CoroutineScope,
    ): VoiceEngineRequest {
        val apiKey = if (route is VoiceRoute.Host) null else store.apiKey()
        val grants = VoiceEndpointGrantSource { store.setup.value.effectiveGrants }
        return VoiceEngineRequest(session, setup, apiKey, grants, scope, VoiceSessionServices(telemetry, controls), route)
    }

    /** Releases the live session, if any (reporting [reason]), and then publishes [next]. Completes even when cancelled. */
    private suspend fun end(next: VoiceModeState, reason: String = SHUTDOWN) = withContext(NonCancellable) {
        val current = live
        live = null
        if (current != null) {
            telemetry.stopped(reason)
            current.watchers.forEach { it.cancel() }
            current.loop.shutdown()
            current.set.release()
            current.job.cancelAndJoin()
        }
        mutableMicrophoneLevel.value = 0f
        mutablePlaybackLevel.value = 0f
        mutablePartialTranscript.value = null
        mutableHandover.value = null
        controls.endSession()
        mutableState.value = next
    }

    private companion object {
        const val STOP_REQUESTED = "stopRequested"
        const val CONVERSATION_CHANGED = "conversationChanged"
        const val RESTARTED = "restarted"
        const val SHUTDOWN = "shutdown"
    }

    private class LiveVoice(val loop: VoiceLoop, val set: VoiceEngineSet, val job: Job, val watchers: List<Job>) {
        /** A tap while speaking: the loop stops playback, drops what was pending and listens. */
        fun interruptSpeech() {
            if (loop.state.value.phase == VoicePhase.SPEAKING) loop.activate(VoiceActivationKind.TAP)
        }
    }

    private sealed interface Command {
        class Start(val session: VoiceChatSession?) : Command

        data class Stop(val reason: String) : Command

        class Retry(val session: VoiceChatSession?) : Command

        data object Interrupt : Command

        data object SetupChanged : Command

        data class Idle(val state: VoiceModeState) : Command

        class Close(val done: CompletableDeferred<Unit>) : Command
    }
}

/**
 * Steps a [VoiceLevelMeter] toward the latest of [readings] and publishes it into [out]: at most
 * once per [VoiceLevelMeter.INTERVAL_MILLIS], and only while the level still has somewhere to go
 * (a steady reading publishes nothing more). Readings arriving faster are conflated to the latest.
 */
private suspend fun publishLevel(readings: Flow<Float>, out: MutableStateFlow<Float>) = coroutineScope {
    val meter = VoiceLevelMeter()
    val latest = MutableStateFlow(0f)
    launch { readings.collect { latest.value = it } }
    while (true) {
        val reading = latest.first { !meter.settled(it) }
        out.value = meter.update(reading, VoiceLevelMeter.INTERVAL_MILLIS)
        delay(VoiceLevelMeter.INTERVAL_MILLIS)
    }
}


/**
 * Where a [VoiceModeController]'s sessions go and what they still lack: through [shared] (the host's
 * shared speech setup) when it is in force, else this device's own server.
 */
private class SessionRouting(
    private val store: VoiceSetupStore,
    private val engines: VoiceEngineFactory,
    private val shared: SharedVoiceSetup?,
) {
    /** Whether a press cannot yet tell the route: the host's shared setup is still being read. */
    val pending: Boolean get() = shared?.pending == true

    /** Where a session would go now, by what is known without waiting. */
    fun now(setup: VoiceSetup): VoiceRoute = shared?.route(setup.prefersDevice) ?: VoiceRoute.Direct

    /** Where a session goes, after reading the shared setup again. */
    suspend fun resolve(setup: VoiceSetup): VoiceRoute = shared?.resolveRoute(setup.prefersDevice) ?: VoiceRoute.Direct

    /** What a session on [route] still lacks, given whether these engines would listen through the server. */
    fun gap(setup: VoiceSetup, route: VoiceRoute): VoiceSetupGap? = setup.gapFor(route, engines.listensOnServer(setup))

    /** What a session started now would still lack. */
    fun gapNow(setup: VoiceSetup): VoiceSetupGap? = gap(setup, now(setup))

    /** Whether [state] asks for a setup that the stored and shared setup now complete. */
    fun completes(state: VoiceModeState): Boolean = state is VoiceModeState.NeedsSetup && gapNow(store.setup.value) == null

    /**
     * The setup gap a loop failure points to: a refused permission is a missing acknowledgement only
     * where this session listens through the server (its own ear, or the ear it handed over to).
     */
    fun failureGap(setup: VoiceSetup, route: VoiceRoute, set: VoiceEngineSet): VoiceSetupGap? {
        val serverEar = engines.listensOnServer(setup) || set.handover.value != null
        return store.setup.value.gapFor(route, serverEar)
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
