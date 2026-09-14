package com.letta.mobile.avatar.core

/**
 * How the host drives the [GazeDirector]. JUSTIFIED is the product behaviour;
 * CURSOR is the bench's range check; OFF parks gaze at centre (bench sliders).
 * Source: `GazeMode` in `RiveDesktopSpike.kt`.
 */
enum class GazeDriveMode {
    JUSTIFIED,
    CURSOR,
    OFF,
}

/**
 * World the director can look at this frame. Pointer / input / timeline are
 * already in gaze units (-1..1). A null optional point makes that target
 * unavailable in the plan. Hosts that have window-space rects should build
 * this with [fromWindow]; OWN / USER still run when input / timeline are null.
 */
data class GazeWorld(
    val pointer: GazePoint? = null,
    val input: GazePoint? = null,
    val timeline: GazePoint? = null,
    val mode: GazeDriveMode = GazeDriveMode.JUSTIFIED,
    /**
     * Bench: `now - lastCursorMove < 500ms`. When null, inferred from pointer
     * position changes with the same 500 ms window.
     */
    val pointerMovedRecently: Boolean? = null,
) {
    companion object {
        /** Product host API: window-space tile + optional composer / timeline. */
        fun fromWindow(
            window: GazeWindow,
            mode: GazeDriveMode = GazeDriveMode.JUSTIFIED,
            pointerMovedRecently: Boolean? = null,
        ): GazeWorld = GazeWorld(
            pointer = GazeMath.pointerPxToGaze(window.pointerPx, window.mascot, window.reach.minPx),
            input = GazeMath.rectCenterToGaze(window.rects.input, window.mascot, window.reach.minPx),
            timeline = GazeMath.rectCenterToGaze(window.rects.timeline, window.mascot, window.reach.minPx),
            mode = mode,
            pointerMovedRecently = pointerMovedRecently,
        )
    }
}

/** Composer and message-list window rects; either may be null until the host publishes them. */
data class GazeTargetRects(
    val input: GazeRect? = null,
    val timeline: GazeRect? = null,
)

/**
 * Window-space surfaces a host publishes for [GazeWorld.fromWindow].
 * [pointerPx] is pixels (same space as [mascot]), not gaze units.
 */
data class GazeWindow(
    val mascot: GazeRect,
    val reach: GazeReach,
    val pointerPx: GazePoint? = null,
    val rects: GazeTargetRects = GazeTargetRects(),
)

/** Eyes, head, and a one-tick blink pulse. Look is also packaged as a screen target. */
data class GazePose(
    val look: GazePoint,
    val head: GazePoint,
    val blink: Boolean,
    val target: GazeTarget,
) {
    val lookX: Float get() = look.x
    val lookY: Float get() = look.y
    val headX: Float get() = head.x
    val headY: Float get() = head.y

    val lookTarget: AvatarLookTarget.Screen
        get() = GazeMath.toScreen(look)

    companion object {
        val CENTER: GazePose = GazePose(
            look = GazePoint(0f, 0f),
            head = GazePoint(0f, 0f),
            blink = false,
            target = GazeTarget.OWN,
        )
    }
}
