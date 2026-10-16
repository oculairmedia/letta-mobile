package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelCatalog
import com.letta.mobile.data.model.ModelRouteIdentity
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** A catalog model a create named, resolved against the loaded list. */
data class RequiredCatalogModel(
    val models: List<LlmModel>,
    val selectionValue: String,
)

/** What [ChatModelCatalogLoader] reads from its owner. */
class ChatModelCatalogSources(
    val agentByIdProvider: suspend (agentIds: Set<String>) -> Map<String, Agent>,
    val onModelsLoaded: (List<LlmModel>) -> Unit,
    /** The selected conversation's agent, whose route a create's model may name. */
    val selectedAgent: () -> AgentId?,
)

/**
 * letta-mobile-ckmub.17.2 (moved from desktop): loads a chat's model list asynchronously, keeps
 * the one load in flight, and resolves a create's model against it (routing included).
 */
class ChatModelCatalogLoader(
    private val scope: CoroutineScope,
    private val sources: ChatModelCatalogSources,
) {
    private var modelCatalogLoad: Deferred<Result<List<LlmModel>>>? = null

    fun reset() {
        modelCatalogLoad?.cancel()
        modelCatalogLoad = null
        sources.onModelsLoaded(emptyList())
    }

    fun startModelCatalogLoad(
        backend: ConversationModelBackend,
        replaceCurrent: Boolean = false,
    ): Deferred<Result<List<LlmModel>>> {
        if (!replaceCurrent) modelCatalogLoad?.let { return it }
        modelCatalogLoad?.cancel()
        return scope.async {
            runCatching { backend.listModels() }
                .onSuccess(sources.onModelsLoaded)
                .onFailure(::reportLoadFailure)
        }.also { modelCatalogLoad = it }
    }

    suspend fun requireCatalogModel(
        backend: ConversationModelBackend,
        availableModels: List<LlmModel>,
        selectedValue: String,
    ): RequiredCatalogModel {
        val initialResult = if (availableModels.isNotEmpty()) {
            Result.success(availableModels)
        } else {
            startModelCatalogLoad(backend).await()
        }
        val result = if (initialResult.isFailure) {
            startModelCatalogLoad(backend, replaceCurrent = true).await()
        } else {
            initialResult
        }
        val models = result.getOrElse { cause ->
            throw IllegalStateException("Model catalog is unavailable; retry agent creation.", cause)
        }
        val selectedModel = ModelCatalog.selectedModel(models, selectedValue)
            ?: selectedModelForCurrentAgentRoute(models, selectedValue)
        requireNotNull(selectedModel) {
            "Selected model is not available in the current catalog; choose a model and retry."
        }
        return RequiredCatalogModel(
            models = models,
            selectionValue = ModelCatalog.selectionValue(models, selectedModel),
        )
    }

    /** A cancelled load stays cancelled; any other failure is the load's result, and is reported. */
    private fun reportLoadFailure(failure: Throwable) {
        if (failure is CancellationException) throw failure
        Telemetry.event(
            TELEMETRY_TAG,
            "modelCatalog.loadFailed",
            "exceptionClass" to (failure::class.simpleName ?: "Throwable"),
            level = Telemetry.Level.WARN,
        )
    }

    private suspend fun selectedModelForCurrentAgentRoute(
        models: List<LlmModel>,
        selectedValue: String,
    ): LlmModel? {
        val agentId = sources.selectedAgent()?.value?.takeIf { it.isNotBlank() } ?: return null
        val agent = runCatching { sources.agentByIdProvider(setOf(agentId)) }.getOrNull()?.get(agentId)
            ?.takeIf { it.model == selectedValue }
            ?: return null
        return ModelCatalog.selectedModelForRoute(
            models = models,
            selectedValue = selectedValue,
            routeIdentity = ModelRouteIdentity.from(agent),
        )
    }

    private companion object {
        const val TELEMETRY_TAG = "ChatModels"
    }
}
