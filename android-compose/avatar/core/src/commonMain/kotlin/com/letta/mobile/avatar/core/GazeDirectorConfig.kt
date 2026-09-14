package com.letta.mobile.avatar.core

/**
 * Bench tunables for [GazeDirector]. Properties rather than constructor args.
 * Defaults match `RiveDesktopSpike.kt` (omega 8.5 / zeta 0.72).
 */
class GazeDirectorConfig {
    /** AWAY: how far off-axis the eyes park (gaze units, |x|). */
    var awayReach: ClosedFloatingPointRange<Float> = 0.45f..0.85f
    /** OWN: a subtler aside when own thoughts drift off-centre. */
    var ownReach: ClosedFloatingPointRange<Float> = 0.2f..0.45f
    /** OWN: how often an own-thoughts dwell parks aside rather than at centre. */
    var ownAsideChance: Float = 0.65f
    /** Vertical spread of an aside point (screen y: negative is up, where thinking looks). */
    var asideVertical: ClosedFloatingPointRange<Float> = -0.35f..0.2f
    // Product tempo: eyes and head a touch slower than the bench (0.35 / 0.25 / 8.5) - read as too quick at product sizes.
    var headLeadSeconds: Float = 0.45f
    var eyeTauSeconds: Float = 0.32f
    var scanTauSeconds: Float = 0.08f
    var springOmega: Float = 6.5f
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
