package com.letta.mobile.data.canvas.mount

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.HostId
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Clearing an agent default: authorized like a mutation (address, then clear), durable, and binding-preserving. */
class CanvasMountDefaultClearTest {
    private val host = HostId("host-one")
    private val agent = AgentId("agent-one")
    private val conversation = ConversationId("conversation-one")
    private val board = CanvasId("board")

    @Test
    fun theFileStoreRemovesOnlyTheDefaultAndSaysWhetherOneExisted() = runBlocking {
        val store = FileCanvasMountStore(Files.createTempDirectory("mount-clear"), host)
        store.bindIfAbsent(agent, conversation, board)
        store.setDefault(agent, board)

        assertEquals(CanvasMountMutation.Removed(written = true), store.clearDefault(agent))
        assertNull(store.readDefault(agent))
        assertEquals(board, store.readBinding(agent, conversation), "a binding is never touched by a clear")
        assertEquals(CanvasMountMutation.Removed(written = false), store.clearDefault(agent))
    }

    @Test
    fun theFacadeAsksAddressThenClearBeforeStorageAndAnswersCleared() = runBlocking {
        val log = mutableListOf<String>()
        val storage = RecordingStorage(log, stored = board)
        val facade = AuthorizedCanvasMounts(storage, RecordingAuthority(log, TableAuthority(Answer.ALLOW)))

        assertEquals(CanvasMountResult.DefaultCleared, facade.clearDefault(defaultRequest(agent.value)))
        assertEquals(listOf("address", "clear", "store.clearDefault"), log)
        assertNull(storage.stored)
    }

    @Test
    fun aRefusedOrFailingClearNeverReachesStorage() = runBlocking {
        listOf(Answer.DENY, Answer.ABSTAIN, Answer.THROW).forEach { answer ->
            val log = mutableListOf<String>()
            val storage = RecordingStorage(log, stored = board)
            val facade = AuthorizedCanvasMounts(storage, RecordingAuthority(log, TableAuthority(Answer.ALLOW, clear = answer)))
            assertEquals(CanvasMountResult.Denied, facade.clearDefault(defaultRequest(agent.value)), answer.name)
            assertEquals(listOf("address", "clear"), log, answer.name)
            assertEquals(board, storage.stored)
        }
    }

    @Test
    fun anAuthorityThatDoesNotDecideClearsDenies() = runBlocking {
        val undecided = object : CanvasMountAuthority {
            override suspend fun mayAddress(request: CanvasMountRequest<CanvasMountScope>) = CanvasMountDecision.ALLOW

            override suspend fun mayPropose(request: CanvasMountRequest<CanvasMountScope>, proposed: CanvasId) = CanvasMountDecision.ALLOW

            override suspend fun maySee(request: CanvasMountRequest<CanvasMountScope>, winner: CanvasId) = CanvasMountDecision.ALLOW
        }
        val storage = RecordingStorage(mutableListOf(), stored = board)
        assertIs<CanvasMountResult.Denied>(AuthorizedCanvasMounts(storage, undecided).clearDefault(defaultRequest(agent.value)))
        assertEquals(board, storage.stored)
    }
}
