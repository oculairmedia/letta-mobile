package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Group
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Group reads over the Iroh admin RPC control channel.
 *
 * P4 purity client batch (letta-mobile): server handlers exist (ArchiveAdminHandlers
 * registers group.list); this is the client wiring.
 */
class IrohAdminRpcGroupSource(
    private val channelTransport: IChannelTransport,
    private val settingsRepository: ISettingsRepository,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    },
) {
    fun shouldUseIroh(): Boolean =
        IrohChannelTransport.shouldUseIroh(settingsRepository.activeConfig.value?.serverUrl)

    suspend fun listGroups(
        managerType: String? = null,
        projectId: String? = null,
        showHiddenGroups: Boolean? = null,
    ): List<Group> {
        val params = buildJsonObject {
            managerType?.let { put("manager_type", it) }
            projectId?.let { put("project_id", it) }
            showHiddenGroups?.let { put("show_hidden_groups", it) }
        }
        val path = buildString {
            append("/v1/groups")
            val queryParts = listOfNotNull(
                managerType?.let { "manager_type=$it" },
                projectId?.let { "project_id=$it" },
                showHiddenGroups?.let { "show_hidden_groups=$it" },
            )
            if (queryParts.isNotEmpty()) {
                append(queryParts.joinToString("&", prefix = "?"))
            }
        }
        val response = channelTransport.adminRpc(
            method = "group.list",
            path = path,
            body = params.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc group.list failed")
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Group.serializer()), result)
    }
}
