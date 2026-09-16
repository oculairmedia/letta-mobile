package com.letta.mobile.avatar.core

/**
 * Normalized expression identity. Presets carry stable keys matching the
 * VRM 1.0 preset expression names, so manifests and renderer adapters agree
 * on spelling without an enum-mapping layer per renderer. VRM 0.x blend-shape
 * presets are normalized to these keys at import.
 */
sealed class AvatarExpression(val key: String) {
    object Neutral : AvatarExpression("neutral")
    object Happy : AvatarExpression("happy")
    object Angry : AvatarExpression("angry")
    object Sad : AvatarExpression("sad")
    object Surprised : AvatarExpression("surprised")
    object Relaxed : AvatarExpression("relaxed")

    /** A model-specific expression declared in the manifest. */
    data class Custom(val name: String) : AvatarExpression(name)

    override fun toString(): String = "AvatarExpression($key)"

    companion object {
        val presets: List<AvatarExpression> =
            listOf(Neutral, Happy, Angry, Sad, Surprised, Relaxed)

        /** Resolve a manifest key back to a preset, else [Custom]. */
        fun fromKey(key: String): AvatarExpression =
            presets.firstOrNull { it.key == key } ?: Custom(key)
    }
}

/** Where the avatar should look. Renderers map this to the rig's gaze inputs. */
sealed interface AvatarLookTarget {
    /**
     * A point in normalized screen space: (0,0) = top-left, (1,1) =
     * bottom-right of the viewport the avatar is rendered into.
     */
    data class Screen(val x: Float, val y: Float) : AvatarLookTarget
}

/**
 * A named gesture (wave, nod, shrug, …). Gestures resolve against the
 * manifest's animation list or a renderer-side procedural library — the id is
 * the only contract.
 */
data class AvatarGesture(val id: String)

/**
 * How the renderer frames the avatar. Surfaces pick a framing for their
 * size/shape (a chat-header chip wants [HEADSHOT], a side dock [BUST], a pet
 * window [FULL_BODY]); renderers map it onto their camera however fits the
 * model's proportions.
 */
enum class AvatarCameraFraming {
    /** Head and shoulders, tightly framed. */
    HEADSHOT,

    /** Waist-up. */
    BUST,

    /** The whole model. */
    FULL_BODY,
}
