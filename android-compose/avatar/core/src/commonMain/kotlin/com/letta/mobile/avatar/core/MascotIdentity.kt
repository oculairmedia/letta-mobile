package com.letta.mobile.avatar.core

/**
 * The body a user picks for an agent's mascot. Order is the picker's grid order; the Rive asset
 * addresses shapes by key, so reordering here is safe.
 */
enum class MascotShape {
    CIRCLE,
    BLOB,
    ROUNDED_SQUARE,
    PILL,
    TRIANGLE,
    HEXAGON,
    CLOUD,
    DROP,
}

/**
 * What identifies an agent's mascot: a shape and a colour. Identity, not behaviour - it is
 * written into the asset once on load and persisted per agent, while the face is driven by
 * [AvatarState] and never stored.
 *
 * [argb] is packed the way both Rive runtimes take a colour. Kept as an Int rather than a
 * platform colour type so this can live in common code and serialise as one number.
 */
data class MascotIdentity(
    val shape: MascotShape,
    val argb: Int,
) {
    /** `shape:AARRGGBB`, the persisted form. */
    fun encode(): String = "${shape.name.lowercase()}:${argb.toUInt().toString(16).padStart(8, '0')}"

    /**
     * The gradient-orb slot this identity stands in for while the small orbs are still gradients
     * (the rollout's P3 replaces them): its colour's legacy slot when it has one, else a stable
     * pick from the colour so an agent keeps the same orb between sessions.
     */
    fun legacyOrbIndex(): Int {
        val legacy = MascotPalette.LEGACY_ORDER.indexOf(argb)
        if (legacy >= 0) return legacy
        return (MascotPalette.ALL.indexOf(argb).takeIf { it >= 0 } ?: (argb ushr 8)).mod(MascotPalette.LEGACY_ORDER.size)
    }

    companion object {
        val DEFAULT: MascotIdentity = MascotIdentity(MascotShape.CIRCLE, MascotPalette.BLUE)

        /**
         * Reads a persisted identity. Accepts the legacy per-agent avatar style (a bare int
         * indexing the old six gradient orbs) so existing agents keep a stable look: they become a
         * circle in the palette colour at that index.
         */
        fun decode(value: String?): MascotIdentity? {
            if (value.isNullOrBlank()) return null
            value.toIntOrNull()?.let { legacy ->
                return MascotIdentity(MascotShape.CIRCLE, MascotPalette.LEGACY_ORDER[legacy.mod(MascotPalette.LEGACY_ORDER.size)])
            }
            val shapeName = value.substringBefore(':')
            val hex = value.substringAfter(':', "")
            val shape = MascotShape.entries.firstOrNull { it.name.equals(shapeName, ignoreCase = true) } ?: return null
            if (hex.length != 8) return null
            val argb = hex.toUIntOrNull(16)?.toInt() ?: return null
            return MascotIdentity(shape, argb)
        }
    }
}

/** The colours the picker offers. The asset accepts any colour; this is the product's choice. */
object MascotPalette {
    const val WHITE: Int = 0xFFFFFFFF.toInt()
    const val BROWN: Int = 0xFF8B5A2B.toInt()
    const val RED: Int = 0xFFE5484D.toInt()
    const val ORANGE: Int = 0xFFFF6A1A.toInt()
    const val AMBER: Int = 0xFFFFA21A.toInt()
    const val GREEN: Int = 0xFF1DA34A.toInt()
    const val TEAL: Int = 0xFF14A08A.toInt()
    const val BLUE: Int = 0xFF1E7BF0.toInt()
    const val PURPLE: Int = 0xFF8E5CFF.toInt()
    const val PINK: Int = 0xFFE0457B.toInt()
    const val GREY: Int = 0xFF7A7A7A.toInt()

    /** Picker order: two rows of five, grey on its own below. */
    val ALL: List<Int> = listOf(WHITE, BROWN, RED, ORANGE, AMBER, GREEN, TEAL, BLUE, PURPLE, PINK, GREY)

    /** Legacy avatar-style index → colour, matching the hue each old gradient led with. */
    val LEGACY_ORDER: List<Int> = listOf(AMBER, PINK, BLUE, GREEN, PURPLE, TEAL)
}
