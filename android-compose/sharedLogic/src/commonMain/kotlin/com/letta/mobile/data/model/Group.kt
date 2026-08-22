package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Group(
    val id: GroupId,
    @SerialName("manager_type") val managerType: String,
    @SerialName("agent_ids") val agentIds: List<AgentId> = emptyList(),
    val description: String,
    @SerialName("project_id") val projectId: ProjectId? = null,
    @SerialName("template_id") val templateId: String? = null,
    @SerialName("base_template_id") val baseTemplateId: String? = null,
    @SerialName("deployment_id") val deploymentId: String? = null,
    @SerialName("shared_block_ids") val sharedBlockIds: List<BlockId> = emptyList(),
    @SerialName("manager_agent_id") val managerAgentId: AgentId? = null,
    @SerialName("termination_token") val terminationToken: String? = null,
    @SerialName("max_turns") val maxTurns: Int? = null,
    @SerialName("sleeptime_agent_frequency") val sleeptimeAgentFrequency: Int? = null,
    @SerialName("turns_counter") val turnsCounter: Int? = null,
    @SerialName("last_processed_message_id") val lastProcessedMessageId: String? = null,
    @SerialName("max_message_buffer_length") val maxMessageBufferLength: Int? = null,
    @SerialName("min_message_buffer_length") val minMessageBufferLength: Int? = null,
    val hidden: Boolean? = null,
)

@Serializable
data class GroupCreateParams(
    @SerialName("agent_ids") val agentIds: List<AgentId>,
    val description: String,
    @SerialName("manager_config") val managerConfig: JsonObject? = null,
    @SerialName("project_id") val projectId: ProjectId? = null,
    @SerialName("shared_block_ids") val sharedBlockIds: List<BlockId>? = null,
    val hidden: Boolean? = null,
)

@Serializable
data class GroupUpdateParams(
    @SerialName("agent_ids") val agentIds: List<AgentId>? = null,
    val description: String? = null,
    @SerialName("manager_config") val managerConfig: JsonObject? = null,
    @SerialName("project_id") val projectId: ProjectId? = null,
    @SerialName("shared_block_ids") val sharedBlockIds: List<BlockId>? = null,
    val hidden: Boolean? = null,
)

/** Cursor + filter params for `GET /v1/groups`. */
data class GroupListParams(
    val managerType: String? = null,
    val before: String? = null,
    val after: String? = null,
    val limit: Int? = null,
    val order: String? = null,
    val projectId: String? = null,
    val showHiddenGroups: Boolean? = null,
)

/** Cursor pagination for group messages. */
data class GroupMessagesListParams(
    val groupId: String,
    val limit: Int? = null,
    val before: String? = null,
    val after: String? = null,
    val order: String? = null,
)

/** Iroh `group.list` filters (no cursor pagination on the admin_rpc path). */
data class GroupIrohListParams(
    val managerType: String? = null,
    val projectId: String? = null,
    val showHiddenGroups: Boolean? = null,
)
