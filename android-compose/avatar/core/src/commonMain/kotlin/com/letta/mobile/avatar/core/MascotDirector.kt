package com.letta.mobile.avatar.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/**
 * The one director every mascot seat on every platform goes through (letta-mobile-te3n1.1).
 *
 * It owns the [AvatarDirector] (which sustained [AvatarState] wins), the [GazeDirector] (where it
 * looks) and the reaction policy (which one-shot [AvatarReaction]s fire, with cooldowns and
 * suppression). Hosts feed it levels ([setSignals]), moments ([onEvent]), the composer's keystroke
 * count ([setKeystrokes]), the gaze world ([setGazeWorld]) and time ([tick]); it drives the
 * [AvatarRuntime] and publishes what it decided as a [MascotFrame].
 *
 * Pure common code with no platform policy: Android, desktop and web construct the same class.
 * Deterministic: all randomness from [random], all time from [tick].
 *
 * Reaction rules:
 * - a mascot that is asleep (sleeping or dreaming) fires [AvatarReaction.WAKE] before anything
 *   that wakes it, and [AvatarReaction.YAWN] when it dozes off;
 * - [AvatarReaction.SENT] never nods while the mascot shows an error;
 * - tool beats are rate-limited to one per working dwell, so a storm of tools reads as one;
 * - [AvatarReaction.RUN_DONE] fires for every completion, back to back included;
 * - under reduced motion the asset is told ([AvatarRuntime.setReducedMotion]) and calms its own
 *   reactions; an asset that cannot ([AvatarRuntime.honorsReducedMotion] false) gets a single
 *   blink for each instead.
 */
