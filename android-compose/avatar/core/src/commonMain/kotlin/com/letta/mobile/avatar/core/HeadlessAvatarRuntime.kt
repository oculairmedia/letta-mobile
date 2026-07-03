package com.letta.mobile.avatar.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Renderer-less [AvatarRuntime]: maintains the full command state machine
 * (state transitions, clamped weights, look target, accessories) without
 * drawing anything.
 *
 * Uses:
 * - the default runtime on platforms without a renderer adapter yet;
 * - a base class for renderer adapters that want the bookkeeping for free
 *   (override the `on*` hooks to forward to the actual renderer);
 * - a test double the app and tests can assert against.
 */
open class HeadlessAvatarRuntime : AvatarRuntime {
    private val _state = MutableStateFlow<AvatarRuntimeState>(AvatarRuntimeState.Idle)
    override val state: StateFlow<AvatarRuntimeState> = _state.asStateFlow()

    /** Current expression weights by normalized key (clamped 0..1). */
    val expressionWeights: Map<String, Float> get() = expressions.toMap()

    /** Current viseme weights by normalized key (clamped 0..1). */
    val visemeWeights: Map<String, Float> get() = visemes.toMap()

    var mouthOpen: Float = 0f
        private set

    var lookTarget: AvatarLookTarget? = null
        private set

    /** Accessory ids currently toggled off (default is enabled). */
    val disabledAccessoryIds: Set<String> get() = disabledAccessories.toSet()

    var isDisposed: Boolean = false
        private set

    private val expressions = mutableMapOf<String, Float>()
    private val visemes = mutableMapOf<String, Float>()
    private val disabledAccessories = mutableSetOf<String>()

    /**
     * Bumped by every load/unload/dispose. A load whose [loadCapabilities]
     * resumes after a newer operation started must not publish its (stale)
     * result over the newer state.
     */
    private var operationGeneration = 0

    /**
     * Capabilities to report when [load] succeeds. The headless runtime can
     * honor every command it's given (it just records them), so it claims
     * everything; renderer subclasses should report what they verified.
     */
    protected open suspend fun loadCapabilities(model: AvatarModel): AvatarCapabilities =
        AvatarCapabilities(
            supportsHumanoid = model.format.isHumanoidProfile,
            supportsExpressions = true,
            supportsVisemes = true,
            supportsLookAt = true,
            supportsSpringBones = false,
            supportsEmbeddedAnimations = true,
            supportsAccessories = true,
        )

    final override suspend fun load(model: AvatarModel) {
        check(!isDisposed) { "AvatarRuntime is disposed" }
        // Replacing a live avatar: give the adapter its teardown hook before
        // the new load starts, so renderer resources for the previous model
        // never outlive the state transition.
        if (_state.value !is AvatarRuntimeState.Idle) {
            onUnload()
        }
        resetCommandState()
        operationGeneration += 1
        val generation = operationGeneration
        _state.value = AvatarRuntimeState.Loading(model)
        try {
            val capabilities = loadCapabilities(model)
            if (generation != operationGeneration) {
                // A newer load/unload/dispose superseded this one while it was
                // suspended — tear down whatever this stale load allocated and
                // leave the newer operation's state untouched.
                onUnload()
                throw CancellationException("Load of ${model.id} was superseded")
            }
            _state.value = AvatarRuntimeState.Ready(model, capabilities)
        } catch (cancelled: CancellationException) {
            // A cancelled load is not a failure — don't flash an error state
            // at observers during normal navigation/avatar-switch teardown.
            // Still give the adapter its teardown hook for partial resources.
            if (generation == operationGeneration) {
                runCatching { onUnload() }
                _state.value = AvatarRuntimeState.Idle
            }
            throw cancelled
        } catch (t: Throwable) {
            if (generation == operationGeneration) {
                // Partial renderer resources from the failed load must not
                // stay live behind a Failed state.
                runCatching { onUnload() }
                _state.value = AvatarRuntimeState.Failed(model, t.message ?: "Load failed")
            }
            throw t
        }
    }

    final override suspend fun unload() {
        if (isDisposed) return
        operationGeneration += 1
        resetCommandState()
        if (_state.value !is AvatarRuntimeState.Idle) {
            onUnload()
        }
        _state.value = AvatarRuntimeState.Idle
    }

