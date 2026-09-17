package com.letta.mobile.avatar.core

/**
 * The body a user picks for an agent's mascot. Order is the picker's grid order; the Rive asset
 * addresses shapes by key, and generated identities draw from their own frozen
 * [MascotIdentity.SEEDED_SHAPES], so adding or reordering entries here changes neither.
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
 * What identifies an agent's mascot: a shape, a colour and how the shape is turned. Identity, not
 * behaviour - it is written into the asset once on load and persisted per agent, while the face is
 * driven by [AvatarState] and never stored.
 *
 * [argb] is packed the way both Rive runtimes take a colour. Kept as an Int rather than a
 * platform colour type so this can live in common code and serialise as one number.
 * [rotationDegrees] turns the body clockwise about its centre, 0 until 359; the face and the
 * lighting stay upright.
 */
data class MascotIdentity(
    val shape: MascotShape,
    val argb: Int,
    val rotationDegrees: Int = 0,
) {
    init {
        require(rotationDegrees in 0 until FULL_TURN) { "rotationDegrees must be 0..359, was $rotationDegrees" }
    }

    /**
     * `shape:AARRGGBB`, or `shape:AARRGGBB:degrees` when the body is turned - the persisted form. An
     * unturned identity keeps the two-part form, so every value written before rotation existed
     * still reads back unchanged, and older clients still read unturned identities.
     */
    fun encode(): String {
        val base = "${shape.name.lowercase()}:${argb.toUInt().toString(16).padStart(8, '0')}"
        return if (rotationDegrees == 0) base else "$base:$rotationDegrees"
    }

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
        const val FULL_TURN: Int = 360

        /** How far apart the turns a generated identity can take are. */
        const val SEEDED_ROTATION_STEP: Int = 45

        /**
         * The shapes a generated identity draws from. FROZEN: [seeded] takes a value modulo this
         * list's size, so adding, removing or reordering anything here changes the mascot of every
         * agent nobody picked one for. A new [MascotShape] joins the picker only; giving generated
         * identities new options needs a versioned seeding scheme, not an edit here.
         */
        val SEEDED_SHAPES: List<MascotShape> = listOf(
            MascotShape.CIRCLE,
            MascotShape.BLOB,
            MascotShape.ROUNDED_SQUARE,
            MascotShape.PILL,
            MascotShape.TRIANGLE,
            MascotShape.HEXAGON,
            MascotShape.CLOUD,
            MascotShape.DROP,
        )

        val DEFAULT: MascotIdentity = MascotIdentity(MascotShape.CIRCLE, MascotPalette.BLUE)

        /** [degrees] folded into 0..359, so -90 is 270 and 450 is 90. */
        fun normalizeRotation(degrees: Int): Int = degrees.mod(FULL_TURN)

        /**
         * The identity [fraction] of the way from [from] to [to] - what a mascot mid-morph draws.
         * The shape is [to]'s from the first step, because the asset morphs the outline itself when
         * the shape input changes; only the colour (mixed per channel) and the turn (the shorter
         * arc, so 350 to 10 passes through 0, not 180) are the host's to ease.
         */
        fun lerp(from: MascotIdentity, to: MascotIdentity, fraction: Float): MascotIdentity {
            val t = fraction.coerceIn(0f, 1f)
            if (t >= 1f) return to
            var delta = (to.rotationDegrees - from.rotationDegrees).mod(FULL_TURN)
            if (delta > FULL_TURN / 2) delta -= FULL_TURN
            val rotation = normalizeRotation(from.rotationDegrees + (delta * t).toInt())
            return MascotIdentity(to.shape, lerpArgb(from.argb, to.argb, t), rotation)
        }

        private fun lerpArgb(from: Int, to: Int, t: Float): Int {
            fun channel(shift: Int): Int {
                val a = (from ushr shift) and 0xff
                val b = (to ushr shift) and 0xff
                return (a + (b - a) * t + 0.5f).toInt().coerceIn(0, 0xff)
            }
            return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

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
            val parts = value.split(':')
            if (parts.size !in 2..3) return null
            val shape = MascotShape.entries.firstOrNull { it.name.equals(parts[0], ignoreCase = true) } ?: return null
            val argb = parts[1].takeIf { it.length == 8 }?.toUIntOrNull(16)?.toInt() ?: return null
            val rotation = parts.getOrNull(2)?.let { it.toIntOrNull() ?: return null } ?: 0
            return MascotIdentity(shape, argb, normalizeRotation(rotation))
        }

        /**
         * The identity an agent has before anyone picks one: a shape, a colour and a turn drawn from
         * a generator seeded by [agentId]. The id never changes, so every client derives the same
         * mascot for the same agent without storing anything; a choice written to the agent still
         * wins. The generator is [SeededRandom], not `kotlin.random`, because the sequence must never
         * change underneath existing agents between Kotlin releases or platforms.
         */
        fun seeded(agentId: String): MascotIdentity {
            val random = SeededRandom(SeededRandom.seedOf(agentId))
            val shape = SEEDED_SHAPES[random.nextInt(SEEDED_SHAPES.size)]
            val argb = MascotPalette.SEEDED[random.nextInt(MascotPalette.SEEDED.size)]
            val rotation = random.nextInt(FULL_TURN / SEEDED_ROTATION_STEP) * SEEDED_ROTATION_STEP
            return MascotIdentity(shape, argb, rotation)
        }
    }
}

/**
 * A small deterministic generator whose output is fixed by this file alone: FNV-1a over the seed's
 * UTF-8 bytes, then SplitMix64. Stable across platforms and Kotlin versions, unlike `kotlin.random`.
 */
class SeededRandom(seed: Long) {
    private var state: Long = seed

    fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    /** Uniform in 0 until [bound]. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        return ((nextLong() ushr 1) % bound).toInt()
    }

    companion object {
        private const val GOLDEN_GAMMA = -0x61c8864680b583ebL
        private const val MIX_1 = -0x40a7b892e31b1a47L
        private const val MIX_2 = -0x6b2fb644ecceee15L
        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L

        /** FNV-1a 64 over [value]'s UTF-8 bytes. */
        fun seedOf(value: String): Long =
            value.encodeToByteArray().fold(FNV_OFFSET) { hash, byte -> (hash xor (byte.toLong() and 0xff)) * FNV_PRIME }
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

    /**
     * The colours a generated identity draws from: the saturated ones. White and grey read as
     * "unset" next to a coloured agent, so they stay a deliberate pick. FROZEN, like
     * [MascotIdentity.SEEDED_SHAPES]: even appending changes the modulo bound and so every generated
     * identity. New colours join [ALL] (the picker) only.
     */
    val SEEDED: List<Int> = listOf(BROWN, RED, ORANGE, AMBER, GREEN, TEAL, BLUE, PURPLE, PINK)

    /** Legacy avatar-style index → colour, matching the hue each old gradient led with. */
    val LEGACY_ORDER: List<Int> = listOf(AMBER, PINK, BLUE, GREEN, PURPLE, TEAL)
}
