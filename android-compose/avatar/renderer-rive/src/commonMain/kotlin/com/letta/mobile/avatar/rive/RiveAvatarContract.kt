package com.letta.mobile.avatar.rive

import com.letta.mobile.avatar.core.AvatarReaction
import com.letta.mobile.avatar.core.AvatarState
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotShape

/**
 * The names a `.riv` mascot must expose for this renderer to drive it.
 *
 * A Rive state machine is addressed by string, so the asset and the app agree by convention or not
 * at all. Writing those strings at each call site is how that agreement rots, so they live here and
 * the authoring side is generated from the same list (see `mascot.rml` in this module's `assets`).
 *
 * The sustained condition is one enum property rather than a boolean each: the director's states are
 * mutually exclusive, and parallel booleans let the asset sit in two of them at once, which is a bug
 * the asset can express but the director cannot.
 */
object RiveAvatarContract {
    /** The state machine the renderer instantiates; a `.riv` may hold several. */
    const val STATE_MACHINE: String = "Avatar"

    /**
     * Custom enum `AvatarState`: the director's *sustained* state, as its [stateKey]. Momentary
     * states are not enum values - Rive convention is that a flash is a trigger and the machine
     * itself plays it and returns, so the file owns the timing: [TRIGGER_SUCCESS], [TRIGGER_ERROR],
     * and dragging is the boolean [INPUT_DRAGGED] (which the file's own drag listeners also write).
     */
    const val INPUT_STATE: String = "state"

    /** Trigger. A task completed: the file plays its happy flash and returns to the sustained state. */
    const val TRIGGER_SUCCESS: String = "success"

    /** Trigger. Something failed: the sad flash; the sustained `error` state settles after it. */
    const val TRIGGER_ERROR: String = "error"

    /** Boolean. The pet is being dragged; the file's drag listeners write it too. */
    const val INPUT_DRAGGED: String = "dragged"

    /** Number, 0..1. Jaw/mouth-open level, driven from speech amplitude. */
    const val INPUT_MOUTH_OPEN: String = "mouthOpen"

    /** Number, -1..1. Horizontal gaze; 0 is straight ahead. */
    const val INPUT_LOOK_X: String = "lookX"

    /** Number, -1..1. Vertical gaze; 0 is straight ahead. */
    const val INPUT_LOOK_Y: String = "lookY"

    /** Trigger. One blink, on the director's randomized idle schedule. */
    const val TRIGGER_BLINK: String = "blink"

    /**
     * Number, -1..1. The head turning toward what the eyes look at (the plate slides and rolls,
     * the body leans), additive with the file's own per-state facing. Written by the gaze director
     * after the eyes have led; see rive/mascot/README.md "Gaze and attention".
     */
    const val INPUT_TURN_X: String = "turnX"

    /** Number, -1..1. Vertical head turn; 0 is level. */
    const val INPUT_TURN_Y: String = "turnY"

    /** Custom enum `MascotShape`. Identity, written once on load; see MASCOT.md. */
    const val INPUT_SHAPE: String = "shape"

    /** Colour. Identity; every body fill in the asset is bound to it. */
    const val INPUT_COLOR: String = "color"

    /**
     * Number, degrees 0..359. Identity: turns the body clockwise about its centre. The asset rotates
     * the body's bones, so the skinned outline turns while the face, the plate and the lighting
     * gradients stay upright.
     */
    const val INPUT_SHAPE_ROTATION: String = "shapeRotation"

    // --- te3n1.5 rig inputs, written by the shared MascotDirector (te3n1.1) ----------------------
    // The host interprets gestures and physics and writes these; the file owns every animation.

    /** Boolean. Reduced motion: no idle beats, wander or breath bob; reactions shrink to a blink or glyph change. */
    const val INPUT_REDUCE_MOTION: String = "reduceMotion"

    /** Boolean. Held while the user pets the mascot. */
    const val INPUT_PETTING: String = "petting"

    /** Boolean. The pointer is over the mascot; the host is its writer (the file's listeners are a fallback). */
    const val INPUT_HOVERED: String = "hovered"

    /** Number, 0..1. Listening intensity: the user's typing cadence while LISTENING. */
    const val INPUT_LISTEN_LEVEL: String = "listenLevel"

