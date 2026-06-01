package com.letta.mobile.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ProjectSummary(
    val id: ProjectId? = null,
    val identifier: String,
    val name: String,
    @SerialName("filesystem_path") val filesystemPath: String? = null,
    @SerialName("git_url") val gitUrl: String? = null,
    val status: String? = null,
    @SerialName("vibe_id") val vibeId: String? = null,
    @SerialName("huly_id") val hulyId: String? = null,
    @SerialName("letta_agent_id") val lettaAgentId: AgentId? = null,
    @SerialName("letta_folder_id") val lettaFolderId: String? = null,
    @SerialName("letta_source_id") val lettaSourceId: String? = null,
    @SerialName("issue_count") val issueCount: Int? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("tech_stack") val techStack: String? = null,
    @SerialName("beads_issue_count") val beadsIssueCount: Int? = null,
    @SerialName("beads_prefix") val beadsPrefix: String? = null,
    val description: String? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("last_scan_at") val lastScanAt: String? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("last_sync_at") val lastSyncAt: String? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("last_checked_at") val lastCheckedAt: String? = null,
    @Serializable(with = FlexibleBooleanSerializer::class)
    @SerialName("mcp_enabled") val mcpEnabled: Boolean? = null,
    val repo: ProjectRepoInfo? = null,
    val agents: ProjectAgentsInfo? = null,
    val conversations: ProjectConversationsInfo? = null,
    val tracker: ProjectTrackerInfo? = null,
    @SerialName("beads_remote") val beadsRemote: BeadsRemoteStatus? = null,
    @SerialName("last_activity_at") val lastActivityAt: String? = null,
    val version: String? = null,
    val etag: String? = null,
)

@Serializable
data class ProjectCatalog(
    val total: Int? = null,
    val projects: List<ProjectSummary>,
    val timestamp: String? = null,
)

@Serializable
data class ProjectDetailResponse(
    val project: ProjectSummary,
)

@Serializable
data class BeadsRemoteStatus(
    val status: String,
    @SerialName("provisioned_at") val provisionedAt: String? = null,
    @SerialName("remote_url") val remoteUrl: String? = null,
    val error: String? = null,
)

@Serializable
data class BeadsRemoteProvisionResponse(
    val status: String,
    @SerialName("remote_url") val remoteUrl: String? = null,
    @SerialName("dry_run") val dryRun: Boolean? = null,
    val error: String? = null,
)

@Serializable
data class ProjectSyncTriggerRequest(
    val projectId: ProjectId? = null,
)

@Serializable
data class ProjectSyncTriggerResponse(
    val status: String? = null,
    val message: String? = null,
    @SerialName("event_id") val eventId: String? = null,
)

@Serializable
data class PmAgentMetadata(
    @SerialName("agent_id") val agentId: AgentId,
    val name: String? = null,
    val status: String? = null,
    val repo: String? = null,
)

@Serializable
data class VibesyncHealthResponse(
    val service: String? = null,
    val version: String? = null,
    val status: String? = null,
    val uptime: JsonElement? = null,
    val sync: JsonElement? = null,
    val database: JsonElement? = null,
    val memory: JsonElement? = null,
    @SerialName("lastError") val lastError: JsonElement? = null,
    val config: JsonElement? = null,
    val rigs: JsonElement? = null,
    @SerialName("connectionPool") val connectionPool: JsonElement? = null,
)

@Serializable
data class VibesyncStatsResponse(
    val uptime: JsonElement? = null,
    val sync: JsonElement? = null,
    @SerialName("sseClients") val sseClients: Int? = null,
    @SerialName("syncHistory") val syncHistory: JsonElement? = null,
    val database: JsonElement? = null,
    val memory: JsonElement? = null,
    @SerialName("connectionPool") val connectionPool: JsonElement? = null,
)

@Serializable
data class AgentsMdRefreshRequest(
    val projectId: ProjectId? = null,
    val dryRun: Boolean = true,
)

@Serializable
data class AgentsMdRefreshSummary(
    val total: Int = 0,
    val updated: Int = 0,
    @SerialName("dry_run") val dryRunLegacy: Boolean? = null,
    val dryRun: Boolean? = null,
    val skipped: Int = 0,
    val errors: Int = 0,
    val results: JsonElement? = null,
)

@Serializable
data class ProjectRepoInfo(
    val provider: String? = null,
    @SerialName("remote_url") val remoteUrl: String? = null,
    @SerialName("filesystem_path") val filesystemPath: String? = null,
    val branch: String? = null,
    val dirty: Boolean? = null,
)

@Serializable
data class ProjectAgentsInfo(
    val total: Int? = null,
    val active: Int? = null,
    @SerialName("default_agent_id") val defaultAgentId: AgentId? = null,
)

@Serializable
data class ProjectConversationsInfo(
    @SerialName("total_known") val totalKnown: Int? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("last_conversation_id") val lastConversationId: ConversationId? = null,
)

@Serializable
data class ProjectTrackerInfo(
    val provider: String? = null,
    val status: String? = null,
    @SerialName("data_freshness") val dataFreshness: ProjectDataFreshness? = null,
    val capabilities: ProjectTrackerCapabilities? = null,
    val summary: ProjectTrackerSummary? = null,
)

@Serializable
data class ProjectDataFreshness(
    val status: String? = null,
    @SerialName("last_sync_at") val lastSyncAt: String? = null,
    val error: String? = null,
    val source: String? = null,
    @SerialName("is_stale") val isStale: Boolean = false,
    @SerialName("stale_threshold_ms") val staleThresholdMs: Long? = null,
)

@Serializable
data class ProjectTrackerCapabilities(
    @SerialName("work_items") val workItems: Boolean = false,
    val activity: Boolean = false,
    val agents: Boolean = false,
    val conversations: Boolean = false,
    val priority: Boolean = false,
    val status: Boolean = false,
    @SerialName("parent_child") val parentChild: Boolean = false,
    val labels: Boolean = false,
    val dependencies: Boolean = false,
)

@Serializable
data class ProjectTrackerSummary(
    @SerialName("total_known") val totalKnown: Int? = null,
    val ready: Int? = null,
    @SerialName("in_progress") val inProgress: Int? = null,
    val blocked: Int? = null,
    @SerialName("closed_recent") val closedRecent: Int? = null,
)

@OptIn(ExperimentalSerializationApi::class)
object FlexibleStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value)
        }
    }

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = jsonDecoder.decodeJsonElement()
        if (element !is JsonPrimitive || element.content == "null") return null
        return element.longOrNull?.toString() ?: element.content
    }
}

@OptIn(ExperimentalSerializationApi::class)
object FlexibleBooleanSerializer : KSerializer<Boolean?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleBoolean", PrimitiveKind.BOOLEAN)

    override fun serialize(encoder: Encoder, value: Boolean?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeBoolean(value)
        }
    }

    override fun deserialize(decoder: Decoder): Boolean? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        val element = jsonDecoder.decodeJsonElement()
        if (element !is JsonPrimitive || element.content == "null") return null
        return element.booleanOrNull
            ?: element.intOrNull?.let { it != 0 }
            ?: when (element.content.lowercase()) {
                "true", "yes", "on" -> true
                "false", "no", "off" -> false
                else -> null
            }
    }
}
