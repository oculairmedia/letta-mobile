package com.letta.mobile.avatar.rive

import com.letta.mobile.avatar.core.AvatarCameraFraming
import com.letta.mobile.avatar.core.AvatarCapabilities
import com.letta.mobile.avatar.core.AvatarExpression
import com.letta.mobile.avatar.core.AvatarGesture
import com.letta.mobile.avatar.core.AvatarLookTarget
import com.letta.mobile.avatar.core.AvatarModel
import com.letta.mobile.avatar.core.AvatarRuntime
import com.letta.mobile.avatar.core.AvatarRuntimeState
import com.letta.mobile.avatar.core.AvatarState
import com.letta.mobile.avatar.core.AvatarViseme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives a 2D Rive mascot through the same [AvatarRuntime] the 3D VRM renderer implements, so the
 * director's behavior is inherited rather than reimplemented for a second art style.
 *
 * The whole runtime is common code over a [RiveInputSink]. Only writing an input is platform work,
 * which is what lets every mapping decision here be tested without a device, and what lets the
 * desktop JCEF surface reuse this class unchanged rather than growing a parallel copy.
 *
 * A flat mascot is honest about what it is not. It has no skeleton, no spring bones and no embedded
 * glTF clips, so those capabilities are reported false and the matching commands are dropped - which
 * the [AvatarRuntime] contract already defines as the behavior for an unsupported capability, so the
 * app keeps one code path for every asset.
 */
class RiveAvatarRuntime(
    private val sink: RiveInputSink,
    private val capabilities: AvatarCapabilities = MASCOT_CAPABILITIES,
) : AvatarRuntime {

    private val _state = MutableStateFlow<AvatarRuntimeState>(AvatarRuntimeState.Idle)
    override val state: StateFlow<AvatarRuntimeState> = _state.asStateFlow()

    private var disposed = false

    /**
     * Loading is synchronous here because the caller already holds the instantiated artboard: the
     * Rive file is fetched and parsed by the hosting surface before a sink exists at all.
     */
    override suspend fun load(model: AvatarModel) {
        check(!disposed) { "Runtime disposed" }
        _state.value = AvatarRuntimeState.Loading(model)
        applyState(AvatarState.LOADING)
        _state.value = AvatarRuntimeState.Ready(model, capabilities)
        applyState(AvatarState.IDLE)
    }

    override suspend fun unload() {
        if (disposed) return
        _state.value = AvatarRuntimeState.Idle
    }

    /**
     * Expressions reach a mascot as the sustained state, not as an independent channel: its art has
     * one drawing per mood, and blending two of them is exactly what a flat rig cannot do. Weight is
     * therefore a switch - anything at or below zero leaves the current state alone.
     */
    override fun setExpression(expression: AvatarExpression, weight: Float) {
        if (!ready() || weight <= 0f) return
        stateForExpression(expression)?.let(::applyState)
    }

    /** No viseme rig. Lip sync arrives as [setMouthOpen], which a flat mouth can honour. */
    override fun setViseme(viseme: AvatarViseme, weight: Float) = Unit

    override fun setMouthOpen(value: Float) {
        if (!ready()) return
        sink.setNumber(RiveAvatarContract.INPUT_MOUTH_OPEN, value.coerceIn(0f, 1f))
    }

    /**
     * Only screen-space gaze survives the trip to a flat rig; world space assumes a scene this
     * asset does not have. A null target returns the eyes to centre, which is the idle gaze.
     */
    override fun setLookTarget(target: AvatarLookTarget?) {
        if (!ready()) return
        val (x, y) = when (target) {
            null -> 0f to 0f
            is AvatarLookTarget.Screen -> (target.x * 2f - 1f) to (target.y * 2f - 1f)
            is AvatarLookTarget.World -> return
        }
        sink.setNumber(RiveAvatarContract.INPUT_LOOK_X, x.coerceIn(-1f, 1f))
        sink.setNumber(RiveAvatarContract.INPUT_LOOK_Y, y.coerceIn(-1f, 1f))
    }

    /** Blink is the one gesture a flat mascot has; the rest need a rig it does not carry. */
    override fun playGesture(gesture: AvatarGesture, fadeSeconds: Float) {
        if (!ready()) return
        if (gesture.id == BLINK_GESTURE) sink.fire(RiveAvatarContract.TRIGGER_BLINK)
    }

    override fun playAnimation(animationId: String, loop: Boolean) = Unit

    override fun setAccessoryEnabled(accessoryId: String, enabled: Boolean) = Unit

    override fun setCameraFraming(framing: AvatarCameraFraming) = Unit

    /** Rive advances its own artboard on its worker thread, so there is no clock to pump here. */
    override fun update(deltaSeconds: Float) = Unit

    override fun dispose() {
        disposed = true
        _state.value = AvatarRuntimeState.Idle
    }

    /** The director's arbitrated state, which is what the mascot's state machine actually reads. */
    fun applyState(state: AvatarState) {
        if (disposed) return
        sink.setEnum(RiveAvatarContract.INPUT_STATE, RiveAvatarContract.stateKey(state))
    }

    private fun ready(): Boolean = !disposed && _state.value is AvatarRuntimeState.Ready

    private fun stateForExpression(expression: AvatarExpression): AvatarState? = when (expression) {
        AvatarExpression.Happy -> AvatarState.SUCCESS
        AvatarExpression.Sad, AvatarExpression.Angry -> AvatarState.ERROR
        AvatarExpression.Surprised -> AvatarState.WAITING_INPUT
        AvatarExpression.Relaxed -> AvatarState.THINKING
        AvatarExpression.Neutral -> AvatarState.IDLE
        is AvatarExpression.Custom -> null
    }

    companion object {
        /** The gesture id the director fires for the idle blink schedule. */
        const val BLINK_GESTURE: String = "blink"

        /**
         * What a flat mascot can actually do. Not humanoid, no viseme rig, no spring bones and no
         * embedded clips; expressions and gaze are state-machine inputs, so those hold.
         */
        val MASCOT_CAPABILITIES: AvatarCapabilities = AvatarCapabilities(
            supportsHumanoid = false,
            supportsExpressions = true,
            supportsVisemes = false,
            supportsLookAt = true,
            supportsSpringBones = false,
            supportsEmbeddedAnimations = false,
            supportsAccessories = false,
        )
    }
}
