package com.letta.mobile.ui.chat.surface

import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.library.CanvasBoardOwner
import com.letta.mobile.data.canvas.library.CanvasBoardSessions
import com.letta.mobile.data.canvas.library.CanvasPageHold
import com.letta.mobile.data.canvas.library.CanvasPageState
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/** A retained navigation store must not reuse a terminal page for a colliding host-local conversation id. */
class ChatCanvasGenerationTest {
    @Test
    fun theSameConversationIsFreshOnAnotherBackendAndWhenReturning() = runTest {
        val firstStore = InMemoryCanvasDocumentStore()
        val stores = listOf(firstStore, InMemoryCanvasDocumentStore(), firstStore)
        var creations = 0
        val owner = CanvasBoardOwner { CanvasBoardSessions(stores[creations++]) }
        val pages = mutableMapOf<String, CanvasPageHold>()
        val conversation = ConversationId("host-local-conversation")
        val retired = mutableListOf<Pair<CanvasBoardSessions, com.letta.mobile.data.canvas.CanvasSession>>()
        val sessions = mutableListOf<com.letta.mobile.data.canvas.CanvasSession>()
        for (backend in listOf(FirstBackend, SecondBackend, FirstBackend)) {
            owner.updateBackend(LettaConfig(backend, LettaConfig.Mode.SELF_HOSTED, "https://generation.invalid"))
            val context = owner.context.value
            val key = checkNotNull(chatCanvasKey(conversation.value, context.generation))
            val page = pages.getOrPut(key) { CanvasPageHold(owner, backgroundScope) }
            page.open(null, conversation, null)
            runCurrent()
            val session = assertIs<CanvasPageState.Ready>(page.state.value, "each owner generation needs a fresh retained page").session
            sessions.forEach { assertNotSame(it, session) }
            val probe = context.boards.acquirePage(null, conversation, null)
            assertSame(session, probe, "the page must belong to the current table, not a retired backend")
            context.boards.release(probe)
            assertEquals(1, context.boards.holds(session.canvasId))
            retired.forEach { (table, old) ->
                assertEquals(0, table.holds(old.canvasId))
                assertNull(table.registry.get(old.canvasId))
            }
            context.boards.registry.registerMount(session)
            retired += context.boards to session
            sessions += session
        }
        assertEquals(3, pages.size)
        owner.close()
        retired.forEach { (table, session) ->
            assertEquals(0, table.holds(session.canvasId))
            assertNull(table.registry.get(session.canvasId))
        }
        pages.values.forEach { assertSame(CanvasPageState.Closed, it.state.value) }
    }
}

private const val FirstBackend = "canvas-generation-first"
private const val SecondBackend = "canvas-generation-second"
