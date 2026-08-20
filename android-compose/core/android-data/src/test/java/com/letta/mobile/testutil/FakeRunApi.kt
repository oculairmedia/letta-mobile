package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.RunApi
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunMetrics
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.UsageStatistics
import io.mockk.mockk

class FakeRunApi : RunApi(mockk(relaxed = true)) {
    var runs = mutableListOf<Run>()
    var runMessages = mutableMapOf<String, List<LettaMessage>>()
    var runUsage = mutableMapOf<String, UsageStatistics>()
    var runMetrics = mutableMapOf<String, RunMetrics>()
    var runSteps = mutableMapOf<String, List<Step>>()
    var shouldFail = false
    var lastListParams: RunListParams? = null
    val calls = mutableListOf<String>()

    override suspend fun listRuns(params: RunListParams): List<Run> {
        calls.add("listRuns")
        lastListParams = params
        if (shouldFail) throw ApiException(500, "Server error")
        return runs.filter { run ->
            (params.agentId == null || run.agentId == params.agentId) &&
                (params.active == null || (run.status == "running") == params.active)
        }
    }

    override suspend fun retrieveRun(runId: String): Run {
        calls.add("retrieveRun:$runId")
        if (shouldFail) throw ApiException(500, "Server error")
        return runs.firstOrNull { it.id == runId } ?: throw ApiException(404, "Not found")
    }

    override suspend fun listRunMessages(
        runId: String,
        before: String?,
        after: String?,
        limit: Int?,
        order: String?,
    ): List<LettaMessage> {
        calls.add("listRunMessages:$runId")
        if (shouldFail) throw ApiException(500, "Server error")
        return runMessages[runId] ?: listOf(AssistantMessage(id = "msg-1", contentRaw = null))
    }

    override suspend fun retrieveRunUsage(runId: String): UsageStatistics {
        calls.add("retrieveRunUsage:$runId")
        if (shouldFail) throw ApiException(500, "Server error")
        return runUsage[runId] ?: UsageStatistics(promptTokens = 10, completionTokens = 20, totalTokens = 30)
    }

    override suspend fun retrieveRunMetrics(runId: String): RunMetrics {
        calls.add("retrieveRunMetrics:$runId")
        if (shouldFail) throw ApiException(500, "Server error")
        return runMetrics[runId] ?: RunMetrics(
            id = runId,
            organizationId = "org-1",
            agentId = "agent-1",
            projectId = "project-1",
            runStartNs = 100L,
            numSteps = 1,
            runNs = 1000L,
            toolsUsed = listOf("tool-1"),
            templateId = "template-1",
            baseTemplateId = "base-template-1",
        )
    }

    override suspend fun listRunSteps(
        runId: String,
        before: String?,
        after: String?,
        limit: Int?,
        order: String?,
    ): List<Step> {
        calls.add("listRunSteps:$runId")
        if (shouldFail) throw ApiException(500, "Server error")
        return runSteps[runId] ?: listOf(
            Step(
                id = "step-1",
                origin = "sdk",
                organizationId = "org-1",
                providerId = "provider-1",
                runId = runId,
                agentId = "agent-1",
                providerName = "OpenAI",
                providerCategory = "llm",
                model = "model-1",
                modelEndpoint = "https://api.example.com/v1",
                contextWindowLimit = 128000,
                promptTokens = 10,
                completionTokens = 20,
                totalTokens = 30,
                traceId = "trace-1",
                stopReason = "stop",
                tags = listOf("tool-call"),
                tid = "txn-1",
                feedback = "positive",
                projectId = "project-1",
                status = "completed",
            )
        )
    }

    override suspend fun cancelRun(agentId: String, runId: String): Map<String, String> {
        calls.add("cancelRun:$agentId:$runId")
        if (shouldFail) throw ApiException(500, "Server error")
        val index = runs.indexOfFirst { it.id == runId }
        if (index >= 0) {
            runs[index] = runs[index].copy(status = "cancelled", stopReason = "cancelled")
        }
        return mapOf(runId to "cancelled")
    }

    override suspend fun deleteRun(runId: String) {
        calls.add("deleteRun:$runId")
        if (shouldFail) throw ApiException(500, "Server error")
        runs.removeAll { it.id == runId }
    }
}
