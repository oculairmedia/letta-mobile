package com.letta.mobile.data.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class AgentEnvironmentVariable(
    val id: String? = null,
    val key: String,
    val value: String? = null,
    val description: String? = null,
    @SerialName("value_enc") val valueEnc: String? = null,
)

@Serializable
data class ContextWindowOverview(
    @SerialName("context_window_size_max") val contextWindowSizeMax: Int = 0,
    @SerialName("context_window_size_current") val contextWindowSizeCurrent: Int = 0,
    @SerialName("num_messages") val numMessages: Int = 0,
    @SerialName("num_archival_memory") val numArchivalMemory: Int = 0,
    @SerialName("num_recall_memory") val numRecallMemory: Int = 0,
    @SerialName("num_tokens_external_memory_summary") val numTokensExternalMemorySummary: Int = 0,
    @SerialName("num_tokens_system") val numTokensSystem: Int = 0,
    @SerialName("num_tokens_core_memory") val numTokensCoreMemory: Int = 0,
    @SerialName("num_tokens_memory_filesystem") val numTokensMemoryFilesystem: Int = 0,
    @SerialName("num_tokens_tool_usage_rules") val numTokensToolUsageRules: Int = 0,
    @SerialName("num_tokens_directories") val numTokensDirectories: Int = 0,
    @SerialName("num_tokens_summary_memory") val numTokensSummaryMemory: Int = 0,
    @SerialName("num_tokens_functions_definitions") val numTokensFunctionsDefinitions: Int = 0,
    @SerialName("num_tokens_messages") val numTokensMessages: Int = 0,
)

@Serializable
data class CompactionSettings(
    val model: String? = null,
    @SerialName("model_settings") val modelSettings: JsonElement? = null,
    val prompt: String? = null,
    @SerialName("prompt_acknowledgement") val promptAcknowledgement: Boolean? = null,
    @SerialName("clip_chars") val clipChars: Int? = null,
    val mode: String? = null,
    @SerialName("sliding_window_percentage") val slidingWindowPercentage: Double? = null,
)

/**
 * Lightweight projection of an agent used by picker UIs (e.g. the Schedules
 * dropdown). Mirrors the admin-shim's opt-in `GET /v1/agents?slim=true`
 * response, which returns only `{id, name, description}` and skips the
 * per-agent [Agent]/`AgentState` synthesis (system prompt, message_ids,
 * llm_config, tool_rules, blocks, …) that bloats the default
 * `/v1/agents` response to ~621KB for 50 agents.
 *
 * This is intentionally NOT the full [Agent] model: the slim response omits
 * required fields ([Agent.name] is required but all the rest default, and
 * strict screens still need the full object). Keep this model lenient and
 * minimal — adapt at the boundary rather than widening it to satisfy a
 * consumer that wants the heavy type.
 */
@Serializable
data class AgentSummary(
    val id: AgentId,
    val name: String = "",
    val description: String? = null,
)

