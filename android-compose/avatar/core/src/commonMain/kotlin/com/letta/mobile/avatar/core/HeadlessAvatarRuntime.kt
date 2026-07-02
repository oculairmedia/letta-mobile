package com.letta.mobile.avatar.core

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
        resetCommandState()
        _state.value = AvatarRuntimeState.Loading(model)
        try {
            val capabilities = loadCapabilities(model)
            _state.value = AvatarRuntimeState.Ready(model, capabilities)
        } catch (t: Throwable) {
            _state.value = AvatarRuntimeState.Failed(model, t.message ?: "Load failed")
            throw t
        }
    }

    final override suspend fun unload() {
        if (isDisposed) return
        resetCommandState()
        onUnload()
        _state.value = AvatarRuntimeState.Idle
    }

    override fun setExpression(expression: AvatarExpression, weight: Float) {
        if (!isReady()) return
        expressions[expression.key] = weight.coerceIn(0f, 1f)
    }

    override fun setViseme(viseme: AvatarViseme, weight: Float) {
        if (!isReady()) return
        visemes[viseme.key] = weight.coerceIn(0f, 1f)
    }

    override fun setMouthOpen(value: Float) {
        if (!isReady()) return
        mouthOpen = value.coerceIn(0f, 1f)
    }

    override fun setLookTarget(target: AvatarLookTarget?) {
        if (!isReady()) return
        lookTarget = target
    }

    override fun playGesture(gesture: AvatarGesture, fadeSeconds: Float) {
        if (!isReady()) return
        onPlayGesture(gesture, fadeSeconds)
    }

    override fun playAnimation(animationId: String, loop: Boolean) {
        if (!isReady()) return
        onPlayAnimation(animationId, loop)
    }

    override fun setAccessoryEnabled(accessoryId: String, enabled: Boolean) {
        if (!isReady()) return
        if (enabled) disabledAccessories.remove(accessoryId) else disabledAccessories.add(accessoryId)
    }

    override fun update(deltaSeconds: Float) {
        // Nothing time-driven headlessly; renderer subclasses override.
    }

    override fun dispose() {
        if (isDisposed) return
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

    private fun isReady(): Boolean =
        !isDisposed && _state.value is AvatarRuntimeState.Ready

    private fun resetCommandState() {
        expressions.clear()
        visemes.clear()
        disabledAccessories.clear()
        mouthOpen = 0f
        lookTarget = null
    }
}
