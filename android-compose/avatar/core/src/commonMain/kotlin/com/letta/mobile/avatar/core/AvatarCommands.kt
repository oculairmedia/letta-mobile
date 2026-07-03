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

/**
 * Mouth-shape identity for lip sync. Keys follow the VRM 1.0 preset viseme
 * names (`aa`/`ih`/`ou`/`ee`/`oh`); VRM 0.x `A`/`I`/`U`/`E`/`O` blend shapes
 * are normalized to these at import.
 */
sealed class AvatarViseme(val key: String) {
    object A : AvatarViseme("aa")
    object I : AvatarViseme("ih")
    object U : AvatarViseme("ou")
    object E : AvatarViseme("ee")
    object O : AvatarViseme("oh")
    object Closed : AvatarViseme("closed")

    /** A model-specific viseme declared in the manifest. */
    data class Custom(val name: String) : AvatarViseme(name)

    override fun toString(): String = "AvatarViseme($key)"

    companion object {
        val presets: List<AvatarViseme> = listOf(A, I, U, E, O, Closed)

        /** Resolve a manifest key back to a preset, else [Custom]. */
        fun fromKey(key: String): AvatarViseme =
            presets.firstOrNull { it.key == key } ?: Custom(key)
    }
}

/** Where the avatar should look. Renderers map this to head/eye bones. */
sealed interface AvatarLookTarget {
    /** A point in world space (renderer scene units). */
    data class World(val x: Float, val y: Float, val z: Float) : AvatarLookTarget

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