    /** Number, -1..1. Horizontal travel / drag velocity: the body leans about its feet. */
    const val INPUT_MOVE_X: String = "moveX"

    /** Number, -1..1. Vertical travel velocity, + is down: up stretches, down squashes. */
    const val INPUT_MOVE_Y: String = "moveY"

    /** Number, 0..1. How hard the next [TRIGGER_LAND] lands. */
    const val INPUT_IMPACT: String = "impact"

    /** Number, 0..3. Which trick the next [TRIGGER_TRICK] plays. */
    const val INPUT_TRICK_ID: String = "trickId"

    /** Trigger. A poke: squish, startled glyph, rebound. */
    const val TRIGGER_POKE: String = "poke"

    /** Trigger. Poked too often: the dizzy glyph and a decaying wobble. */
    const val TRIGGER_DIZZY: String = "dizzy"

    /** Trigger. The user sent a message: a 'got it' catch-dip. */
    const val TRIGGER_SENT: String = "sent"

    /** Trigger. A tool started: a small hop with a glance down. */
    const val TRIGGER_TOOL_START: String = "toolStart"

    /** Trigger. A tool returned: a nod. */
    const val TRIGGER_TOOL_DONE: String = "toolDone"

    /** Trigger. Put down: an arrival squash scaled by [INPUT_IMPACT]. */
    const val TRIGGER_LAND: String = "land"

    /** Trigger. Woken out of sleeping / dreaming. */
    const val TRIGGER_WAKE: String = "wake"

    /** Trigger. Dozing off into sleeping / dreaming. */
    const val TRIGGER_YAWN: String = "yawn"

    /** Trigger. Plays trick [INPUT_TRICK_ID]. */
    const val TRIGGER_TRICK: String = "trick"

    /** The enum key for [shape], matching a `DataEnumValue key` in the asset. A key, not an index, for the same reason as [stateKey]. */
    fun shapeKey(shape: MascotShape): String = when (shape) {
        MascotShape.CIRCLE -> "circle"
        MascotShape.BLOB -> "blob"
        MascotShape.ROUNDED_SQUARE -> "roundedSquare"
        MascotShape.PILL -> "pill"
        MascotShape.TRIANGLE -> "triangle"
        MascotShape.HEXAGON -> "hexagon"
        MascotShape.CLOUD -> "cloud"
        MascotShape.DROP -> "drop"
    }

    /** Writes an identity into the asset. Surfaces call this once after load; the runtime never does. */
    fun applyIdentity(sink: RiveInputSink, identity: MascotIdentity) {
        sink.setEnum(INPUT_SHAPE, shapeKey(identity.shape))
        sink.setColor(INPUT_COLOR, identity.argb)
        sink.setNumber(INPUT_SHAPE_ROTATION, identity.rotationDegrees.toFloat())
    }

    /**
     * The enum key for [state], matching a `DataEnumValue key` in the asset.
     *
     * A key rather than an index on purpose. Rive delivers a custom enum's value as its position in
     * the declared list, so an index contract breaks silently the day someone reorders the art: the
     * build stays clean and the mascot simply plays the wrong state forever.
     */
    fun stateKey(state: AvatarState): String? = when (state) {
        AvatarState.IDLE -> "idle"
        AvatarState.LISTENING -> "listening"
        AvatarState.THINKING -> "thinking"
        AvatarState.WORKING -> "working"
        AvatarState.WAITING_INPUT -> "waitingInput"
        AvatarState.SPEAKING -> "speaking"
        AvatarState.ERROR -> "error"
        AvatarState.SLEEPING -> "sleeping"
        AvatarState.DREAMING -> "dreaming"
        AvatarState.LOADING -> "loading"
        AvatarState.FAILED -> "failed"
        AvatarState.DEGRADED -> "degraded"
        // Momentary: written as a trigger / a boolean, never as the sustained enum.
        AvatarState.SUCCESS, AvatarState.DRAGGED -> null
    }
}

/**
 * What the loaded `.riv` plays beyond the base contract (letta-mobile-te3n1.1): the reactions it
 * has a trigger for, the reactions it answers with a blink for want of its own animation, and the
 * optional host inputs it reads. A reaction outside both sets and an input outside [inputs] write
 * nothing, so the director drives its whole vocabulary against any file and the renderer never
 * writes a name the file does not declare.
 *
 * Every name here must be in the file: rig/contract.py and the CI contract check hold
 * [RiveAvatarContract]'s names to the built `.riv`.
 */
