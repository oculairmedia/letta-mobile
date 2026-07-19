package com.letta.mobile.cli.commands

import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.iroh.IrohProbeAssertions
import com.letta.mobile.data.transport.iroh.IrohProbeTurnMetrics
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class HydrateHeavyProbeScenario(
    private val options: IrohProbeOptions,
    private val fixture: ProbeSessionFixture,
    private val admin: ProbeAdminClient,
) {
    suspend fun run(target: ProbeTarget): IrohProbeTurnMetrics {
        val seeded = try {
            postSeed(target.conversationId)
        } catch (error: Exception) {
            return IrohProbeTurnMetrics(
                turn = 1,
                scenario = ProbeScenarioName.HydrateHeavy.value,
                profile = IrohProbeAssertions.PROFILE_REPORT,
                scenarioViolations = listOf("hydrate_seed_failed:${error.message ?: error}"),
            )
        }
        return pageAfterSeed(target, seeded)
    }

    private fun postSeed(conversationId: ProbeConversationId): Int {
        val response = admin.json(
            ProbeHttpMethod.Post,
            ProbeHttpPath("/probe/seed"),
            ProbeJsonBody(
                buildJsonObject {
                    put("conversation_id", conversationId.value)
                    put("count", options.seedMessages)
                    put("payload_bytes", options.payloadBytes)
                }.toString(),
            ),
        ).jsonObject
        return response["seeded"]?.jsonPrimitive?.longOrNull?.toInt() ?: options.seedMessages
    }

    private suspend fun pageAfterSeed(target: ProbeTarget, seeded: Int): IrohProbeTurnMetrics {
        val scope = newProbeScope()
        var session: ProbeSession? = null
        return try {
            val startedAt = nowMs()
            withTimeoutOrNull(options.timeoutMs) {
                val established = fixture.establish(
                    ProbeEstablishRequest(
                        target = target,
                        scope = scope,
                        turn = 1,
                        turnStartedAt = startedAt,
                    ),
                )
                session = established
                val pages = listAllPages(established, target.conversationId, seeded)
                buildHydrateMetrics(established, seeded, pages, nowMs() - startedAt)
            } ?: hydrateTimeoutMetrics()
        } catch (error: Throwable) {
            IrohProbeTurnMetrics(
                turn = 1,
                scenario = ProbeScenarioName.HydrateHeavy.value,
                profile = IrohProbeAssertions.PROFILE_REPORT,
                scenarioViolations = listOf("hydrate_heavy_failed:${error.message ?: error}"),
            )
        } finally {
            fixture.close(session, scope)
        }
    }

    private fun hydrateTimeoutMetrics() = IrohProbeTurnMetrics(
        turn = 1,
        scenario = ProbeScenarioName.HydrateHeavy.value,
        profile = IrohProbeAssertions.PROFILE_REPORT,
        timedOut = true,
        scenarioViolations = listOf("hydrate_heavy_timeout_after_${options.timeoutMs}ms"),
    )

    private fun buildHydrateMetrics(
        established: ProbeSession,
        seeded: Int,
        pages: HydratePageResult,
        wallMs: Long,
    ): IrohProbeTurnMetrics = IrohProbeTurnMetrics(
        turn = 1,
        scenario = ProbeScenarioName.HydrateHeavy.value,
        profile = IrohProbeAssertions.PROFILE_REPORT,
        dialMs = established.dialMs,
        wallMs = wallMs,
        scenarioViolations = IrohProbeAssertions.classifyHydrateHeavy(
            seeded, pages.listed, wallMs, options.hydrateBudgetMs, pages.failures,
        ),
        notes = listOf(
            "hydrate_seeded=$seeded",
            "hydrate_listed=${pages.listed}",
            "hydrate_page_limit=${pages.pageLimit}",
            "hydrate_total_bytes=${seeded.toLong() * options.payloadBytes}",
        ),
    )

    private suspend fun listAllPages(
        established: ProbeSession,
        conversationId: ProbeConversationId,
        seeded: Int,
    ): HydratePageResult {
        val pageLimit = ((700 * 1024) / options.payloadBytes).coerceIn(1, 100)
        var after: String? = null
        var listed = 0
        val failures = mutableListOf<String>()
        var page = 0
        val maxPages = (seeded / pageLimit) + 5
        while (page < maxPages) {
            page += 1
            val pageResult = fetchHydratePage(
                HydratePageRequest(
                    session = established,
                    conversationId = conversationId,
                    pageLimit = pageLimit,
                    after = after,
                    page = page,
                ),
            )
            val failure = pageResult.failure
            if (failure != null) {
                failures += failure
                break
            }
            listed += pageResult.itemCount
            if (pageResult.done) break
            after = pageResult.nextAfter
            if (after == null) {
                failures += "page-$page: last item missing id"
                break
            }
        }
        return HydratePageResult(listed = listed, pageLimit = pageLimit, failures = failures)
    }

    private data class HydratePageRequest(
        val session: ProbeSession,
        val conversationId: ProbeConversationId,
        val pageLimit: Int,
        val after: String?,
        val page: Int,
    )

    private data class PageFetch(
        val itemCount: Int = 0,
        val nextAfter: String? = null,
        val done: Boolean = false,
        val failure: String? = null,
    )

    private suspend fun fetchHydratePage(request: HydratePageRequest): PageFetch {
        val response = request.session.client.adminRpc(
            AppServerCommand.AdminRpc(
                requestId = "probe-hydrate-${request.page}-${UUID.randomUUID()}",
                method = "message.list",
                params = buildJsonObject {
                    put("conversation_id", request.conversationId.value)
                    put("limit", request.pageLimit.toString())
                    request.after?.let { put("after", it) }
                },
            ),
        )
        val items = response.result as? JsonArray
        if (!response.success || items == null) {
            return PageFetch(failure = "page-${request.page}: ${response.error ?: "non-array result"}")
        }
        if (items.isEmpty() || items.size < request.pageLimit) {
            return PageFetch(itemCount = items.size, done = true)
        }
        val nextAfter = items.last().jsonObject["id"]?.jsonPrimitive?.contentOrNull
        return PageFetch(itemCount = items.size, nextAfter = nextAfter, done = false)
    }
}
