package com.letta.mobile.util

import com.letta.mobile.data.timeline.TimelineStreamReplayFuzzTest
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM-only bounded campaign entry point. Excluded from default [jvmTest]; run via
 * `:sharedLogic:paginationReplayFuzzCampaign`.
 */
class PaginationReplayFuzzCampaignRunnerTest {
    @Test
    fun runBoundedCampaignFromSystemProperties() = runTest {
        val params = FuzzCampaignParams.fromSystemProperties()
        val manifestPath = System.getProperty(FuzzCampaignParams.PROP_MANIFEST_PATH)?.trim()
            ?: throw FuzzCampaignValidationException("missing ${FuzzCampaignParams.PROP_MANIFEST_PATH}")
        val paginationSeeds = derivePaginationCampaignSeeds(params.seedBase, params.seedCount)
        val timelineSeeds = deriveTimelineCampaignSeeds(params.seedBase, params.seedCount)

        // Preserve the exact planned seeds even if the first generated family throws.
        val manifestFile = File(manifestPath)
        manifestFile.parentFile?.mkdirs()
        manifestFile.writeText(
            buildManifest(params, paginationSeeds, timelineSeeds, emptyMap()),
        )

        val fleetCases = AgentConversationFleetFuzzTest.runFleetPaginationCampaign(
            paginationSeeds,
            params.scenariosPerSeed,
        )
        val offsetCases = PaginationHelpersFuzzTest.runOffsetExhaustCampaign(
            paginationSeeds,
            params.scenariosPerSeed,
        )
        val cursorCases = PaginationHelpersFuzzTest.runCursorExhaustCampaign(
            paginationSeeds,
            params.scenariosPerSeed,
        )
        val timelineCases = TimelineStreamReplayFuzzTest.runTimelineReplayCampaign(
            timelineSeeds,
            params.scenariosPerSeed,
        )

        val actualByFamily = linkedMapOf(
            "fleet" to fleetCases,
            "offset" to offsetCases,
            "cursor" to cursorCases,
            "timeline" to timelineCases,
        )
        actualByFamily.forEach { (family, count) ->
            assertEquals(
                params.expectedCasesPerFamily,
                count,
                "family=$family case count",
            )
        }
        val actualTotal = actualByFamily.values.sum()
        assertEquals(params.expectedTotalCases, actualTotal)

        manifestFile.writeText(buildManifest(params, paginationSeeds, timelineSeeds, actualByFamily))
        assertTrue(manifestFile.isFile && manifestFile.length() > 0L, "manifest not written: $manifestPath")
    }

    private fun buildManifest(
        params: FuzzCampaignParams,
        paginationSeeds: List<Long>,
        timelineSeeds: List<Long>,
        actualByFamily: Map<String, Int>,
    ): String = buildString {
        val actualTotal = actualByFamily.values.sum()
        val completionStatus = if (actualTotal == params.expectedTotalCases) "complete" else "planned"
        appendLine("{")
        appendLine("  \"kind\": ${jsonString(System.getProperty(FuzzCampaignParams.PROP_CAMPAIGN_KIND) ?: "manual")},")
        appendLine("  \"gitSha\": ${jsonString(System.getProperty(FuzzCampaignParams.PROP_GIT_SHA) ?: "unknown")},")
        appendLine("  \"workflowRun\": ${jsonString(System.getProperty(FuzzCampaignParams.PROP_WORKFLOW_RUN) ?: "local")},")
        appendLine("  \"seedBase\": ${params.seedBase},")
        appendLine("  \"seedCount\": ${params.seedCount},")
        appendLine("  \"scenariosPerSeed\": ${params.scenariosPerSeed},")
        appendLine("  \"expectedCasesPerFamily\": ${params.expectedCasesPerFamily},")
        appendLine("  \"expectedTotalCases\": ${params.expectedTotalCases},")
        appendLine("  \"actualTotalCases\": $actualTotal,")
        appendLine("  \"completionStatus\": \"$completionStatus\",")
        appendLine(
            "  \"families\": [${
                FuzzCampaignLimits.FAMILY_IDS.joinToString { jsonString(it) }
            }],",
        )
        appendLine("  \"actualCasesByFamily\": {")
        actualByFamily.entries.forEachIndexed { index, (family, count) ->
            val comma = if (index < actualByFamily.size - 1) "," else ""
            appendLine("    ${jsonString(family)}: $count$comma")
        }
        appendLine("  },")
        appendLine("  \"paginationSeedsSample\": [${paginationSeeds.take(3).joinToString()}],")
        appendLine("  \"timelineSeedsSample\": [${timelineSeeds.take(3).joinToString()}],")
        appendLine(
            "  \"knownLimitations\": [" +
                "\"fleet ordering oracle uses ConversationRecency.sortKeyMillis on generated ISO-Z timestamps\"" +
                "]",
        )
        appendLine("}")
    }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
