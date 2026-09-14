package com.letta.mobile.avatar.core

/**
 * Bench tunables for [GazeDirector]. Properties rather than constructor args.
 * Defaults match `RiveDesktopSpike.kt` (omega 8.5 / zeta 0.72).
 */
class GazeDirectorConfig {
    var headLeadSeconds: Float = 0.350f
    var eyeTauSeconds: Float = 0.25f
    var scanTauSeconds: Float = 0.08f
    var springOmega: Float = 8.5f
    var springZeta: Float = 0.72f
    var habituationDecaySeconds: Float = 4f
    var habituationRestoreSeconds: Float = 12f
    var cursorInterestFloor: Float = 0.3f
    var cursorNearRadius: Float = 0.4f
    var cursorDemandSeconds: Float = 0.5f
    var headCommitDelta: Float = 0.15f
    var blinkOnHeadTurn: Float = 0.4f
    var headXScale: Float = 0.85f
    var headYScale: Float = 0.7f
    var eyeHeadCompensation: Float = 0.6f
    var maxDeltaSeconds: Float = 0.1f
}
