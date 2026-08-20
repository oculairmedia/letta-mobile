package com.letta.mobile.testutil

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.StepApi
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ProviderTrace
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.StepFeedbackUpdateParams
import com.letta.mobile.data.model.StepListParams
import com.letta.mobile.data.model.StepMetrics
import io.mockk.mockk
import kotlinx.serialization.json.JsonPrimitive

class FakeStepApi : StepApi(mockk(relaxed = true)) {
    var steps = mutableListOf<Step>()
    var stepMessages = mutableMapOf<String, List<LettaMessage>>()
    var stepMetrics = mutableMapOf<String, StepMetrics>()
    var stepTraces = mutableMapOf<String, ProviderTrace?>()
    var shouldFail = false
    val calls = mutableListOf<String>()

    override suspend fun listSteps(params: StepListParams): List<Step> {
        calls.add("listSteps")
        if (shouldFail) throw ApiException(500, "Server error")
        return steps.filter { step -> params.agentId == null || step.agentId == params.agentId }
    }

    override suspend fun retrieveStep(stepId: String): Step {
        calls.add("retrieveStep:$stepId")
        if (shouldFail) throw ApiException(500, "Server error")
        return steps.firstOrNull { it.id == stepId } ?: sampleStep(stepId)
    }

    override suspend fun retrieveStepMetrics(stepId: String): StepMetrics {
        calls.add("retrieveStepMetrics:$stepId")
        if (shouldFail) throw ApiException(500, "Server error")
        return stepMetrics[stepId] ?: StepMetrics(id = stepId, stepStartNs = 100L, llmRequestNs = 250L, stepNs = 500L)
    }

    override suspend fun retrieveStepTrace(stepId: String): ProviderTrace? {
        calls.add("retrieveStepTrace:$stepId")
        if (shouldFail) throw ApiException(500, "Server error")
        return stepTraces[stepId] ?: ProviderTrace(
            id = "provider_trace-1",
            stepId = stepId,
            createdAt = "2026-04-10T12:00:00Z",
            requestJson = mapOf("model" to JsonPrimitive("gpt-4")),
            responseJson = mapOf("finish_reason" to JsonPrimitive("stop")),
        )
    }

    override suspend fun listStepMessages(stepId: String, before: String?, after: String?, limit: Int?, order: String?): List<LettaMessage> {
        calls.add("listStepMessages:$stepId")
        if (shouldFail) throw ApiException(500, "Server error")
        return stepMessages[stepId] ?: listOf(AssistantMessage(id = "msg-1", contentRaw = null))
    }

    override suspend fun updateStepFeedback(stepId: String, params: StepFeedbackUpdateParams): Step {
        calls.add("updateStepFeedback:$stepId")
        if (shouldFail) throw ApiException(500, "Server error")
        val step = (steps.firstOrNull { it.id == stepId } ?: sampleStep(stepId)).copy(
            feedback = params.feedback,
            tags = params.tags ?: emptyList(),
        )
        val index = steps.indexOfFirst { it.id == stepId }
        if (index >= 0) {
            steps[index] = step
        } else {
            steps.add(step)
        }
        return step
    }

    fun sampleStep(id: String) = Step(
        id = id,
        origin = "sdk",
        organizationId = "org-1",
        providerId = "provider-1",
        runId = "run-1",
        agentId = "agent-1",
        providerName = "OpenAI",
        providerCategory = "llm",
        model = "gpt-4",
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
}
