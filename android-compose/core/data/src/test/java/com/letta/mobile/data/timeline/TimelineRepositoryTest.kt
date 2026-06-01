package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import io.ktor.utils.io.ByteReadChannel
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("integration")
class TimelineRepositoryTest {

    @Test
    fun `clear cancels cached loop stream subscriber before removing it`() = runBlocking {
        val api = CancellableStreamApi()
        val repository = TimelineRepository(api, NoOpPendingLocalStore, maxCachedLoops = 4)

        repository.getOrCreate("conv-clear")
        api.awaitActive("conv-clear")

        repository.clear("conv-clear")

        api.awaitClosed("conv-clear")
        assertEquals(0, repository.cachedLoopCount())
        assertFalse("cleared loop should no longer have an active stream", api.isActive("conv-clear"))
    }

    @Test
    fun `cache evicts least recently used loop and keeps recently accessed loop active`() = runBlocking {
        val api = CancellableStreamApi()
        val repository = TimelineRepository(api, NoOpPendingLocalStore, maxCachedLoops = 2)

        repository.getOrCreate("conv-a")
        repository.getOrCreate("conv-b")
        api.awaitActive("conv-a")
        api.awaitActive("conv-b")

        repository.getOrCreate("conv-a") // refresh access order, making conv-b eldest
        repository.getOrCreate("conv-c")

        api.awaitClosed("conv-b")
        api.awaitActive("conv-c")

        assertEquals(2, repository.cachedLoopCount())
        assertTrue("recently accessed loop should remain active", api.isActive("conv-a"))
        assertTrue("new loop should remain active", api.isActive("conv-c"))
        assertFalse("least recently used loop should be closed", api.isActive("conv-b"))

        repository.clearAll()
    }

    @Test
    fun `post handler collapse cache hit is synchronized and refreshes access order`() = runBlocking {
        val api = CancellableStreamApi()
        val repository = TimelineRepository(api, NoOpPendingLocalStore, maxCachedLoops = 2)

        repository.getOrCreate("conv-a")
        repository.getOrCreate("conv-b")
        api.awaitActive("conv-a")
        api.awaitActive("conv-b")

        repository.postHandlerCollapse("conv-a") // refresh access order, making conv-b eldest
        repository.getOrCreate("conv-c")

        api.awaitClosed("conv-b")
        api.awaitActive("conv-c")

        assertEquals(2, repository.cachedLoopCount())
        assertTrue("postHandlerCollapse access should keep conv-a active", api.isActive("conv-a"))
        assertTrue("new loop should remain active", api.isActive("conv-c"))
        assertFalse("least recently used loop should be closed", api.isActive("conv-b"))

        repository.clearAll()
    }

    @Test
    fun `getOrCreate hydrates on background dispatcher even when caller is main-like`() = runBlocking {
        val api = CancellableStreamApi()
        val repository = TimelineRepository(api, NoOpPendingLocalStore, maxCachedLoops = 4)
        val callerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "caller-main-probe")
        }.asCoroutineDispatcher()

        try {
            withContext(callerDispatcher) {
                repository.getOrCreate("conv-dispatch")
            }
        } finally {
            callerDispatcher.close()
        }

        val hydrateThread = api.listMessageThreads.getValue("conv-dispatch")
        assertFalse(
            "hydrate should not run on caller dispatcher, ran on $hydrateThread",
            hydrateThread.contains("caller-main-probe"),
        )

        repository.clearAll()
    }
}

private class CancellableStreamApi : MessageApi(mockk(relaxed = true)) {
    private val activeStreams = ConcurrentHashMap<String, AtomicInteger>()
    private val closedStreams = ConcurrentHashMap<String, AtomicInteger>()
    val listMessageThreads = ConcurrentHashMap<String, String>()

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        activeStreams.counter(conversationId.value).incrementAndGet()
        try {
            awaitCancellation()
        } finally {
            activeStreams.counter(conversationId.value).decrementAndGet()
            closedStreams.counter(conversationId.value).incrementAndGet()
        }
    }

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> {
        listMessageThreads[conversationId.value] = Thread.currentThread().name
        return emptyList()
    }

    fun isActive(conversationId: String): Boolean =
        activeStreams[conversationId]?.get()?.let { it > 0 } == true

    suspend fun awaitActive(conversationId: String) = withTimeout(5_000) {
        while (!isActive(conversationId)) delay(10)
    }

    suspend fun awaitClosed(conversationId: String) = withTimeout(5_000) {
        while (closedStreams[conversationId]?.get()?.let { it > 0 } != true) delay(10)
    }

    private fun ConcurrentHashMap<String, AtomicInteger>.counter(conversationId: String): AtomicInteger =
        computeIfAbsent(conversationId) { AtomicInteger(0) }
}
