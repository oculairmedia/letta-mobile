package com.letta.mobile.data.swarm

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageId
import com.letta.mobile.data.pairing.PairedHost
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-jvctq: the outbox and the device profile on disk, readable by the current user only. */
class FileSwarmOutboxTest {
    @Test
    fun entriesRoundTripAndAreRemovable() = runTest {
        val outbox = FileSwarmOutbox(tempDir().resolve("outbox"))
        val entry = OutboxEntry(
            id = ClientMessageId("swarm-1"),
            agentRef = AgentRef("PM-letta-mobile"),
            target = ConversationTarget.New,
            text = "[from: orchestrator]\n\nhello \"quoted\"",
            createdAtMs = 42,
            stage = OutboxStage.Accepted,
            conversation = ConversationId("conversation-1"),
            anchor = MessageId("message-0"),
        )

        outbox.put(entry)

        assertEquals(entry, outbox.get(entry.id))
        assertEquals(listOf(entry), outbox.list())
        outbox.remove(entry.id)
        assertNull(outbox.get(entry.id))
    }

    @Test
    fun outboxFilesAreOwnerOnly() = runTest {
        val directory = tempDir().resolve("outbox")
        FileSwarmOutbox(directory).put(
            OutboxEntry(ClientMessageId("swarm-2"), AgentRef("pm"), ConversationTarget.New, "secret plan", createdAtMs = 1),
        )

        assertOwnerOnly(directory.resolve("swarm-2.json"))
    }

    @Test
    fun pairedHostIsStoredOwnerOnlyAndUnpairForgetsIt() = runTest {
        val profile = SwarmDeviceProfile(tempDir().resolve("profile"))
        profile.hostStore("orchestrator-cli") { 7L }.saveAndActivate(PairedHost("a".repeat(64), "a".repeat(64)))
        profile.identity.loadOrCreate()

        val stored = profile.host()

        assertEquals("orchestrator-cli", stored?.deviceName)
        assertOwnerOnly(profile.directory.resolve(SwarmDeviceProfile.HOST_FILE))
        profile.unpair()
        assertNull(profile.host())
        assertTrue(Files.notExists(profile.keyFile))
    }

    private fun tempDir(): Path = createTempDirectory("swarm-outbox-test")

    private fun assertOwnerOnly(file: Path) {
        val posix = Files.getFileAttributeView(file, PosixFileAttributeView::class.java)
        if (posix != null) {
            assertEquals("rw-------", PosixFilePermissions.toString(posix.readAttributes().permissions()))
            return
        }
        val acl = Files.getFileAttributeView(file, AclFileAttributeView::class.java)
        val entries = acl.acl
        assertEquals(1, entries.size, "one ACL entry: $entries")
        assertEquals(acl.owner, entries.single().principal())
    }
}