data class RiveMascotCapabilities(
    /** Reactions the file plays as a trigger of their own. */
    val reactionTriggers: Map<AvatarReaction, String> = emptyMap(),
    /** Reactions the file has no animation for that read well as a blink. */
    val blinkFallback: Set<AvatarReaction> = emptySet(),
    /** Optional host inputs the file reads ([RiveAvatarContract.INPUT_REDUCE_MOTION] and friends). */
    val inputs: Set<String> = emptySet(),
) {
    /** The file calms itself under reduced motion, so reactions need not shrink on the host side. */
    val honorsReducedMotion: Boolean get() = RiveAvatarContract.INPUT_REDUCE_MOTION in inputs

    /** The file squashes, stretches and leans itself from the travel inputs and its landing trigger. */
    val carriesBody: Boolean
        get() = RiveAvatarContract.INPUT_MOVE_X in inputs &&
            RiveAvatarContract.INPUT_MOVE_Y in inputs &&
            AvatarReaction.LAND in reactionTriggers

    companion object {
        /**
         * The mascot.riv shipped with the te3n1.5 rig: every reaction has its own trigger except an
         * approval ask, whose sustained waitingInput state says it and which gets a blink as its beat.
         */
        val CURRENT: RiveMascotCapabilities = RiveMascotCapabilities(
            reactionTriggers = mapOf(
                AvatarReaction.SENT to RiveAvatarContract.TRIGGER_SENT,
                AvatarReaction.TOOL_START to RiveAvatarContract.TRIGGER_TOOL_START,
                AvatarReaction.TOOL_DONE to RiveAvatarContract.TRIGGER_TOOL_DONE,
                AvatarReaction.RUN_DONE to RiveAvatarContract.TRIGGER_SUCCESS,
                AvatarReaction.RUN_FAILED to RiveAvatarContract.TRIGGER_ERROR,
                AvatarReaction.POKE to RiveAvatarContract.TRIGGER_POKE,
                AvatarReaction.DIZZY to RiveAvatarContract.TRIGGER_DIZZY,
                AvatarReaction.LAND to RiveAvatarContract.TRIGGER_LAND,
                AvatarReaction.WAKE to RiveAvatarContract.TRIGGER_WAKE,
                AvatarReaction.YAWN to RiveAvatarContract.TRIGGER_YAWN,
                AvatarReaction.TRICK to RiveAvatarContract.TRIGGER_TRICK,
            ),
            blinkFallback = setOf(AvatarReaction.APPROVAL_ASK),
            inputs = setOf(
                RiveAvatarContract.INPUT_REDUCE_MOTION,
                RiveAvatarContract.INPUT_PETTING,
                RiveAvatarContract.INPUT_HOVERED,
                RiveAvatarContract.INPUT_LISTEN_LEVEL,
                RiveAvatarContract.INPUT_MOVE_X,
                RiveAvatarContract.INPUT_MOVE_Y,
                RiveAvatarContract.INPUT_IMPACT,
                RiveAvatarContract.INPUT_TRICK_ID,
            ),
        )
    }
}

/**
 * Where input writes go. Implemented per platform: by the Rive Android runtime, and on desktop by
 * the native rive-runtime bridge (spike, letta-mobile-0s5bi). Keeping it an interface is what lets the runtime and
 * every mapping decision above it be shared code with tests that need no device.
 */
interface RiveInputSink {
    fun setNumber(input: String, value: Float)

    fun setBoolean(input: String, value: Boolean)

    /** Writes a custom-enum property by its value key. */
    fun setEnum(input: String, key: String)

    /** Writes a colour property as packed ARGB, the layout both Rive runtimes take. */
    fun setColor(input: String, argb: Int)

    fun fire(input: String)
}

/** The mascot this module ships: a raw resource on Android, a classpath resource on desktop. */
val MASCOT_MODEL: com.letta.mobile.avatar.core.AvatarModel = com.letta.mobile.avatar.core.AvatarModel(
    id = "letta-mascot",
    displayName = "Letta Mascot",
    uri = "res://raw/mascot.riv",
)
