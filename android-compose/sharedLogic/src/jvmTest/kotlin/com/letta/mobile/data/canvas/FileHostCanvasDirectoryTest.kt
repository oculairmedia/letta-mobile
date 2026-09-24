package com.letta.mobile.data.canvas

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** The host's canvas directory survives a restart, and the first binding of a canvas stands. */
class FileHostCanvasDirectoryTest {
    private fun entry(id: String, agent: String, conversation: String? = null) = HostCanvasEntry(
        canvasId = id,
        topic = "canvas:$id",
        title = id,
        conversationId = conversation,
        acl = CanvasAcl(ownerUserId = CanvasSession.LOCAL_USER_ACTOR_ID, writerAgentIds = setOf(agent)),
    )

    @Test
    fun entriesSurviveARestartAndAreNeverReplaced() = runTest {
        val dir = Files.createTempDirectory("host-canvases")
        try {
            val file = dir.resolve("host-canvases.json")
            val first = FileHostCanvasDirectory(file)
            first.putIfAbsent(entry("c1", "agent-1", conversation = "conv-1"))
            first.putIfAbsent(entry("c2", "agent-1"))

            val reopened = FileHostCanvasDirectory(file)
            assertEquals(listOf("c1", "c2"), reopened.all().map { it.canvasId })
            assertEquals("c1", reopened.forConversation("conv-1")?.canvasId)

            val claimAgain = reopened.putIfAbsent(entry("c1", "agent-2"))
            assertEquals(setOf("agent-1"), claimAgain.acl.writerAgentIds, "a second claim cannot rewrite the ACL")
            val sameConversation = reopened.putIfAbsent(entry("c9", "agent-2", conversation = "conv-1"))
            assertEquals("c1", sameConversation.canvasId, "one canvas per conversation")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
