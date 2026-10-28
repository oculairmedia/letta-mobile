package com.letta.mobile.util

/** JVM adapter; shared campaign parameters and validation remain platform-neutral. */
internal fun FuzzCampaignParams.Companion.fromSystemProperties(): FuzzCampaignParams {
    val seedBase = readRequiredLongProperty(PROP_SEED_BASE)
    val seedCount = readRequiredIntProperty(PROP_SEED_COUNT)
    val scenariosPerSeed = readRequiredIntProperty(PROP_SCENARIOS_PER_SEED)
    return validate(FuzzCampaignParams(seedBase, seedCount, scenariosPerSeed))
}

private fun readRequiredLongProperty(name: String): Long {
    val raw = System.getProperty(name)?.trim()
        ?: throw FuzzCampaignValidationException("missing system property $name")
    return try {
        when {
            raw.startsWith("0x", ignoreCase = true) || raw.startsWith("0X") ->
                raw.removePrefix("0x").removePrefix("0X").toLong(16)
            else -> raw.toLong()
        }
    } catch (_: NumberFormatException) {
        throw FuzzCampaignValidationException("invalid long for $name: $raw")
    }
}

private fun readRequiredIntProperty(name: String): Int {
    val raw = System.getProperty(name)?.trim()
        ?: throw FuzzCampaignValidationException("missing system property $name")
    return try {
        raw.toInt()
    } catch (_: NumberFormatException) {
        throw FuzzCampaignValidationException("invalid int for $name: $raw")
    }
}
