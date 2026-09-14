package com.letta.mobile.ui.mascot

import com.letta.mobile.avatar.core.AvatarDirector
import com.letta.mobile.avatar.core.AvatarGesture
import com.letta.mobile.avatar.core.AvatarHeadTurn
import com.letta.mobile.avatar.core.AvatarRuntime
import com.letta.mobile.avatar.core.AvatarState
import com.letta.mobile.avatar.core.GazeDirector
import com.letta.mobile.avatar.core.GazePose
import com.letta.mobile.avatar.core.GazeWorld
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.presence.AgentPresence

/**
 * One live mascot for one agent, kept for the life of the process: the renderer's scene (owned
 * by the platform subclass), the [AvatarRuntime] over it, and the [AvatarDirector] that
 * arbitrates its state. A scene that is closed whenever its composable leaves composition
 * restarts from its entry pose every time the user switches view; holding it here means the same
 * character, mid-thought, is drawn wherever that agent appears.
 *
 * The director is the only thing that decides the mascot's state; [GazeDirector] decides where
 * it looks. Surfaces feed presence ([apply]), time ([tickTo]) and the gaze world ([setGazeWorld]);
 * it drives the runtime through [applyState]. Nothing here is platform policy; the platform
 * supplies [load] and [dispose].
 */
abstract class MascotEntry(
    val runtime: AvatarRuntime,
    var identity: MascotIdentity,
    /** The renderer's write for an arbitrated state (Rive: the state machine's inputs). */
    applyState: (AvatarState) -> Unit,
) {
    val director = AvatarDirector(runtime).also { d ->
        d.addStateListener { _, enter -> applyState(enter) }
    }
    val gaze = GazeDirector()
    val lastGaze: GazePose get() = lastGazePose
    private val headTurn: AvatarHeadTurn? = runtime as? AvatarHeadTurn
    private var gazeWorld = GazeWorld()
    private var lastGazePose: GazePose = GazePose.CENTER
    private var lastPresence = AgentPresence.IDLE
    private var lastTickNanos = 0L
    private var loaded = false

    /** Loads the runtime's model; called once, from the first surface that draws this agent. */
    protected abstract suspend fun load()

    /** Releases the renderer's scene. */
    abstract fun dispose()

    /** Loads once, from whichever surface first draws this agent (its own lifecycle, no ad hoc scope). */
    suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        load()
    }

    /** Feeds the director the agent's presence; see [applyPresence]. */
    fun apply(presence: AgentPresence) {
        val was = lastPresence
        lastPresence = presence
        director.applyPresence(was, presence)
    }

    /**
     * Cursor / surface bounds (already converted to gaze units by the surface). A null pointer
     * still runs the justified plan so the eyes are never dead. Last writer wins when several
     * surfaces of the same agent compose in one frame.
     */
    fun setGazeWorld(world: GazeWorld) {
        gazeWorld = world
    }

    /** Advances the director and gaze clocks once per frame, however many surfaces draw this agent. */
    fun tickTo(nowNanos: Long) {
        if (nowNanos == lastTickNanos) return
        val dt = if (lastTickNanos == 0L) 0f else ((nowNanos - lastTickNanos) / NANOS_PER_SECOND).coerceIn(0f, MAX_TICK_SECONDS)
        lastTickNanos = nowNanos
        if (dt > 0f) {
            director.tick(dt)
            applyGaze(gaze.tick(dt, director.state, gazeWorld))
        }
    }

    private fun applyGaze(pose: GazePose) {
        lastGazePose = pose
        director.setLookTarget(pose.lookTarget)
        headTurn?.setHeadTurn(pose.headX, pose.headY)
        if (pose.blink) runtime.playGesture(BLINK)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1e9f
        /** A tab that was hidden for a minute comes back with one frame of motion, not sixty seconds of it. */
        const val MAX_TICK_SECONDS = 0.1f
        val BLINK = AvatarGesture("blink")
    }
}

/**
 * The per-agent [MascotEntry] table. [create] is the platform's renderer bring-up (scene, file,
 * identity applied before the first advance); [applyIdentity] re-skins a live entry when the
 * agent's identity changes. Null from [create] means the renderer is unavailable and the caller
 * draws its fallback.
 */
class MascotEntries<E : MascotEntry>(
    private val create: (identity: MascotIdentity) -> E?,
    private val applyIdentity: (entry: E, identity: MascotIdentity) -> Unit,
) {
    private val entries = HashMap<String, E>()

    /** The entry for [agentId], created on first use. Called from composition, i.e. the UI thread, on both hosts. */
    fun get(agentId: String, identity: MascotIdentity): E? {
        entries[agentId]?.let { entry ->
            if (entry.identity != identity) {
                entry.identity = identity
                applyIdentity(entry, identity)
            }
            return entry
        }
        return create(identity)?.also { entries[agentId] = it }
    }

    fun closeAll() {
        entries.values.forEach { it.dispose() }
        entries.clear()
    }
}
