package com.letta.mobile.data.controller.node.iroh

import kotlin.test.Test
import kotlin.test.assertEquals

class AdminRpcContractTest {
    @Test
    fun contractTestRegisteredMethods() {
        // The contract test builds the full registry router, gets its registered method set,
        // and asserts it EXACTLY equals a hardcoded CANONICAL expected set.
        // Adding a new admin method must update both handler + this expected set.
        val router = AdminRpcRegistry.buildRouter("http://localhost:8080", null)
        assertEquals(
            (expectedRegisteredMethods() + "conversation.update").sorted(),
            router.registeredMethods.sorted(),
            "Registered Admin RPC methods do not exactly match the expected contract. Did you add/remove a method? Update handlers and expected set.",
        )
    }

    private companion object {
        fun expectedRegisteredMethods(): Set<String> =
            agentMethods() +
                blockMethods() +
                conversationMethods() +
                projectMethods() +
                toolMethods() +
                miscMethods() +
                subagentMethods()

        fun agentMethods() = setOf(
            "agent.context", "agent.create", "agent.delete", "agent.get", "agent.list", "agent.update",
        )

        fun blockMethods() = setOf(
            "block.attach", "block.create", "block.delete", "block.detach",
            "block.get", "block.list", "block.update", "block.update_agent",
        )

        fun conversationMethods() = setOf(
            "conversation.archive", "conversation.create", "conversation.delete",
            "conversation.get", "conversation.list", "conversation.restore",
        )

        fun projectMethods() = setOf(
            "project.archive", "project.beadsRemoteStatus", "project.create", "project.delete",
            "project.get", "project.list", "project.provisionBeadsRemote",
            "project.triggerSync", "project.update",
        )

        fun toolMethods() = setOf(
            "tool.attach", "tool.create", "tool.delete", "tool.detach",
            "tool.get", "tool.list", "tool.update", "tool_return.get",
        )

        fun subagentMethods() = setOf("subagent.list", "subagent.todos")

        fun miscMethods() = setOf(
            "approval.submit",
            "archive.list",
            "folder.list",
            "goal.command",
            "goal.get",
            "slash_command.list",
            "slash_command.list_agent",
            "skill.install",
            "skill.list",
            "skill.list_agent",
            "skill.uninstall",
            "group.list",
            "health.check",
            "identity.get",
            "identity.list",
            "job.get",
            "job.list",
            "mcp.list",
            "message.get",
            "message.list",
            "model.list",
            "model.list.embedding",
            "passage.create",
            "passage.delete",
            "passage.list",
            "provider.list",
            "run.get",
            "run.list",
            "schedule.create",
            "schedule.delete",
            "schedule.get",
            "schedule.list",
            "step.list",
        )
    }
}
