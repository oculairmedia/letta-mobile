package com.letta.mobile.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PaginationReplayFuzzCampaignConfigTest {
    @Test
    fun validateAcceptsNightlyShape() {
        val params = validate(
            FuzzCampaignParams(
                seedBase = 0x91A80C2B_0001L,
                seedCount = 32,
                scenariosPerSeed = 200,
            ),
        )
        assertEquals(32 * 200 * 4, params.expectedTotalCases)
    }

    @Test
    fun validateRejectsZeroSeedCount() {
        assertFailsWith<FuzzCampaignValidationException> {
            validate(FuzzCampaignParams(1L, 0, 50))
        }
    }

    @Test
    fun validateRejectsOversizedSeedCount() {
        assertFailsWith<FuzzCampaignValidationException> {
            validate(FuzzCampaignParams(1L, 257, 50))
        }
    }

    @Test
    fun validateRejectsOversizedScenarioCount() {
        assertFailsWith<FuzzCampaignValidationException> {
            validate(FuzzCampaignParams(1L, 6, 1001))
        }
    }

    @Test
    fun paginationAndTimelineSeedSpacesStayDisjoint() {
        val pagination = derivePaginationCampaignSeeds(0x100L, 4)
        val timeline = deriveTimelineCampaignSeeds(0x100L, 4)
        assertTrue(pagination.intersect(timeline.toSet()).isEmpty())
        assertEquals(listOf(0x100L, 0x101L, 0x102L, 0x103L), pagination)
    }

    @Test
    fun deriveSeedsIsDeterministic() {
        val first = derivePaginationCampaignSeeds(42L, 5)
        val second = derivePaginationCampaignSeeds(42L, 5)
        assertEquals(first, second)
    }

    @Test
    fun fromSystemPropertiesRejectsOversizedSeedCount() {
        val prior = System.getProperties().stringPropertyNames().associateWith { System.getProperty(it) }
        try {
            System.setProperty(FuzzCampaignParams.PROP_SEED_BASE, "1")
            System.setProperty(FuzzCampaignParams.PROP_SEED_COUNT, "999")
            System.setProperty(FuzzCampaignParams.PROP_SCENARIOS_PER_SEED, "3")
            assertFailsWith<FuzzCampaignValidationException> {
                FuzzCampaignParams.fromSystemProperties()
            }
        } finally {
            prior.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }
}