    override fun setExpression(expression: AvatarExpression, weight: Float) {
        if (readyCapabilities()?.supportsExpressions != true) return
        val clamped = sanitizeWeight(weight)
        expressions[expression.key] = clamped
        onExpressionChanged(expression, clamped)
    }

    override fun setViseme(viseme: AvatarViseme, weight: Float) {
        if (readyCapabilities()?.supportsVisemes != true) return
        val clamped = sanitizeWeight(weight)
        visemes[viseme.key] = clamped
        onVisemeChanged(viseme, clamped)
    }

    override fun setMouthOpen(value: Float) {
        if (readyCapabilities()?.supportsVisemes != true) return
        mouthOpen = sanitizeWeight(value)
        onMouthOpenChanged(mouthOpen)
    }

    override fun setLookTarget(target: AvatarLookTarget?) {
        if (readyCapabilities()?.supportsLookAt != true) return
        lookTarget = target
        onLookTargetChanged(target)
    }

    override fun playGesture(gesture: AvatarGesture, fadeSeconds: Float) {
        // Gestures may be procedural, so they have no capability flag —
        // Ready-gated only.
        if (readyCapabilities() == null) return
        onPlayGesture(gesture, fadeSeconds)
    }

    override fun playAnimation(animationId: String, loop: Boolean) {
        if (readyCapabilities()?.supportsEmbeddedAnimations != true) return
        onPlayAnimation(animationId, loop)
    }

    override fun setAccessoryEnabled(accessoryId: String, enabled: Boolean) {
        if (readyCapabilities()?.supportsAccessories != true) return
        if (enabled) disabledAccessories.remove(accessoryId) else disabledAccessories.add(accessoryId)
        onAccessoryToggled(accessoryId, enabled)
    }

    override fun update(deltaSeconds: Float) {
        // Nothing time-driven headlessly; renderer subclasses override.
    }

    override fun dispose() {
        if (isDisposed) return
        operationGeneration += 1
        // Same teardown hook contract as unload(): adapters relying on
        // onUnload() for renderer cleanup must not leak on direct dispose().
        if (_state.value !is AvatarRuntimeState.Idle) {
            onUnload()
        }
        isDisposed = true
        resetCommandState()
        _state.value = AvatarRuntimeState.Idle
    }

    /** Hook for subclasses: the current avatar is being unloaded. */
    protected open fun onUnload() {}

    /** Hook for subclasses: a gesture was requested while Ready. */
    protected open fun onPlayGesture(gesture: AvatarGesture, fadeSeconds: Float) {}

    /** Hook for subclasses: an animation was requested while Ready. */
    protected open fun onPlayAnimation(animationId: String, loop: Boolean) {}

    // Setter hooks fire AFTER the capability gate and clamping, so adapters
    // forward exactly the values the base class recorded — never commands the
    // model reported unsupported.

    /** Hook for subclasses: an expression weight changed (already clamped). */
    protected open fun onExpressionChanged(expression: AvatarExpression, weight: Float) {}

    /** Hook for subclasses: a viseme weight changed (already clamped). */
    protected open fun onVisemeChanged(viseme: AvatarViseme, weight: Float) {}

    /** Hook for subclasses: the mouth-open level changed (already clamped). */
    protected open fun onMouthOpenChanged(value: Float) {}

    /** Hook for subclasses: the look target changed. */
    protected open fun onLookTargetChanged(target: AvatarLookTarget?) {}

    /** Hook for subclasses: an accessory was toggled. */
    protected open fun onAccessoryToggled(accessoryId: String, enabled: Boolean) {}

    /** The Ready-state capabilities, or null when commands must be dropped. */
    private fun readyCapabilities(): AvatarCapabilities? {
        if (isDisposed) return null
        return (_state.value as? AvatarRuntimeState.Ready)?.capabilities
    }

    private fun resetCommandState() {
        expressions.clear()
        visemes.clear()
        disabledAccessories.clear()
        mouthOpen = 0f
        lookTarget = null
    }

    /** Contract promises 0..1 levels; NaN survives coerceIn, so drop it to 0. */
    private fun sanitizeWeight(weight: Float): Float =
        if (weight.isNaN()) 0f else weight.coerceIn(0f, 1f)
}
