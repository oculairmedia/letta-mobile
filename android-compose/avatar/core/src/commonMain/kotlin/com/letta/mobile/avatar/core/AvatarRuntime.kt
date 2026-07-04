package com.letta.mobile.avatar.core

import kotlinx.coroutines.flow.StateFlow

/** Lifecycle of an [AvatarRuntime] as observed by the app. */
sealed interface AvatarRuntimeState {
    /** No avatar loaded. */
    data object Idle : AvatarRuntimeState

    /** [model] is being fetched/parsed/uploaded to the renderer. */
    data class Loading(val model: AvatarModel) : AvatarRuntimeState

    /**
     * [model] is live. [capabilities] are the renderer-verified capabilities
     * (a renderer may downgrade manifest claims it can't honor yet, e.g. a
     * Filament build without spring bones).
     */
    data class Ready(
        val model: AvatarModel,
        val capabilities: AvatarCapabilities,
    ) : AvatarRuntimeState

    /** A load or runtime failure; the previous avatar (if any) is unloaded. */
    data class Failed(
        val model: AvatarModel?,
        val message: String,
    ) : AvatarRuntimeState
}

/**
 * Renderer-independent avatar control surface. The app talks ONLY to this
 * interface; Filament, three-vrm/WebView, or future renderers implement it.
 *
 * Command semantics:
 * - Commands issued while not [AvatarRuntimeState.Ready] are best-effort and
 *   may be dropped; callers gate persistent intent on [state].
 * - Weight-style setters ([setExpression], [setViseme], [setMouthOpen]) are
 *   level controls, not events: the value holds until changed. Weights are
 *   clamped to 0..1 by implementations.
 * - Commands targeting capabilities the model lacks (see
 *   [AvatarRuntimeState.Ready.capabilities]) are silently ignored, so the app
 *   can drive one code path for every asset.
 */
interface AvatarRuntime {
    val state: StateFlow<AvatarRuntimeState>

    /**
     * Load [model], replacing any currently-loaded avatar. [animations] are
     * user-provided standard animations (VRMA/Mixamo FBX) to retarget onto the
     * model and register in its clip registry (playable by id via
     * [playAnimation]); default empty. Renderers without animation-import
     * support (or headless runtimes) simply record them.
     */
    suspend fun load(model: AvatarModel, animations: List<AvatarAnimationSource> = emptyList())

    /** Unload the current avatar and return to [AvatarRuntimeState.Idle]. */
    suspend fun unload()

    fun setExpression(expression: AvatarExpression, weight: Float = 1f)

    fun setViseme(viseme: AvatarViseme, weight: Float)

    /** Direct jaw/mouth-open level (0..1), e.g. from audio amplitude. */
    fun setMouthOpen(value: Float)

    /** Aim head/eyes at [target]; `null` returns to the idle gaze. */
    fun setLookTarget(target: AvatarLookTarget?)

    fun playGesture(gesture: AvatarGesture, fadeSeconds: Float = 0.2f)

    /** Play an embedded glTF animation by manifest animation id. */
    fun playAnimation(animationId: String, loop: Boolean = false)

    fun setAccessoryEnabled(accessoryId: String, enabled: Boolean)

    /**
     * Ask the renderer to frame the avatar for the hosting surface. Default
     * no-op so renderer-less implementations stay source-compatible.
     */
    fun setCameraFraming(framing: AvatarCameraFraming) {}

    /** Advance renderer-side time (spring bones, blends, procedural idle). */
    fun update(deltaSeconds: Float)

    /** Release all renderer resources. The runtime is unusable afterwards. */
    fun dispose()
}
