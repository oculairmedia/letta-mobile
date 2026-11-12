package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.CanvasRelayHost
import com.letta.mobile.data.canvas.HostCanvasAccess
import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.HostCanvasCaller
import com.letta.mobile.data.canvas.InMemoryCanvasRelayStore
import com.letta.mobile.data.canvas.InMemoryHostCanvasDirectory
import com.letta.mobile.data.canvas.mount.AuthorizedCanvasMounts
import com.letta.mobile.data.canvas.mount.CanvasMountRequest
import com.letta.mobile.data.canvas.mount.CanvasMountResult
import com.letta.mobile.data.canvas.mount.CanvasMountScope
import com.letta.mobile.data.canvas.mount.FileCanvasMountStore
import com.letta.mobile.data.hoststate.HostDirectoryDurability
import com.letta.mobile.data.model.CanvasId
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S1: the host canvas tools follow the mount of their own runtime-stamped conversation and can never change it. */
class CanvasMountToolLookupTest {
    private val root = Files.createTempDirectory("canvas-mount-tools")
    private val relayStore = InMemoryCanvasRelayStore()
    private val store = FileCanvasMountStore(root, TEST_HOST, HostDirectoryDurability::syncMount)
    private val lookup = CanvasMountToolLookup(
        { authority -> AuthorizedCanvasMounts(store, authority) },
        testDeadline(10.seconds, 1.seconds),
    )
    private val backend = HostCanvasBackend(
        relay = CanvasRelayHost(relayStore, hostId = { "host" }),
        store = relayStore,
        directory = InMemoryHostCanvasDirectory(),
        mounts = lookup,
    )
    private val callerA = HostCanvasCaller(AGENT_A.value, CONV_A.value)
    private val callerB = HostCanvasCaller(AGENT_B.value, CONV_B.value)

    @Test
    fun withoutABindingAToolActsOnItsOwnConversationsCanvas() = runBlocking {
        val access = assertIs<HostCanvasAccess.Granted>(backend.ownConversation(callerA))
        assertEquals(CONV_A.value, access.entry.conversationId)
        assertEquals(CanvasId.forConversation(CONV_A), backend.currentCanvas(callerA))
    }

    @Test
    fun aBindingToACanvasTheAgentMayReadIsWhatAToolNamingNoCanvasActsOn() = runBlocking {
        val board = backend.create(callerA, "Shared board")
        store.replaceBinding(AGENT_A, CONV_A, board.id)
        val access = assertIs<HostCanvasAccess.Granted>(backend.ownConversation(callerA))
        assertEquals(board.id, access.entry.id)
        assertEquals(board.id, backend.currentCanvas(callerA))
        assertTrue(access.entry.acl.canWrite(callerA.agentId))
    }

    @Test
    fun aMountedCanvasCreatedByAnotherAgentIsGrantedByTheMountWithoutTouchingItsAcl() = runBlocking {
        val foreign = backend.create(callerB, "Agent B's board")
        store.replaceBinding(AGENT_A, CONV_A, foreign.id)
        val granted = assertIs<HostCanvasAccess.Granted>(backend.ownConversation(callerA))
        assertEquals(foreign.id, granted.entry.id)
        assertTrue(!granted.entry.acl.canRead(callerA.agentId), "the grant is the mount's, not the ACL's")
        assertEquals(foreign.id, backend.currentCanvas(callerA))
        assertTrue(backend.list(callerA).any { it.id == foreign.id })
        store.replaceBinding(AGENT_A, CONV_A, CanvasId.forConversation(CONV_A))
        assertIs<HostCanvasAccess.Denied>(backend.open(callerA, foreign.id))
        assertTrue(backend.list(callerA).none { it.id == foreign.id })
    }

    @Test
    fun anUnreadableMountRefusesInsteadOfGuessingABoard() = runBlocking {
        store.replaceBinding(AGENT_A, CONV_A, CanvasId("canvas-x"))
        Files.list(root.resolve("canvas-mounts")).use { files ->
            files.filter { it.fileName.toString().startsWith("binding-") }.toList()
        }.forEach { Files.writeString(it, "corrupt") }
        assertIs<HostCanvasAccess.Denied>(backend.ownConversation(callerA))
        assertNull(backend.currentCanvas(callerA))
    }

    @Test
    fun aToolReadsOnlyItsOwnConversationsMount() = runBlocking {
        store.replaceBinding(AGENT_A, CONV_A2, CanvasId("canvas-of-another-conversation"))
        assertEquals(CanvasId.forConversation(CONV_A), backend.currentCanvas(callerA))
    }

    @Test
    fun theRuntimeAuthorityRefusesEveryMutationOtherConversationsAndRemotePrincipals() = runBlocking {
        val facade = AuthorizedCanvasMounts(store, RuntimeToolAuthority(AGENT_A, CONV_A))
        val own = CanvasMountRequest(RuntimeToolAuthority.PRINCIPAL, CanvasMountScope.Conversation(AGENT_A, CONV_A))
        assertEquals(CanvasMountResult.Denied, facade.replaceBinding(own, CanvasId("canvas-agent-choice")))
        assertEquals(CanvasMountResult.Denied, facade.bindIfAbsent(own, CanvasId("canvas-agent-choice")))
        val defaultRequest = CanvasMountRequest(RuntimeToolAuthority.PRINCIPAL, CanvasMountScope.AgentDefault(AGENT_A))
        assertEquals(CanvasMountResult.Denied, facade.setDefault(defaultRequest, CanvasId("canvas-agent-choice")))
        val other = CanvasMountRequest(RuntimeToolAuthority.PRINCIPAL, CanvasMountScope.Conversation(AGENT_A, CONV_A2))
        assertEquals(CanvasMountResult.Denied, facade.read(other))
        val remote = CanvasMountRequest(PEER.principal(), CanvasMountScope.Conversation(AGENT_A, CONV_A))
        assertEquals(CanvasMountResult.Denied, facade.read(remote))
        assertNull(store.readBinding(AGENT_A, CONV_A))
    }

    private fun Peer.principal() = checkNotNull(com.letta.mobile.data.canvas.mount.CanvasMountPrincipal.fromVerifiedConnection(endpoint))
}
