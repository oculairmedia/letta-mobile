package com.letta.mobile.avatar.core

/**
 * Head facing in -1..1 on each axis. This is a mascot-specific channel the
 * [GazeDirector] writes after the eyes have led; it is not an [AvatarRuntime]
 * command because the 3D VRM path aims with [AvatarRuntime.setLookTarget]
 * alone. The Rive runtime satisfies this with `turnX` / `turnY`.
 */
fun interface AvatarHeadTurn {
    fun setHeadTurn(turnX: Float, turnY: Float)
}
