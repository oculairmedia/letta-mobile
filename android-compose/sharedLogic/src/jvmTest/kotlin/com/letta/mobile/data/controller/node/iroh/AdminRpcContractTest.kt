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
        val registeredMethods = router.registeredMethods

        val expectedMethods = setOf(
            "agent.context",
            "agent.create",
            "agent.delete",
            "agent.get",
            "agent.list",
            "agent.update",
            "approval.submit",
            "archive.list",
            "block.attach",
            "block.create",
            "block.delete",
            "block.detach",
            "block.get",
            "block.list",
            "block.update",
            "block.update_agent",
            "conversation.archive",
            "conversation.create",
            "conversation.delete",
            "conversation.get",
            "conversation.list",
            "conversation.restore",
            "folder.list",
            "goal.command",
            "goal.get",
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
            "project.create",
            "project.delete",
            "project.get",
            "project.list",
            "project.update",
            "provider.list",
            "run.get",
            "run.list",
            "schedule.create",
            "schedule.delete",
            "schedule.get",
            "schedule.list",
            "step.list",
            "tool.attach",
            "tool.create",
            "tool.delete",
            "tool.detach",
            "tool.get",
            "tool.list",
            "tool.update",
            "tool_return.get",
        )

        assertEquals(
            expectedMethods.sorted(),
            registeredMethods.sorted(),
            "Registered Admin RPC methods do not exactly match the expected contract. Did you add/remove a method? Update handlers and expected set."
        )
    }
}
