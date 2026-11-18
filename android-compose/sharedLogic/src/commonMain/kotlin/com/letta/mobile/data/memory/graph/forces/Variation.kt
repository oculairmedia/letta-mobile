package com.letta.mobile.data.memory.graph.forces

/**
 * Seeded per-element variation for the memory graph's forces: an element's id hashes to a stable
 * seed ([hash]), and each varied constant mixes that seed with its own salt, so the variations are
 * independent of each other and never change when unrelated elements are added. A [factor] is
 * 1 ± spread; a spread of 0 is exactly 1.
 */
object Variation {
    /** Salts: one per varied constant, so a node's charge and radius vary independently. */
    const val CHARGE: Int = 0x1B873593
    const val RADIUS: Int = 0x5BD1E995
    const val GRAVITY: Int = 0x27D4EB2F
    const val CLUSTER: Int = 0x165667B1
    const val LENGTH: Int = 0x7FEB352D
    const val STIFFNESS: Int = -2073254261

    /** [text]'s FNV-1a hash: the same on every platform, unlike [String.hashCode]'s contract. */
    fun hash(text: String): Int {
        var hash = FNV_OFFSET
        text.forEach { char -> hash = (hash xor char.code) * FNV_PRIME }
        return hash
    }

    /** A spring's seed: its two ends' ids in either order, so the edge's direction does not matter. */
    fun hashPair(first: String, second: String): Int = hash(minOf(first, second) + SEPARATOR + maxOf(first, second))

    /** 1 plus [spread] times a value in [-1, 1) drawn from [seed] mixed with [salt]; exactly 1 for spread 0. */
    fun factor(seed: Int, salt: Int, spread: Float): Float {
        if (spread == 0f) return 1f
        var mixed = seed xor salt
        mixed = (mixed xor (mixed ushr SHIFT_A)) * MIX_A
        mixed = (mixed xor (mixed ushr SHIFT_B)) * MIX_B
        mixed = mixed xor (mixed ushr SHIFT_A)
        val unit = (mixed ushr UNIT_SHIFT).toFloat() / UNIT_RANGE
        return 1f + spread * (2f * unit - 1f)
    }

    private const val FNV_OFFSET = -2128831035
    private const val FNV_PRIME = 16777619
    private const val SEPARATOR = '\u0000'
    private const val SHIFT_A = 16
    private const val SHIFT_B = 13
    private const val MIX_A = -2048144789
    private const val MIX_B = -1028477387
    private const val UNIT_SHIFT = 8
    private const val UNIT_RANGE = 16_777_216f
}
