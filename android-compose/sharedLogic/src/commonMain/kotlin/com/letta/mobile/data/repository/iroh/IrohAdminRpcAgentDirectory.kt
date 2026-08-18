package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.chat.send.lettaWireJson
import com.letta.mobile.data.commands.AgentSlashCommand
import com.letta.mobile.data.commands.SlashCommandsResponse
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleListResponse
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.ToolUpdateParams
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.data.timeline.TimelineTransportHttpException
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * Minimal agent directory over iroh admin_rpc — enough for the desktop
 * shell's agent-name/model lookups without the HTTP admin repositories.
 */
class IrohAdminRpcAgentDirectory(
    private val transport: IChannelTransport,
) {
    private val json = lettaWireJson

    private suspend fun adminRpcResult(method: AdminRpcMethod, path: AdminRpcPath, body: AdminRpcBody? = null): JsonElement? {
        val response = transport.adminRpc(method.value, path.value, body?.value)
        if (!response.success) {
            throw TimelineTransportHttpException(502, response.error ?: "${method.value} failed over iroh admin_rpc")
        }
        return response.result
    }

    private suspend fun adminRpcResultOrNull(method: AdminRpcMethod, path: AdminRpcPath, body: AdminRpcBody? = null): JsonElement? {
        val response = transport.adminRpc(method.value, path.value, body?.value)
        if (!response.success) return null
        return response.result
    }

    private suspend fun scheduleGetResultOrNull(path: AdminRpcPath, body: AdminRpcBody): JsonElement? {
        val response = transport.adminRpc("schedule.get", path.value, body.value)
        if (!response.success) {
            val error = response.error
            if (error != null && SCHEDULE_GET_NOT_FOUND.matches(error)) return null
            throw TimelineTransportHttpException(502, error ?: "schedule.get failed over iroh admin_rpc")
        }
        return response.result
            ?: throw TimelineTransportHttpException(502, "schedule.get returned no result over iroh admin_rpc")
    }

    private suspend inline fun <reified T> adminRpcDecoded(
        method: AdminRpcMethod,
        path: AdminRpcPath,
        body: AdminRpcBody? = null,
    ): T {
        val result = adminRpcResult(method, path, body)
            ?: throw TimelineTransportHttpException(502, "${method.value} returned no result over iroh admin_rpc")
        return json.decodeFromJsonElement(serializer<T>(), result)
    }

    private suspend inline fun <reified T> adminRpcDecodedList(
        method: AdminRpcMethod,
        path: AdminRpcPath,
        body: AdminRpcBody? = null,
    ): List<T> {
        val result = adminRpcResult(method, path, body) ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(serializer<T>()), result)
    }

    suspend fun countAgents(): Int {
        val result = adminRpcResult(
            method = AdminRpcMethod("agent.count"),
            path = AdminRpcPath("/v1/agents/count"),
            body = AdminRpcBody("{}"),
        ) ?: throw TimelineTransportHttpException(
            502,
            "agent.count returned no result over iroh admin_rpc",
        )
        return (result as? JsonPrimitive)?.intOrNull
            ?: (result as? JsonObject)?.get("count")?.jsonPrimitive?.intOrNull
            ?: error("agent.count returned unparseable result: $result")
    }

    @kotlin.concurrent.Volatile
    var lastAgentListTruncated: Boolean = false
        private set

    suspend fun listAgents(limit: Int = AGENT_LIST_LIMIT): List<Agent> {
        val out = ArrayList<Agent>(minOf(limit, 64))
        val seenIds = HashSet<String>()
        var offset = 0
        var truncated = false
        while (out.size < limit) {
            val pageLimit = minOf(AGENT_LIST_PAGE_SIZE, limit - out.size)
            val body = buildJsonObject {
                put("limit", pageLimit.toString())
                put("offset", offset.toString())
            }.toString()
            val page: List<Agent> = adminRpcDecodedList(
                method = AdminRpcMethod("agent.list"),
                path = AdminRpcPath("/v1/agents?limit=$pageLimit&offset=$offset"),
                body = AdminRpcBody(body),
            )
            if (page.isEmpty()) break
            val fresh = page.filter { seenIds.add(it.id.value) }
            val room = limit - out.size
            if (fresh.size > room) truncated = true
            out += fresh.take(room)
            if (fresh.isEmpty()) {
                truncated = true
                break
            }
            if (page.size < pageLimit) break
            offset += page.size
        }
        if (out.size >= limit) truncated = true
        lastAgentListTruncated = truncated
        return out
    }

    suspend fun getAgent(agentId: AgentId): Agent? {
        val body = buildJsonObject { put("agent_id", agentId.value) }.toString()
        val result = adminRpcResultOrNull(
            method = AdminRpcMethod("agent.get"),
            path = AdminRpcPath("/v1/agents/${agentId.value}"),
            body = AdminRpcBody(body),
        ) ?: return null
        return json.decodeFromJsonElement(Agent.serializer(), result)
    }

    suspend fun getAgent(agentId: String): Agent? = getAgent(AgentId(agentId))

    suspend fun updateAgent(agentId: AgentId, params: AgentUpdateParams): Agent {
        val paramsJson = json.encodeToJsonElement(AgentUpdateParams.serializer(), params).jsonObject
        val body = buildJsonObject {
            put("agent_id", agentId.value)
            paramsJson.forEach { (key, value) -> put(key, value) }
        }.toString()
        return adminRpcDecoded(
            method = AdminRpcMethod("agent.update"),
            path = AdminRpcPath("/v1/agents/${agentId.value}"),
            body = AdminRpcBody(body),
        )
    }

    suspend fun updateAgent(agentId: String, params: AgentUpdateParams): Agent = updateAgent(AgentId(agentId), params)

    suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId? = null): ContextWindowOverview {
        val body = buildJsonObject {
            put("agent_id", agentId.value)
            conversationId?.let { put("conversation_id", it.value) }
        }.toString()
        val path = buildString {
            append("/v1/agents/")
            append(agentId.value)
            append("/context")
            if (conversationId != null) append("?conversation_id=").append(conversationId.value)
        }
        return adminRpcDecoded(
            method = AdminRpcMethod("agent.context"),
            path = AdminRpcPath(path),
            body = AdminRpcBody(body),
        )
    }

    suspend fun getContextWindow(agentId: String, conversationId: String? = null): ContextWindowOverview =
        getContextWindow(AgentId(agentId), conversationId?.let(::ConversationId))

    suspend fun listTools(limit: Int, offset: Int): List<Tool> {
        val body = buildJsonObject {
            put("limit", limit)
            put("offset", offset)
        }.toString()
        return adminRpcDecodedList(
            method = AdminRpcMethod("tool.list"),
            path = AdminRpcPath("/v1/tools?limit=$limit&offset=$offset"),
            body = AdminRpcBody(body),
        )
    }

    suspend fun createTool(params: ToolCreateParams): Tool {
        val body = json.encodeToString(ToolCreateParams.serializer(), params)
        return adminRpcDecoded(
            method = AdminRpcMethod("tool.create"),
            path = AdminRpcPath("/v1/tools"),
            body = AdminRpcBody(body),
        )
    }

    suspend fun updateTool(toolId: ToolId, params: ToolUpdateParams): Tool {
        val paramsJson = json.encodeToJsonElement(ToolUpdateParams.serializer(), params).jsonObject
        val body = buildJsonObject {
            put("tool_id", toolId.value)
            paramsJson.forEach { (key, value) -> put(key, value) }
        }.toString()
        return adminRpcDecoded(
            method = AdminRpcMethod("tool.update"),
            path = AdminRpcPath("/v1/tools/${toolId.value}"),
            body = AdminRpcBody(body),
        )
    }

    suspend fun updateTool(toolId: String, params: ToolUpdateParams): Tool = updateTool(ToolId(toolId), params)

    suspend fun deleteTool(toolId: ToolId) {
        val body = buildJsonObject { put("tool_id", toolId.value) }.toString()
        adminRpcResult(
            method = AdminRpcMethod("tool.delete"),
            path = AdminRpcPath("/v1/tools/${toolId.value}"),
            body = AdminRpcBody(body),
        )
    }

    suspend fun deleteTool(toolId: String) = deleteTool(ToolId(toolId))

    suspend fun setToolAttached(agentId: AgentId, toolId: ToolId, attached: Boolean) {
        val body = buildJsonObject {
            put("agent_id", agentId.value)
            put("tool_id", toolId.value)
        }.toString()
        val method = if (attached) AdminRpcMethod("tool.attach") else AdminRpcMethod("tool.detach")
        val action = if (attached) "attach" else "detach"
        adminRpcResult(
            method = method,
            path = AdminRpcPath("/v1/agents/${agentId.value}/tools/$action/${toolId.value}"),
            body = AdminRpcBody(body),
        )
    }

    suspend fun setToolAttached(agentId: String, toolId: String, attached: Boolean) =
        setToolAttached(AgentId(agentId), ToolId(toolId), attached)

    suspend fun listSkills(agentId: AgentId? = null): List<Skill> {
        val body = buildJsonObject {
            agentId?.let { put("agent_id", it.value) }
        }.toString()
        val method = if (agentId == null) AdminRpcMethod("skill.list") else AdminRpcMethod("skill.list_agent")
        val path = agentId?.let { "/v1/agents/${it.value}/skills" } ?: "/v1/skills"
        val result = adminRpcResult(
            method = method,
            path = AdminRpcPath(path),
            body = AdminRpcBody(body),
        ) ?: return emptyList()
        val skillsElement = (result as? JsonObject)?.get("skills") ?: result
        return json.decodeFromJsonElement(ListSerializer(Skill.serializer()), skillsElement)
    }

    suspend fun listSkills(agentId: String?): List<Skill> = listSkills(agentId?.let(::AgentId))

    suspend fun listAgentSlashCommands(agentId: AgentId): List<AgentSlashCommand> {
        val body = buildJsonObject { put("agent_id", agentId.value) }.toString()
        val result = adminRpcResult(
            method = AdminRpcMethod("slash_command.list_agent"),
            path = AdminRpcPath("/v1/agents/${agentId.value}/slash-commands"),
            body = AdminRpcBody(body),
        ) ?: return emptyList()
        return json.decodeFromJsonElement(SlashCommandsResponse.serializer(), result).commands
    }

    suspend fun listAgentSlashCommands(agentId: String): List<AgentSlashCommand> =
        listAgentSlashCommands(AgentId(agentId))

    suspend fun installSkill(agentId: AgentId, skillName: SkillName) {
        val body = buildJsonObject {
            put("agent_id", agentId.value)
            put("name", skillName.value)
            put("skill_path", skillName.value)
        }.toString()
        adminRpcResult(
            method = AdminRpcMethod("skill.install"),
            path = AdminRpcPath("/v1/agents/${agentId.value}/skills"),
            body = AdminRpcBody(body),
        )
    }

    suspend fun installSkill(agentId: String, skillName: String) =
        installSkill(AgentId(agentId), SkillName(skillName))

    suspend fun uninstallSkill(agentId: AgentId, skillName: SkillName) {
        val body = buildJsonObject {
            put("agent_id", agentId.value)
            put("name", skillName.value)
        }.toString()
        adminRpcResult(
            method = AdminRpcMethod("skill.uninstall"),
            path = AdminRpcPath("/v1/agents/${agentId.value}/skills/${skillName.value}"),
            body = AdminRpcBody(body),
        )
    }

    suspend fun uninstallSkill(agentId: String, skillName: String) =
        uninstallSkill(AgentId(agentId), SkillName(skillName))

    suspend fun getBlock(blockId: BlockId): Block? {
        val body = buildJsonObject { put("block_id", blockId.value) }.toString()
        val result = adminRpcResultOrNull(
            method = AdminRpcMethod("block.get"),
            path = AdminRpcPath("/v1/blocks/${blockId.value}"),
            body = AdminRpcBody(body),
        ) ?: return null
        return json.decodeFromJsonElement(Block.serializer(), result)
    }

    suspend fun getBlock(blockId: String): Block? = getBlock(BlockId(blockId))

    suspend fun listAgentBlocks(agentId: AgentId): List<Block> {
        require(agentId.value.isNotBlank()) { "agent_id must not be blank" }
        val merged = mutableListOf<Block>()
        val seenIds = HashSet<String>()
        var offset = 0
        repeat(AGENT_BLOCK_LIST_MAX_PAGES) {
            val body = buildJsonObject {
                put("agent_id", agentId.value)
                put("limit", AGENT_BLOCK_LIST_PAGE_SIZE.toString())
                put("offset", offset.toString())
            }.toString()
            val result = adminRpcResult(
                method = AdminRpcMethod("block.list_agent"),
                path = AdminRpcPath("/v1/agents/${agentId.value}/core-memory/blocks?limit=$AGENT_BLOCK_LIST_PAGE_SIZE&offset=$offset"),
                body = AdminRpcBody(body),
            ) ?: throw TimelineTransportHttpException(
                502,
                "block.list_agent returned no result over iroh admin_rpc",
            )
            val page = decodeAgentBlockPage(result)
            val fresh = page.blocks.filter { block -> seenIds.add(block.id.value) }
            merged += fresh
            if (page.blocks.isEmpty() || fresh.isEmpty()) return merged
            if (!page.hasMore) return merged
            offset += page.blocks.size
        }
        throw TimelineTransportHttpException(
            502,
            "block.list_agent exceeded $AGENT_BLOCK_LIST_MAX_PAGES pages while has_more remained true",
        )
    }

    suspend fun listAgentBlocks(agentId: String): List<Block> = listAgentBlocks(AgentId(agentId))

    private fun decodeAgentBlockPage(result: JsonElement): AgentBlockPage = when (result) {
        is JsonArray -> AgentBlockPage(
            blocks = json.decodeFromJsonElement(ListSerializer(Block.serializer()), result),
            hasMore = false,
        )
        is JsonObject -> AgentBlockPage(
            blocks = result["blocks"]
                ?.let { json.decodeFromJsonElement(ListSerializer(Block.serializer()), it) }
                ?: throw TimelineTransportHttpException(502, "block.list_agent returned an object without blocks"),
            hasMore = (result["has_more"] as? JsonPrimitive)
                ?.takeUnless { it.isString }
                ?.booleanOrNull
                ?: throw TimelineTransportHttpException(502, "block.list_agent returned invalid has_more"),
        )
        else -> throw TimelineTransportHttpException(
            502,
            "block.list_agent returned an unsupported result: $result",
        )
    }

    private data class AgentBlockPage(
        val blocks: List<Block>,
        val hasMore: Boolean,
    )

    suspend fun createBlock(params: BlockCreateParams): Block {
        val body = json.encodeToString(BlockCreateParams.serializer(), params)
        return adminRpcDecoded(
            method = AdminRpcMethod("block.create"),
            path = AdminRpcPath("/v1/blocks"),
            body = AdminRpcBody(body),
        )
    }

    suspend fun updateBlock(blockId: BlockId, params: BlockUpdateParams): Block {
        val body = buildJsonObject {
            put("block_id", blockId.value)
            params.value?.let { put("value", it) }
            params.limit?.let { put("limit", it) }
            params.description?.let { put("description", it) }
        }.toString()
        return adminRpcDecoded(
            method = AdminRpcMethod("block.update"),
            path = AdminRpcPath("/v1/blocks/${blockId.value}"),
            body = AdminRpcBody(body),
        )
    }

    suspend fun updateBlock(blockId: String, params: BlockUpdateParams): Block = updateBlock(BlockId(blockId), params)

    suspend fun deleteBlock(blockId: BlockId) {
        val body = buildJsonObject { put("block_id", blockId.value) }.toString()
        adminRpcResult(
            method = AdminRpcMethod("block.delete"),
            path = AdminRpcPath("/v1/blocks/${blockId.value}"),
            body = AdminRpcBody(body),
        )
    }

    suspend fun deleteBlock(blockId: String) = deleteBlock(BlockId(blockId))

    suspend fun attachBlock(agentId: AgentId, blockId: BlockId) {
        val body = buildJsonObject {
            put("agent_id", agentId.value)
            put("block_id", blockId.value)
        }.toString()
        adminRpcResult(
            method = AdminRpcMethod("block.attach"),
            path = AdminRpcPath("/v1/agents/${agentId.value}/core-memory/blocks/attach/${blockId.value}"),
            body = AdminRpcBody(body),
        )
    }

    suspend fun attachBlock(agentId: String, blockId: String) = attachBlock(AgentId(agentId), BlockId(blockId))

    suspend fun listSchedules(agentId: AgentId? = null): List<ScheduledMessage> {
        val body = buildJsonObject {
            agentId?.let { put("agent_id", it.value) }
        }.toString()
        val path = agentId?.let { "/v1/agents/${it.value}/schedule" } ?: "/v1/schedules"
        val result = adminRpcResult(
            method = AdminRpcMethod("schedule.list"),
            path = AdminRpcPath(path),
            body = AdminRpcBody(body),
        ) ?: throw TimelineTransportHttpException(502, "schedule.list returned no result over iroh admin_rpc")
        if (result is JsonObject && "scheduled_messages" !in result) {
            throw TimelineTransportHttpException(502, "schedule.list returned a malformed result over iroh admin_rpc")
        }
        val schedules = json.decodeFromJsonElement(ScheduleListResponse.serializer(), result).scheduledMessages
        if (agentId == null) return schedules

        val scopedSchedules = schedules.filter { it.agentId == agentId.value }
        val excludedCount = schedules.size - scopedSchedules.size
        if (excludedCount > 0) {
            Telemetry.event(
                "IrohAdminRpcAgentDirectory",
                "scheduleList.scopeMismatch",
                "requestedAgentId" to agentId.value,
                "excludedCount" to excludedCount,
                level = Telemetry.Level.WARN,
            )
        }
        return scopedSchedules
    }

    suspend fun listSchedules(agentId: String?): List<ScheduledMessage> = listSchedules(agentId?.let(::AgentId))

    suspend fun getSchedule(scheduleId: ScheduleId, agentId: AgentId? = null): ScheduledMessage? {
        val body = buildJsonObject {
            put("schedule_id", scheduleId.value)
            agentId?.let { put("agent_id", it.value) }
        }.toString()
        val path = agentId?.let { "/v1/agents/${it.value}/schedule/${scheduleId.value}" }
            ?: "/v1/schedules/${scheduleId.value}"
        val result = scheduleGetResultOrNull(AdminRpcPath(path), AdminRpcBody(body)) ?: return null
        return json.decodeFromJsonElement(ScheduledMessage.serializer(), result)
    }

    suspend fun getSchedule(scheduleId: String, agentId: String? = null): ScheduledMessage? =
        getSchedule(ScheduleId(scheduleId), agentId?.let(::AgentId))

    suspend fun createSchedule(agentId: AgentId, params: ScheduleCreateParams): ScheduledMessage {
        val paramsJson = json.encodeToJsonElement(ScheduleCreateParams.serializer(), params).jsonObject
        val body = buildJsonObject {
            put("agent_id", agentId.value)
            paramsJson.forEach { (key, value) -> put(key, value) }
        }.toString()
        return adminRpcDecoded(
            method = AdminRpcMethod("schedule.create"),
            path = AdminRpcPath("/v1/agents/${agentId.value}/schedule"),
            body = AdminRpcBody(body),
        )
    }

    suspend fun createSchedule(agentId: String, params: ScheduleCreateParams): ScheduledMessage =
        createSchedule(AgentId(agentId), params)

    suspend fun deleteSchedule(scheduleId: ScheduleId, agentId: AgentId? = null) {
        val body = buildJsonObject {
            put("schedule_id", scheduleId.value)
            agentId?.let { put("agent_id", it.value) }
        }.toString()
        val path = agentId?.let { "/v1/agents/${it.value}/schedule/${scheduleId.value}" }
            ?: "/v1/schedules/${scheduleId.value}"
        adminRpcResult(
            method = AdminRpcMethod("schedule.delete"),
            path = AdminRpcPath(path),
            body = AdminRpcBody(body),
        )
    }

    suspend fun deleteSchedule(scheduleId: String, agentId: String? = null) =
        deleteSchedule(ScheduleId(scheduleId), agentId?.let(::AgentId))

    companion object {
        const val AGENT_LIST_LIMIT = 2500
        const val AGENT_LIST_PAGE_SIZE = 25
        const val AGENT_BLOCK_LIST_PAGE_SIZE = 50
        const val AGENT_BLOCK_LIST_MAX_PAGES = 100

        private val SCHEDULE_GET_NOT_FOUND = Regex(
            "^(?:scheduled message \\S+ not found|HTTP 404(?::.*)?|not_found(?::.*)?)$",
            RegexOption.IGNORE_CASE,
        )
    }
}
