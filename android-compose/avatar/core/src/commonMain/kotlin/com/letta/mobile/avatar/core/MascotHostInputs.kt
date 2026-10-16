package com.letta.mobile.avatar.core

import kotlin.jvm.JvmInline

/*
 * letta-mobile-te3n1.1: the values the host writes into a mascot asset beyond its sustained state,
 * one small type per channel, so an [AvatarRuntime] cannot be handed a listening level where it
 * expects a landing impact, or a hover where it expects reduced motion. The inline ones cost
 * nothing at runtime: the director writes them from its frame loop without allocating.
 */

/** How intently the mascot listens, 0..1: the user's typing cadence while it is LISTENING. */
@JvmInline
value class ListenLevel(val value: Float)

/** A reaction's magnitude or variant: LAND's impact 0..1, TRICK's trick id. */
@JvmInline
value class ReactionArg(val value: Float) {
    companion object {
        val NONE = ReactionArg(0f)
    }
}

/** Whether the pointer is over the mascot (the host hit-tests it). */
@JvmInline
value class Hover(val over: Boolean)

/** Whether the user is petting the mascot (the host classifies the gesture). */
@JvmInline
value class Petting(val active: Boolean)

/** Whether the platform asked for reduced motion. */
@JvmInline
value class MotionPreference(val reduced: Boolean)

/** The mascot's own travel / drag velocity through the window, -1..1 on each axis. */
data class MascotMotion(val x: Float, val y: Float)
