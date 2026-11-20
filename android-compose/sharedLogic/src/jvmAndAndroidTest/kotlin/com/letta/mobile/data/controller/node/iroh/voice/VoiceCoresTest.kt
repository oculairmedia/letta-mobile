package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.model.AgentId
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The host's voice cores: read from each agent's own `voice` block, cached until that block changes or the entry ages. */
class VoiceCoresTest {
    private val root: File = Files.createTempDirectory("voice-cores").toFile()
    private val cores = VoiceCores { agentId, label -> File(File(root, agentId.value), "$label.md") }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun write(agentId: String, value: String, modified: Long) {
        val file = File(File(root, agentId).apply { mkdirs() }, "voice.md")
        file.writeText(value)
        file.setLastModified(modified)
    }

    @Test
    fun anAgentsCoreComesFromItsVoiceBlock() {
        write("agent-a", "A calm, low voice.", 1_000_000L)
        assertEquals("A calm, low voice.", cores.coreOf(AgentId("agent-a"))?.description)
    }

    @Test
    fun anUnchangedBlockIsServedFromTheCache() {
        write("agent-a", "A calm voice.", 1_000_000L)
        cores.coreOf(AgentId("agent-a"))
        cores.coreOf(AgentId("agent-a"))
        assertEquals(1, cores.reads)
    }

    @Test
    fun anEditedBlockIsReadAgain() {
        write("agent-a", "A calm voice.", 1_000_000L)
        cores.coreOf(AgentId("agent-a"))
        write("agent-a", "A bright, quick voice.", 2_000_000L)
        assertEquals("A bright, quick voice.", cores.coreOf(AgentId("agent-a"))?.description)
        assertEquals(2, cores.reads)
    }

    @Test
    fun aFailedReadIsNotCachedAndIsTriedAgain() {
        write("agent-a", "A calm voice.", 1_000_000L)
        var fail = true
        val flaky = VoiceCores(
            files = { agentId, label -> File(File(root, agentId.value), "$label.md") },
            reader = { file -> if (fail) throw java.io.IOException("busy") else file.readText() },
        )
        assertNull(flaky.coreOf(AgentId("agent-a")))
        fail = false
        assertEquals("A calm voice.", flaky.coreOf(AgentId("agent-a"))?.description, "the same unchanged file is read again")
        assertEquals(2, flaky.reads)
    }

    @Test
    fun anEditKeepingTheStampIsPickedUpOnceTheEntryAges() {
        var now = 0L
        val aging = VoiceCores(
            files = { agentId, label -> File(File(root, agentId.value), "$label.md") },
            clock = { now },
            maxAge = 1_000L,
        )
        write("agent-a", "warm", 1_000_000L)
        assertEquals("warm", aging.coreOf(AgentId("agent-a"))?.description)
        write("agent-a", "deep", 1_000_000L)
        now = 999L
        assertEquals("warm", aging.coreOf(AgentId("agent-a"))?.description, "a fresh entry is served from the cache")
        now = 1_000L
        assertEquals("deep", aging.coreOf(AgentId("agent-a"))?.description)
        assertEquals(2, aging.reads)
    }

    @Test
    fun noAgentNoBlockOrNoStoreMeansNoCore() {
        assertNull(cores.coreOf(null))
        assertNull(cores.coreOf(AgentId("agent-without-block")))
        assertNull(VoiceCores(files = null).coreOf(AgentId("agent-a")))
        write("agent-a", "A calm voice.", 1_000_000L)
        cores.coreOf(AgentId("agent-a"))
        File(File(root, "agent-a"), "voice.md").delete()
        assertNull(cores.coreOf(AgentId("agent-a")), "a removed block takes the core away")
    }
}
