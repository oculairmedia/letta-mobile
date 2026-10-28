package com.letta.mobile.util

/**
 * Bounded campaign parameters for seeded fuzz families (test-only; not production API).
 *
 * PR corpus remains [FuzzCorpus] (6 seeds × 50 scenarios × 4 families = 1200 cases).
 * Nightly/manual campaigns derive contiguous seeds from [seedBase] without mutating [FuzzCorpus].
 */
internal data class FuzzCampaignParams(
    val seedBase: Long,
    val seedCount: Int,
    val scenariosPerSeed: Int,
) {
    val expectedCasesPerFamily: Int = seedCount * scenariosPerSeed
    val expectedTotalCases: Int = expectedCasesPerFamily * FuzzCampaignLimits.FAMILY_COUNT

    companion object {
        const val PROP_SEED_BASE: String = "letta.fuzz.campaign.seedBase"
        const val PROP_SEED_COUNT: String = "letta.fuzz.campaign.seedCount"
        const val PROP_SCENARIOS_PER_SEED: String = "letta.fuzz.campaign.scenariosPerSeed"
        const val PROP_MANIFEST_PATH: String = "letta.fuzz.campaign.manifestPath"
        const val PROP_GIT_SHA: String = "letta.fuzz.campaign.gitSha"
        const val PROP_WORKFLOW_RUN: String = "letta.fuzz.campaign.workflowRun"
        const val PROP_CAMPAIGN_KIND: String = "letta.fuzz.campaign.kind"
    }
}

internal object FuzzCampaignLimits {
    const val MIN_SEED_COUNT: Int = 1
    const val MAX_SEED_COUNT: Int = 256
    const val MIN_SCENARIOS_PER_SEED: Int = 1
    const val MAX_SCENARIOS_PER_SEED: Int = 1000
    const val FAMILY_COUNT: Int = 4

    val FAMILY_IDS: List<String> = listOf("fleet", "offset", "cursor", "timeline")
}

internal class FuzzCampaignValidationException(
    message: String,
) : IllegalArgumentException(message)

internal fun validate(params: FuzzCampaignParams): FuzzCampaignParams {
    if (params.seedCount < FuzzCampaignLimits.MIN_SEED_COUNT ||
        params.seedCount > FuzzCampaignLimits.MAX_SEED_COUNT
    ) {
        throw FuzzCampaignValidationException(
            "seedCount=${params.seedCount} outside ${FuzzCampaignLimits.MIN_SEED_COUNT}.." +
                FuzzCampaignLimits.MAX_SEED_COUNT,
        )
    }
    if (params.scenariosPerSeed < FuzzCampaignLimits.MIN_SCENARIOS_PER_SEED ||
        params.scenariosPerSeed > FuzzCampaignLimits.MAX_SCENARIOS_PER_SEED
    ) {
        throw FuzzCampaignValidationException(
            "scenariosPerSeed=${params.scenariosPerSeed} outside " +
                "${FuzzCampaignLimits.MIN_SCENARIOS_PER_SEED}.." +
                FuzzCampaignLimits.MAX_SCENARIOS_PER_SEED,
        )
    }
    return params
}

/** Contiguous pagination-family seeds (fleet, offset, cursor share this list). */
internal fun derivePaginationCampaignSeeds(seedBase: Long, seedCount: Int): List<Long> =
    List(seedCount) { index -> seedBase + index }

/**
 * Timeline family uses a disjoint seed space (mirrors [FuzzCorpus.timelineSeeds] vs [FuzzCorpus.paginationSeeds]).
 */
internal fun deriveTimelineCampaignSeeds(seedBase: Long, seedCount: Int): List<Long> =
    List(seedCount) { index -> seedBase + TIMELINE_SEED_SPACE_OFFSET + index }

private const val TIMELINE_SEED_SPACE_OFFSET: Long = 0x91A80C2B_1000L - 0x91A80C2B_0001L