@Serializable
data class Agent(
    val id: AgentId,
    val name: String,
    val description: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
    val model: String? = null,
    val embedding: String? = null,
    @SerialName("model_settings") val modelSettings: ModelSettings? = null,
    @SerialName("llm_config") val llmConfig: LlmConfig? = null,
    @SerialName("embedding_config") val embeddingConfig: EmbeddingConfig? = null,
    @SerialName("context_window_limit") val contextWindowLimit: Int? = null,
    @SerialName("response_format") val responseFormat: JsonElement? = null,
    @Serializable(with = ImmutableListSerializer::class) val blocks: ImmutableList<Block>? = null,
    /**
     * Canonical Letta `AgentState` nests core-memory blocks HERE; only the admin
     * shim also duplicates them to the top-level [blocks]. Decoding both shapes
     * means either payload resolves — read [coreBlocks] rather than [blocks]
     * anywhere you want "this agent's core memory".
     */
    val memory: AgentMemory? = null,
    @Serializable(with = ImmutableListSerializer::class) val tools: ImmutableList<Tool> = persistentListOf(),
    @Serializable(with = ImmutableListSerializer::class) val sources: ImmutableList<JsonObject> = persistentListOf(),
    @Serializable(with = ImmutableListSerializer::class) val tags: ImmutableList<String> = persistentListOf(),
    @Serializable(with = ImmutableListSerializer::class) @SerialName("tool_rules") val toolRules: ImmutableList<JsonObject> = persistentListOf(),
    @Serializable(with = ImmutableListSerializer::class) @SerialName("tool_exec_environment_variables") val toolExecEnvironmentVariables: ImmutableList<AgentEnvironmentVariable> = persistentListOf(),
    @Serializable(with = ImmutableListSerializer::class) val secrets: ImmutableList<AgentEnvironmentVariable> = persistentListOf(),
    @SerialName("project_id") val projectId: ProjectId? = null,
    @SerialName("template_id") val templateId: String? = null,
    @SerialName("base_template_id") val baseTemplateId: String? = null,
    @SerialName("compaction_settings") val compactionSettings: CompactionSettings? = null,
    @SerialName("deployment_id") val deploymentId: String? = null,
    @SerialName("entity_id") val entityId: String? = null,
    @Serializable(with = ImmutableListSerializer::class) @SerialName("identity_ids") val identityIds: ImmutableList<String> = persistentListOf(),
    @Serializable(with = ImmutableListSerializer::class) val identities: ImmutableList<Identity> = persistentListOf(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val system: String? = null,
    @SerialName("enable_sleeptime") val enableSleeptime: Boolean? = null,
    @SerialName("agent_type") val agentType: String? = null,
    @Serializable(with = ImmutableListSerializer::class) @SerialName("message_ids") val messageIds: ImmutableList<String> = persistentListOf(),
    @SerialName("message_buffer_autoclear") val messageBufferAutoclear: Boolean? = null,
    @SerialName("last_run_completion") val lastRunCompletion: String? = null,
    @SerialName("last_run_duration_ms") val lastRunDurationMs: Int? = null,
    val timezone: String? = null,
    @SerialName("max_files_open") val maxFilesOpen: Int? = null,
    @SerialName("per_file_view_window_char_limit") val perFileViewWindowCharLimit: Int? = null,
    val hidden: Boolean? = null,
    @SerialName("managed_group") val managedGroup: JsonObject? = null,
    @SerialName("multi_agent_group") val multiAgentGroup: JsonObject? = null,
) {
    /**
     * This agent's core-memory blocks, from whichever shape the backend sent.
     * Prefers the top-level [blocks]; falls back to the canonical nested
     * [AgentMemory.blocks]. Every "show this agent's memory" surface must use
     * this — reading [blocks] directly renders 0 blocks against a backend that
     * only emits the canonical shape.
     */
    val coreBlocks: ImmutableList<Block> get() = blocks ?: memory?.blocks ?: persistentListOf()
}

/** The `memory` sub-object of a canonical Letta `AgentState`. */
@Serializable
data class AgentMemory(
    @Serializable(with = ImmutableListSerializer::class) val blocks: ImmutableList<Block> = persistentListOf(),
    @Serializable(with = ImmutableListSerializer::class) @SerialName("file_blocks") val fileBlocks: ImmutableList<Block> = persistentListOf(),
)

@Serializable
data class AgentCreateParams(
    val name: String? = null,
    val model: String? = null,
    val embedding: String? = null,
    @SerialName("model_settings") val modelSettings: ModelSettings? = null,
    @SerialName("llm_config") val llmConfig: LlmConfig? = null,
    @SerialName("embedding_config") val embeddingConfig: EmbeddingConfig? = null,
    @Serializable(with = ImmutableListSerializer::class) @SerialName("memory_blocks") val memoryBlocks: ImmutableList<BlockCreateParams>? = null,
    @Serializable(with = ImmutableListSerializer::class) val tools: ImmutableList<String>? = null,
    @Serializable(with = ImmutableListSerializer::class) @SerialName("tool_ids") val toolIds: ImmutableList<ToolId>? = null,
    @Serializable(with = ImmutableListSerializer::class) @SerialName("source_ids") val sourceIds: ImmutableList<String>? = null,
    @Serializable(with = ImmutableListSerializer::class) @SerialName("block_ids") val blockIds: ImmutableList<BlockId>? = null,
    @Serializable(with = ImmutableListSerializer::class) @SerialName("tool_rules") val toolRules: ImmutableList<JsonObject>? = null,
    @Serializable(with = ImmutableListSerializer::class) val tags: ImmutableList<String>? = null,
    val system: String? = null,
    val description: String? = null,
    val metadata: Map<String, JsonElement>? = null,
    @SerialName("enable_sleeptime") val enableSleeptime: Boolean? = null,
    @SerialName("agent_type") val agentType: String? = null,
    @Serializable(with = ImmutableListSerializer::class) @SerialName("initial_message_sequence") val initialMessageSequence: ImmutableList<MessageCreate>? = null,
    @SerialName("include_base_tools") val includeBaseTools: Boolean? = null,
    @SerialName("include_multi_agent_tools") val includeMultiAgentTools: Boolean? = null,
    @SerialName("include_base_tool_rules") val includeBaseToolRules: Boolean? = null,
    @SerialName("include_default_source") val includeDefaultSource: Boolean? = null,
    @SerialName("embedding_chunk_size") val embeddingChunkSize: Int? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("max_reasoning_tokens") val maxReasoningTokens: Int? = null,
    @SerialName("enable_reasoner") val enableReasoner: Boolean? = null,
    val reasoning: JsonElement? = null,
    @SerialName("response_format") val responseFormat: JsonElement? = null,
    @SerialName("tool_exec_environment_variables") val toolExecEnvironmentVariables: Map<String, String>? = null,
    val secrets: Map<String, String>? = null,
    @SerialName("memory_variables") val memoryVariables: Map<String, String>? = null,
    @SerialName("project_id") val projectId: ProjectId? = null,
    @SerialName("template_id") val templateId: String? = null,
    @SerialName("base_template_id") val baseTemplateId: String? = null,
    @Serializable(with = ImmutableListSerializer::class) @SerialName("identity_ids") val identityIds: ImmutableList<String>? = null,
    @SerialName("message_buffer_autoclear") val messageBufferAutoclear: Boolean? = null,
    val timezone: String? = null,
    @SerialName("max_files_open") val maxFilesOpen: Int? = null,
    @SerialName("per_file_view_window_char_limit") val perFileViewWindowCharLimit: Int? = null,
    val hidden: Boolean? = null,
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
    @SerialName("context_window_limit") val contextWindowLimit: Int? = null,
    @SerialName("compaction_settings") val compactionSettings: CompactionSettings? = null,
)

@Serializable
data class AgentUpdateParams(
    val name: String? = null,
    val description: String? = null,
    val model: String? = null,
    val embedding: String? = null,
    @SerialName("model_settings") val modelSettings: ModelSettings? = null,
    @SerialName("llm_config") val llmConfig: LlmConfig? = null,
    @SerialName("embedding_config") val embeddingConfig: EmbeddingConfig? = null,
    val system: String? = null,
    @Serializable(with = ImmutableListSerializer::class) @SerialName("tool_ids") val toolIds: ImmutableList<ToolId>? = null,
    @Serializable(with = ImmutableListSerializer::class) @SerialName("source_ids") val sourceIds: ImmutableList<String>? = null,
    @Serializable(with = ImmutableListSerializer::class) @SerialName("block_ids") val blockIds: ImmutableList<BlockId>? = null,
    @Serializable(with = ImmutableListSerializer::class) val tags: ImmutableList<String>? = null,
    @Serializable(with = ImmutableListSerializer::class) @SerialName("tool_rules") val toolRules: ImmutableList<JsonObject>? = null,
    @Serializable(with = ImmutableListSerializer::class) @SerialName("message_ids") val messageIds: ImmutableList<String>? = null,
    val metadata: Map<String, JsonElement>? = null,
    @SerialName("tool_exec_environment_variables") val toolExecEnvironmentVariables: Map<String, String>? = null,
    val secrets: Map<String, String>? = null,
    @SerialName("project_id") val projectId: ProjectId? = null,
    @SerialName("template_id") val templateId: String? = null,
    @SerialName("base_template_id") val baseTemplateId: String? = null,
    @Serializable(with = ImmutableListSerializer::class) @SerialName("identity_ids") val identityIds: ImmutableList<String>? = null,
    @SerialName("message_buffer_autoclear") val messageBufferAutoclear: Boolean? = null,
    @SerialName("context_window_limit") val contextWindowLimit: Int? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val reasoning: JsonElement? = null,
    @SerialName("enable_sleeptime") val enableSleeptime: Boolean? = null,
    @SerialName("response_format") val responseFormat: JsonElement? = null,
    @SerialName("last_run_completion") val lastRunCompletion: String? = null,
    @SerialName("last_run_duration_ms") val lastRunDurationMs: Int? = null,
    val timezone: String? = null,
    @SerialName("max_files_open") val maxFilesOpen: Int? = null,
    @SerialName("per_file_view_window_char_limit") val perFileViewWindowCharLimit: Int? = null,
    val hidden: Boolean? = null,
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
    @SerialName("compaction_settings") val compactionSettings: CompactionSettings? = null,
)

/**
 * Checkpoint of critical agent configuration fields that should be preserved
 * across operations like conversation compaction/recompilation.
 *
 * Captures the essential model and behavior configuration without including
 * runtime state like message_ids or timestamps.
 */
data class AgentConfigCheckpoint(
    val model: String?,
    val embedding: String?,
    val modelSettings: ModelSettings?,
    val embeddingConfig: EmbeddingConfig?,
    val contextWindowLimit: Int?,
    val system: String?,
    val responseFormat: JsonElement?,
    val compactionSettings: CompactionSettings?,
) {
    companion object {
        /**
         * Create a checkpoint from the current agent state.
         */
        fun from(agent: Agent) = AgentConfigCheckpoint(
            model = agent.model,
            embedding = agent.embedding,
            modelSettings = agent.modelSettings,
            embeddingConfig = agent.embeddingConfig,
            contextWindowLimit = agent.contextWindowLimit,
            system = agent.system,
            responseFormat = agent.responseFormat,
            compactionSettings = agent.compactionSettings,
        )
    }

    /**
     * Check if the current agent configuration matches this checkpoint.
     * Returns true if all checkpointed fields match.
     */
    fun matches(agent: Agent): Boolean {
        return model == agent.model &&
            embedding == agent.embedding &&
            modelSettings == agent.modelSettings &&
            embeddingConfig == agent.embeddingConfig &&
            contextWindowLimit == agent.contextWindowLimit &&
            system == agent.system &&
            responseFormat == agent.responseFormat &&
            compactionSettings == agent.compactionSettings
    }

    /**
     * Convert this checkpoint to AgentUpdateParams for restoration.
     */
    fun toUpdateParams() = AgentUpdateParams(
        model = model,
        embedding = embedding,
        modelSettings = modelSettings,
        embeddingConfig = embeddingConfig,
        contextWindowLimit = contextWindowLimit,
        system = system,
        responseFormat = responseFormat,
        compactionSettings = compactionSettings,
    )
}
