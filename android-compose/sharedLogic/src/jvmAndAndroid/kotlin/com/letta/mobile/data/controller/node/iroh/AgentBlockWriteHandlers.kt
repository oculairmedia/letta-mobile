package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Agent-scoped memory-block writes (lgns8.9 update, bfooy.5 create/delete).
 *
 * A core-memory block IS the MemFS file `memory/system/<label>.md`, so every
 * write here is addressed by `agent_id` + `label` and maps onto a native App
 * Server command that commits the change (moving MemFS HEAD, which is what the
 * compiled system prompt reads):
 *  - `block.update_agent` -> `write_memory_file`
 *  - `block.create_agent` -> `write_memory_file`, refusing an existing label so a
 *    create never silently clobbers a block
 *  - `block.delete_agent` -> `delete_memory_file`
 *
 * The App Server stays the backend root's single writer; the controller only
 * reads the store (for the create-collision check).
 */
internal object AgentBlockWriteHandlers {
    const val UPDATE = "block.update_agent"
    const val CREATE = "block.create_agent"
    const val DELETE = "block.delete_agent"

    val METHODS: Set<String> = setOf(UPDATE, CREATE, DELETE)

    fun register(router: AdminRpcRouter, store: LocalBackendAdminStore?, nativeClient: AppServerClient?) {
        router.register(UPDATE) { params ->
            val target = BlockTarget.from(params)
            val value = requireValue(params, UPDATE)
            writeBlock(requireClient(nativeClient, UPDATE), target, value, UPDATE)
        }
        router.register(CREATE) { params ->
            val target = BlockTarget.from(params)
            val value = param(params, AdminParamKey("value")).orEmpty()
            if (store != null && store.hasBlock(target)) {
                adminError("block ${target.label} already exists for agent ${target.agentId}")
            }
            writeBlock(requireClient(nativeClient, CREATE), target, value, CREATE)
        }
        router.register(DELETE) { params ->
            val target = BlockTarget.from(params)
            deleteBlock(requireClient(nativeClient, DELETE), target)
        }
    }

    private suspend fun writeBlock(
        client: AppServerClient,
        target: BlockTarget,
        value: String,
        method: String,
    ): JsonElement {
        val response = client.writeMemoryFile(
            AppServerCommand.WriteMemoryFile(
                requestId = NativeAdmin.requestId(),
                agentId = target.agentId,
                path = target.memoryPath,
                content = value,
                commitMessage = "$method: ${target.label}",
            ),
        )
        if (!response.success) adminError(response.error ?: "write_memory_file failed")
        // Echo the post-write block using the SAME projection block.get serves,
        // so the client decodes one shape regardless of which route it used.
        return LocalBackendBlockReader.projectBlock(target.agentId, target.label, value)
    }

    private suspend fun deleteBlock(client: AppServerClient, target: BlockTarget): JsonElement {
        val response = client.deleteMemoryFile(
            AppServerCommand.DeleteMemoryFile(
                requestId = NativeAdmin.requestId(),
                agentId = target.agentId,
                path = target.memoryPath,
                commitMessage = "$DELETE: ${target.label}",
            ),
        )
        if (!response.success) adminError(response.error ?: "delete_memory_file failed")
        return buildJsonObject {
            put("id", LocalBackendBlockReader.blockIdFor(target.agentId, target.label))
            put("agent_id", target.agentId)
            put("label", target.label)
            put("deleted", true)
            put("committed", response.committed)
        }
    }

    private fun requireValue(params: JsonObject?, method: String): String =
        param(params, AdminParamKey("value"))
            ?: adminError("value required: $method writes the memory file contents")

    private fun requireClient(client: AppServerClient?, method: String): AppServerClient =
        client ?: adminError("capability_unavailable: $method requires the native App Server client")

    private fun LocalBackendAdminStore.hasBlock(target: BlockTarget): Boolean =
        blocksForAgentProjected(target.agentId).any { block ->
            runCatching { block.jsonObject["label"]?.jsonPrimitive?.content }.getOrNull() == target.label
        }

    /** A validated agent + label pair; both are single MemFS path segments. */
    private class BlockTarget(val agentId: String, val label: String) {
        /** MemFS path for a core-memory block label, mirroring `memory/system/<label>.md`. */
        val memoryPath: String get() = "system/$label.md"

        companion object {
            fun from(params: JsonObject?): BlockTarget = BlockTarget(
                agentId = requireSafeMemfsSegment(params.requireParam(AdminParamKey("agent_id")), "agent_id"),
                label = requireSafeMemfsSegment(params.requireParam(AdminParamKey("label")), "label"),
            )
        }
    }
}
