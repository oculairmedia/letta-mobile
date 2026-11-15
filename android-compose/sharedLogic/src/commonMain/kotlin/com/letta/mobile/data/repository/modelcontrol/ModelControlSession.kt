package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.model.RecentModelsStore
import com.letta.mobile.data.toolsets.EmptyToolsets
import com.letta.mobile.data.toolsets.ToolsetControl
import com.letta.mobile.data.toolsets.ToolsetRuntime
import com.letta.mobile.data.toolsets.ToolsetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One host's provider and catalog repositories, shared by every model-control
 * surface of a client (picker, Models sheet, Providers settings) so a change in
 * one shows in the others at once. Android gets the same sharing from Hilt
 * singletons; desktop holds one of these per data binding. [oauthHttp] runs
 * in-app subscription sign-ins; null keeps the
 * host-terminal steps.
 */
class ModelControlSession(
    private val rpc: AdminRpcInvoker,
    private val oauthHttp: ProviderOAuthHttp? = defaultProviderOAuthHttp(),
    /** Where this client keeps the models switched to most recently (the picker's "Recent" group); null keeps none. */
    val recents: RecentModelsStore? = null,
    /** The live connection's confirmed toolsets; resolved when a picker opens. */
    private val toolsetStates: () -> StateFlow<Map<ToolsetRuntime, ToolsetState>> = { EmptyToolsets },
) {
    val providers: ProviderConnectionRepository = ProviderConnectionRepository(rpc)
    val catalog: ModelCatalogRepository = ModelCatalogRepository(rpc)

    /** A toolset picker for the conversation captured when it opens; null without a target. */
    fun toolsets(scope: CoroutineScope, runtime: ToolsetRuntime?): ToolsetControl? {
        val target = runtime ?: return null
        val statuses = toolsetStates()
        return ToolsetControl(scope, target, statuses) { preference ->
            check(toolsetStates() === statuses) { "The host connection changed; reopen the toolset picker" }
            rpc.invoke("toolset.update", buildJsonObject {
                put("agent_id", target.agentId)
                put("conversation_id", target.conversationId)
                put("toolset_preference", preference)
            })
        }
    }

    fun pickerSource(): ModelPickerSource = ModelPickerSource.catalog(providers, catalog)

    fun managementController(scope: CoroutineScope): ProviderManagementController =
        ProviderManagementController(scope, providers, catalog, oauthHttp)
}
