package com.letta.mobile.ui.components

import kotlin.math.exp

/**
 * Frame-rate independent motion state for [ThinkingShader].
 *
 * The scan phase is integrated instead of derived from `time * speed`, so a
 * streaming-energy change cannot jump the wave. Energy uses a quicker attack
 * than release: token bursts register promptly, then settle without flicker.
 */
internal data class ThinkingShaderMotionState(
    val scanPhase: Float = 0f,
    val streamEnergy: Float = 0f,
)

internal object ThinkingShaderMotion {
    const val MinEnergy = 0f
    const val MaxEnergy = 1f

    private const val BaseScanCyclesPerSecond = 0.075f
    private const val MaxEnergySpeedBoost = 0.10f
    private const val AttackTimeConstantSeconds = 0.16f
    private const val ReleaseTimeConstantSeconds = 0.72f
    private const val MaxFrameDeltaSeconds = 0.10f

    fun advance(
        state: ThinkingShaderMotionState,
        deltaSeconds: Float,
        targetEnergy: Float,
        motionScale: Float,
    ): ThinkingShaderMotionState {
        val energy = smoothEnergy(state.streamEnergy, targetEnergy, deltaSeconds)
        val phase = advancePhase(state.scanPhase, energy, deltaSeconds, motionScale)
        return ThinkingShaderMotionState(scanPhase = phase, streamEnergy = energy)
    }

    fun smoothEnergy(current: Float, target: Float, deltaSeconds: Float): Float {
        val dt = deltaSeconds.coerceIn(0f, MaxFrameDeltaSeconds)
        val boundedTarget = target.coerceIn(MinEnergy, MaxEnergy)
        val timeConstant = if (boundedTarget > current) {
            AttackTimeConstantSeconds
        } else {
            ReleaseTimeConstantSeconds
        }
        val blend = if (dt == 0f) 0f else 1f - exp(-dt / timeConstant)
        return (current + (boundedTarget - current) * blend).coerceIn(MinEnergy, MaxEnergy)
    }

    fun advancePhase(
        current: Float,
        smoothedEnergy: Float,
        deltaSeconds: Float,
        motionScale: Float,
    ): Float {
        val dt = deltaSeconds.coerceIn(0f, MaxFrameDeltaSeconds)
        val energy = smoothedEnergy.coerceIn(MinEnergy, MaxEnergy)
        val speed = BaseScanCyclesPerSecond * (1f + energy * MaxEnergySpeedBoost)
        return wrapUnitPhase(current + dt * speed * motionScale.coerceIn(0f, 1f))
    }

    internal fun wrapUnitPhase(value: Float): Float {
        val wrapped = value % 1f
        return if (wrapped < 0f) wrapped + 1f else wrapped
    }
}
