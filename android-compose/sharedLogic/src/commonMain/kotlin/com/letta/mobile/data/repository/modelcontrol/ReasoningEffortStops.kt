package com.letta.mobile.data.repository.modelcontrol

/**
 * letta-mobile-3io8k: the stops of the reasoning-effort slider for one model, built from the tiers
 * the host lists for it (`list_models`). The first stop is "Default" (null: the provider's own
 * effort, what `reasoning_effort: null` restores), then the tiers lowest to highest.
 *
 * A model with no effort support or a single tier has nothing to choose between, so it gets no
 * slider at all ([of] answers null).
 */
data class ReasoningEffortStops(val values: List<String?>) {
    val count: Int get() = values.size

    /** The stop the model runs at now; Default when its effort is unknown or not one of the stops. */
    fun indexOf(effort: String?): Int {
        val tier = ReasoningTier.of(effort)?.effort ?: return 0
        return values.indexOfFirst { it.equals(tier, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
    }

    fun valueAt(index: Int): String? = values[index.coerceIn(0, values.lastIndex)]

    companion object {
        fun of(efforts: List<String>): ReasoningEffortStops? {
            val tiers = efforts.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
            if (tiers.size < MIN_TIERS) return null
            return ReasoningEffortStops(listOf<String?>(null) + tiers)
        }

        private const val MIN_TIERS = 2
    }
}
