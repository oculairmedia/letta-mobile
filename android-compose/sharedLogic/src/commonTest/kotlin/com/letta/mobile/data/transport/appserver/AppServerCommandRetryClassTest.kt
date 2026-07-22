package com.letta.mobile.data.transport.appserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppServerCommandRetryClassTest {

    private val scope = AppServerRuntimeScope(agentId = "a", conversationId = "c")

    @Test
    fun authIsSafeRead() {
        assertEquals(
            AppServerCommandRetryClass.SafeRead,
            AppServerCommandRetryClass.of(AppServerCommand.Auth(requestId = "r", token = "t")),
        )
    }

    @Test
    fun runtimeStartIsSafeRead() {
        val cmd = AppServerCommand.RuntimeStart(requestId = "r", agentId = "a", conversationId = "c")
        assertTrue(AppServerCommandRetryClass.isRetryableAfterAmbiguousDisconnect(cmd))
    }

    @Test
    fun syncIsSafeRead() {
        val cmd = AppServerCommand.Sync(runtime = scope, requestId = "r")
        assertEquals(AppServerCommandRetryClass.SafeRead, AppServerCommandRetryClass.of(cmd))
    }

    @Test
    fun inputMutationExposesClientMessageIdAsDedupKey() {
        val cmd = AppServerCommand.Input(
            runtime = scope,
            payload = AppServerInputPayload.CreateMessage(
                messages = listOf(AppServerInputMessage.userText("hi", clientMessageId = "cmid-1")),
            ),
        )
        val cls = AppServerCommandRetryClass.of(cmd)
        assertTrue(cls is AppServerCommandRetryClass.AmbiguousMutation)
        assertEquals("cmid-1", (cls as AppServerCommandRetryClass.AmbiguousMutation).dedupKey)
        assertFalse(AppServerCommandRetryClass.isRetryableAfterAmbiguousDisconnect(cmd))
    }

    @Test
    fun inputWithoutClientMessageIdHasNoDedupKey() {
        val cmd = AppServerCommand.Input(
            runtime = scope,
            payload = AppServerInputPayload.CreateMessage(
                messages = listOf(AppServerInputMessage.userText("hi")),
            ),
        )
        val cls = AppServerCommandRetryClass.of(cmd) as AppServerCommandRetryClass.AmbiguousMutation
        assertNull(cls.dedupKey)
    }

    @Test
    fun abortAndToolResponseAreAmbiguousMutations() {
        val abort = AppServerCommand.AbortMessage(runtime = scope, requestId = "r")
        val tool = AppServerCommand.ExternalToolCallResponse(requestId = "r")
        assertTrue(AppServerCommandRetryClass.of(abort) is AppServerCommandRetryClass.AmbiguousMutation)
        assertTrue(AppServerCommandRetryClass.of(tool) is AppServerCommandRetryClass.AmbiguousMutation)
        assertFalse(AppServerCommandRetryClass.isRetryableAfterAmbiguousDisconnect(abort))
    }

    @Test
    fun adminRpcReadMethodsAreSafe() {
        for (m in listOf("agent.list", "conversation.get", "message.list", "agent.context", "runtime.status", "block.search")) {
            val cmd = AppServerCommand.AdminRpc(requestId = "r", method = m)
            assertEquals(AppServerCommandRetryClass.SafeRead, AppServerCommandRetryClass.of(cmd), "method $m should be a safe read")
        }
    }

    @Test
    fun adminRpcMutationMethodsAreAmbiguous() {
        for (m in listOf("agent.create", "conversation.delete", "block.update", "schedule.create", "approval.submit")) {
            val cmd = AppServerCommand.AdminRpc(requestId = "r", method = m)
            assertTrue(
                AppServerCommandRetryClass.of(cmd) is AppServerCommandRetryClass.AmbiguousMutation,
                "method $m should be an ambiguous mutation",
            )
        }
    }
}