class MascotDirector(
    private val runtime: AvatarRuntime,
    random: Random = Random.Default,
    private val config: Config = Config(),
    avatarConfig: AvatarDirector.Config = AvatarDirector.Config(),
) {
    /** Reaction cooldowns (seconds) and listening cadence tunables. */
    data class Config(
        val sentCooldownSeconds: Float = 0.6f,
        /** One tool beat per this long; matches [AvatarDirector.Config.workingMinDwellSeconds]. */
        val toolBeatCooldownSeconds: Float = 1.5f,
        val approvalCooldownSeconds: Float = 0.6f,
        val failedCooldownSeconds: Float = 0.6f,
        val pokeCooldownSeconds: Float = 0.12f,
        val landCooldownSeconds: Float = 0.3f,
        val wakeCooldownSeconds: Float = 1f,
        /** Below the 3 s rest rule, so a cooldown never outlives the ticks that count it down. */
        val yawnCooldownSeconds: Float = 2.5f,
        val trickCooldownSeconds: Float = 0.5f,
        /** [AvatarReaction.DIZZY]: this many pokes inside [dizzyWindowSeconds]. */
        val dizzyPokes: Int = 5,
        val dizzyWindowSeconds: Float = 2f,
        /** Measured on ticked time like every cooldown; a mascot at rest does not count it down. */
        val dizzyCooldownSeconds: Float = 10f,
        /** Typing at this many keystrokes per second is full attention. */
        val listenFullCadence: Float = 8f,
        /** A composing user who pauses still has some attention. */
        val listenFloor: Float = 0.25f,
        val listenSmoothingSeconds: Float = 0.35f,
    ) {
        /** How long [reaction] waits before it may fire again; completions never wait. */
        fun cooldownOf(reaction: AvatarReaction): Float = when (reaction) {
            AvatarReaction.SENT -> sentCooldownSeconds
            AvatarReaction.TOOL_START, AvatarReaction.TOOL_DONE -> toolBeatCooldownSeconds
            AvatarReaction.APPROVAL_ASK -> approvalCooldownSeconds
            AvatarReaction.RUN_DONE -> 0f
            AvatarReaction.RUN_FAILED -> failedCooldownSeconds
            AvatarReaction.POKE -> pokeCooldownSeconds
            AvatarReaction.DIZZY -> dizzyCooldownSeconds
            AvatarReaction.LAND -> landCooldownSeconds
            AvatarReaction.WAKE -> wakeCooldownSeconds
            AvatarReaction.YAWN -> yawnCooldownSeconds
            AvatarReaction.TRICK -> trickCooldownSeconds
        }
    }

    /** Sustained-state arbitration. */
    val avatar: AvatarDirector = AvatarDirector(runtime, random, avatarConfig)

    private val gaze = GazeDirector(random)
    private val headTurn: AvatarHeadTurn? = runtime as? AvatarHeadTurn

    /** What was decided at the last [tick]. */
    val frame: MascotFrame = MascotFrame()

    /** The arbitrated sustained state. */
    val state: AvatarState get() = avatar.state

    /** The asset animates travel and landings itself ([AvatarRuntime.carriesBody]); the host must not deform it too. */
    val carriesBody: Boolean get() = runtime.carriesBody

    private var signals = MascotSignals()
    private var world = GazeWorld()
    private val pending = ReactionBuffer()

    /** Seconds of ticked time; reaction cooldowns are measured on it. */
    private var clock = 0f
    private val lastFired = FloatArray(AvatarReaction.entries.size) { Float.NEGATIVE_INFINITY }
    private val pokeTimes = FloatArray(config.dizzyPokes.coerceAtLeast(1)) { Float.NEGATIVE_INFINITY }
    private var pokeCursor = 0

    private var keystrokesSeen = -1
    private var keysSinceTick = 0
    private var cadence = 0f
    private var listenLevel = 0f
    private var listenLevelSent = 0f

    init {
        avatar.addStateListener { exit, enter -> onTransition(exit, enter) }
    }

    /**
     * Hears every reaction the director fires, after cooldowns and suppression (letta-mobile-te3n1.4):
     * the host's hook for what it adds around the asset - a squash on the seat's body - so the body
     * answers exactly the reactions the asset plays. Null tells no one.
     */
    var reactionListener: MascotReactionListener? = null

    /** Observe sustained-state transitions (the host's renderer write is one of these). */
    fun addStateListener(listener: AvatarStateListener) = avatar.addStateListener(listener)

    /** Apply a new snapshot of levels; only what changed reaches the arbitration, and it is arbitrated once. */
    fun setSignals(next: MascotSignals) {
        val previous = signals
        if (next == previous) return
        signals = next
        if (next.reduceMotion != previous.reduceMotion) {
            frame.reduceMotion = next.reduceMotion
            runtime.setReducedMotion(MotionPreference(next.reduceMotion))
        }
        avatar.batch {
            if (next.error != previous.error) setFailed(next.error)
            if (next.awaitingApproval != previous.awaitingApproval) setAwaitingApproval(next.awaitingApproval)
            if (next.dreaming != previous.dreaming) setDreaming(next.dreaming)
            if (next.activity != previous.activity) setActivity(next.activity)
            if (next.userTyping != previous.userTyping) setUserTyping(next.userTyping)
        }
    }

    /** React to one moment. */
    fun onEvent(event: MascotEvent) {
        when (event) {
            MascotEvent.Sent -> {
                wakeIfAsleep()
                if (avatar.state != AvatarState.ERROR) fire(AvatarReaction.SENT)
            }
            is MascotEvent.ToolStarted -> {
                wakeIfAsleep()
                fire(AvatarReaction.TOOL_START)
            }
            MascotEvent.ToolEnded -> fire(AvatarReaction.TOOL_DONE)
            MascotEvent.ApprovalRaised -> {
                wakeIfAsleep()
                fire(AvatarReaction.APPROVAL_ASK)
            }
            is MascotEvent.RunFinished -> if (event.ok) {
                avatar.notifyTaskSucceeded()
                fire(AvatarReaction.RUN_DONE)
            }
            MascotEvent.RunFailed -> fire(AvatarReaction.RUN_FAILED)
            else -> onGesture(event)
        }
    }

    private fun onGesture(event: MascotEvent) {
        when (event) {
            MascotEvent.Poke -> {
                wakeIfAsleep()
                fire(AvatarReaction.POKE)
                if (pokedTooOften()) fire(AvatarReaction.DIZZY)
            }
            is MascotEvent.Petting -> {
                if (event.active) wakeIfAsleep()
                frame.petting = event.active
                runtime.setPetting(Petting(event.active))
            }
            MascotEvent.DragStarted -> avatar.setDragged(true)
            is MascotEvent.DragEnded -> {
                avatar.setDragged(false)
                setMotion(0f, 0f)
                fire(AvatarReaction.LAND, ReactionArg(event.impact.coerceIn(0f, 1f)))
            }
            is MascotEvent.Landed -> fire(AvatarReaction.LAND, ReactionArg(event.impact.coerceIn(0f, 1f)))
            is MascotEvent.DoubleTap -> {
                wakeIfAsleep()
                fire(AvatarReaction.TRICK, ReactionArg(event.trick.toFloat()))
            }
            is MascotEvent.Hover -> {
                frame.hovered = event.hovered
                runtime.setHovered(Hover(event.hovered))
            }
            else -> Unit
        }
    }

    /**
     * The host-measured travel / drag velocity, -1..1 on each axis (te3n1.3's MascotBody motion).
     * Written to the runtime only when it changes; reduced motion holds the body still, so it writes zero.
     */
    fun setMotion(vx: Float, vy: Float) {
        val x = if (signals.reduceMotion || vx.isNaN()) 0f else vx.coerceIn(-1f, 1f)
        val y = if (signals.reduceMotion || vy.isNaN()) 0f else vy.coerceIn(-1f, 1f)
        if (x == frame.motionX && y == frame.motionY) return
        frame.motionX = x
        frame.motionY = y
        runtime.setMotion(MascotMotion(x, y))
    }

    /** The composer's running keystroke count (any surface's; only an agent being typed to listens). */
    fun setKeystrokes(total: Int) {
        if (keystrokesSeen >= 0 && total > keystrokesSeen) keysSinceTick += total - keystrokesSeen
        keystrokesSeen = total
    }

    /** Cursor / composer / timeline for the gaze, already in gaze units. */
    fun setGazeWorld(next: GazeWorld) {
        world = next
    }

    /** Advance state, gaze, listening and reactions by [deltaSeconds]; returns the rewritten [frame]. */
    fun tick(deltaSeconds: Float): MascotFrame {
        if (deltaSeconds <= 0f || deltaSeconds.isNaN()) return frame
        clock += deltaSeconds
        avatar.tick(deltaSeconds)
        applyGaze(gaze.tick(deltaSeconds, avatar.state, world))
        tickListening(deltaSeconds)
        frame.state = avatar.state
        frame.copyReactionsFrom(pending)
        pending.clear()
        return frame
    }

    private fun applyGaze(pose: GazePose) {
        frame.gaze = pose
        avatar.setLookTarget(pose.lookTarget)
        headTurn?.setHeadTurn(pose.headX, pose.headY)
        if (pose.blink) runtime.playGesture(BLINK)
    }

    private fun tickListening(delta: Float) {
        val alpha = 1f - exp(-delta / config.listenSmoothingSeconds)
        cadence += (keysSinceTick / delta - cadence) * alpha
        keysSinceTick = 0
        val target = if (signals.userTyping) (cadence / config.listenFullCadence).coerceIn(config.listenFloor, 1f) else 0f
        listenLevel += (target - listenLevel) * alpha
        // Settling to silence snaps to exactly zero, and that last step is always written.
        if (target == 0f) listenLevel = listenLevel.takeIf { it >= LEVEL_EPSILON } ?: 0f
        frame.listenLevel = listenLevel
        val threshold = if (listenLevel == 0f) 0f else LEVEL_EPSILON
        if (abs(listenLevel - listenLevelSent) > threshold) {
            listenLevelSent = listenLevel
            runtime.setListenLevel(ListenLevel(listenLevel))
        }
    }

    private fun onTransition(exit: AvatarState, enter: AvatarState) {
        when {
            enter.eyesClosed && !exit.eyesClosed -> fire(AvatarReaction.YAWN)
            exit.eyesClosed && !enter.eyesClosed -> fire(AvatarReaction.WAKE)
        }
    }

    private fun wakeIfAsleep() {
        if (avatar.state.eyesClosed) fire(AvatarReaction.WAKE)
    }

    private fun pokedTooOften(): Boolean {
        pokeTimes[pokeCursor] = clock
        pokeCursor = (pokeCursor + 1) % pokeTimes.size
        // The slot about to be overwritten is the oldest of the last N pokes.
        val oldest = pokeTimes[pokeCursor]
        if (clock - oldest > config.dizzyWindowSeconds) return false
        pokeTimes.fill(Float.NEGATIVE_INFINITY)
        return true
    }

    /** Fire [reaction] unless it is cooling down; reduced motion on an asset that cannot calm itself plays a blink. */
    private fun fire(reaction: AvatarReaction, arg: ReactionArg = ReactionArg.NONE) {
        if (clock - lastFired[reaction.ordinal] < config.cooldownOf(reaction)) return
        lastFired[reaction.ordinal] = clock
        pending.add(reaction)
        if (signals.reduceMotion && !runtime.honorsReducedMotion) runtime.playGesture(BLINK) else runtime.react(reaction, arg)
        reactionListener?.onReaction(reaction, arg)
    }

    private companion object {
        val BLINK = AvatarGesture("blink")
        const val LEVEL_EPSILON = 0.01f
    }
}

/** Hears the reactions a [MascotDirector] fires; see [MascotDirector.reactionListener]. */
fun interface MascotReactionListener {
    fun onReaction(reaction: AvatarReaction, arg: ReactionArg)
}
