package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelCatalog
import com.letta.mobile.data.model.ModelRouteIdentity
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

internal data class RequiredCatalogModel(
    val models: List<LlmModel>,
    val selectionValue: String,
)

/**
 * Handles asynchronous model catalog loading, catalog caching, and routing selection
 * for agents and chat models.
 */
internal class DesktopChatModelCatalogHelper(
    private val scope: CoroutineScope,
    private val agentByIdProvider: suspend (agentIds: Set<String>) -> Map<String, Agent>,
    private val onModelsLoaded: (List<LlmModel>) -> Unit,
    private val getSelectedConversationAgentId: () -> String?,
) {
    private var modelCatalogLoad: Deferred<Result<List<LlmModel>>>? = null

    fun reset() {
        modelCatalogLoad?.cancel()
        modelCatalogLoad = null
        onModelsLoaded(emptyList())
    }

    fun startModelCatalogLoad(
        extras: ChatGatewayExtras,
        replaceCurrent: Boolean = false,
    ): Deferred<Result<List<LlmModel>>> {
        if (!replaceCurrent) modelCatalogLoad?.let { return it }
        modelCatalogLoad?.cancel()
        return scope.async {
            try {
                val models = extras.listLlmModels()
                onModelsLoaded(models)
                Result.success(models)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Telemetry.event(
                    TELEMETRY_TAG,
                    "modelCatalog.loadFailed",
                    "exceptionClass" to (t::class.simpleName ?: "Throwable"),
                    level = Telemetry.Level.WARN,
                )
                Result.failure(t)
            }
        }.also { modelCatalogLoad = it }
    }

    suspend fun requireCatalogModel(
        extras: ChatGatewayExtras,
        availableModels: List<LlmModel>,
        selectedValue: String,
    ): RequiredCatalogModel {
        val initialResult = if (availableModels.isNotEmpty()) {
            Result.success(availableModels)
        } else {
            startModelCatalogLoad(extras).await()
        }
        val result = if (initialResult.isFailure) {
            startModelCatalogLoad(extras, replaceCurrent = true).await()
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

    private suspend fun selectedModelForCurrentAgentRoute(
        models: List<LlmModel>,
        selectedValue: String,
    ): LlmModel? {
        val agentId = getSelectedConversationAgentId()?.takeIf { it.isNotBlank() } ?: return null
        val agent = runCatching { agentByIdProvider(setOf(agentId)) }.getOrNull()?.get(agentId)
            ?.takeIf { it.model == selectedValue }
            ?: return null
        return ModelCatalog.selectedModelForRoute(
            models = models,
            selectedValue = selectedValue,
            routeIdentity = ModelRouteIdentity.from(agent),
        )
    }

    companion object {
        private const val TELEMETRY_TAG = "DesktopChat"
    }
}
