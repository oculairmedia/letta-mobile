package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.iroh.discovery.ConversationDiscovery
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationDiscoveryBoundsTest {
    @Test
    fun cappedPassesDrainWithoutRescanningUnchangedDirectory() = runTest {
        val root = createTempDirectory("discovery-bounds").toFile()
        try {
            repeat(40) { directory(root, "conversation:local-conv-$it") }
            directory(root, "default:agent-1")
            directory(root, "foreign:local-conv-other")
            File(root, "bad%name").mkdir()
            val watched = CountingDirectory(root)
            var now = 0L
            val discovery = ConversationDiscovery(watched, null, ::decodeTestKey) { now }
            val server = RecordingClient(root)
            discovery.prime(server, true)
            assertEquals(32, server.ids.size)
            assertEquals(1, watched.reads)
            discovery.prime(server, true)
            assertEquals(32, server.ids.size)
            assertEquals(1, watched.reads)
            now += 5_000
            discovery.prime(server, true)
            assertEquals(40, server.ids.size)
            assertEquals(1, watched.reads)
            now += 5_000
            discovery.prime(server, true)
            assertEquals(40, server.ids.size)
            assertEquals(1, watched.reads)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun baselineDrainedOverPassesAndRereadIsNeverAnnounced() = runTest {
        val root = createTempDirectory("discovery-baseline").toFile()
        try {
            repeat(40) { directory(root, "conversation:local-conv-$it") }
            val announced = mutableListOf<String>()
            var now = 0L
            val discovery = ConversationDiscovery(
                root, null, ::decodeTestKey, onPrimed = { id, _ -> announced += id }, clock = { now },
            )
            val server = RecordingClient(root)
            discovery.prime(server, true)
            now += 5_000
            discovery.prime(server, true)
            // A full re-read of the same directory finds nothing new.
            now += 60_000
            discovery.prime(server, true)
            assertEquals(40, server.ids.size, "the baseline is still primed, over two capped passes")
            assertEquals(emptyList(), announced)
            directory(root, "conversation:local-conv-new")
            check(root.setLastModified(root.lastModified() + 10_000))
            now += 5_000
            discovery.prime(server, true)
            assertEquals(listOf("local-conv-new"), announced)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun periodicRecheckCoversCoarseDirectoryTimestamps() = runTest {
        val root = createTempDirectory("discovery-mtime").toFile()
        try {
            directory(root, "conversation:local-conv-first")
            var now = 0L
            val discovery = ConversationDiscovery(root, null, ::decodeTestKey) { now }
            val server = RecordingClient(root)
            discovery.prime(server, true)
            val oldMtime = root.lastModified()
            directory(root, "conversation:local-conv-later")
            check(root.setLastModified(oldMtime))
            now += 5_000
            discovery.prime(server, true)
            assertEquals(1, server.ids.size)
            now += 55_000
            discovery.prime(server, true)
            assertEquals(listOf("local-conv-first", "local-conv-later"), server.ids)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingDirectoryDoesNotAttemptRetrieval() = runTest {
        val root = createTempDirectory("discovery-missing").toFile()
        try {
            val server = RecordingClient(root)
            ConversationDiscovery(File(root, "missing"), null, ::decodeTestKey) { 0L }.prime(server, true)
            assertEquals(emptyList(), server.ids)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedRetrieveIsRetriedWithoutDirectoryMtimeChange() = runTest {
        val root = createTempDirectory("discovery-retry").toFile()
        try {
            val id = "local-conv-retry"
            directory(root, "conversation:$id")
            val server = RecordingClient(root, fail = true)
            assertRetriedWithoutRescan(root, id, server) { server.fail = false }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidRecordIsRetriedAfterRepairWithoutDirectoryChange() = runTest {
        val root = createTempDirectory("discovery-invalid-record").toFile()
        try {
            val id = "local-conv-repaired"
            directory(root, "conversation:$id")
            val name = Base64.getUrlEncoder().withoutPadding().encodeToString("conversation:$id".toByteArray())
            val file = File(root, "$name/conversation.json")
            file.writeText("{")
            val server = RecordingClient(root)
            assertRetriedWithoutRescan(root, id, server) {
                file.writeText("""{"id":"$id","agent_id":"agent-1"}""")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun serverKnownIdsDoNotConsumeTheNextPassBudget() = runTest {
        val root = createTempDirectory("discovery-known").toFile()
        try {
            repeat(40) { directory(root, "conversation:local-conv-$it") }
            var now = 0L
            val discovery = ConversationDiscovery(root, null, ::decodeTestKey) { now }
            val server = RecordingClient(root)
            discovery.prime(server, true)
            discovery.remember(JsonArray((0 until 40).map { id ->
                buildJsonObject { put("id", "local-conv-$id") }
            }))
            directory(root, "conversation:local-conv-new")
            root.setLastModified(root.lastModified() + 10_000)
            now += 5_000
            discovery.prime(server, true)
            assertEquals("local-conv-new", server.ids.last())
            assertEquals(33, server.ids.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun slowFailuresCannotBlockAListForTheWholeRetrievalCap() = runTest {
        val root = createTempDirectory("discovery-budget").toFile()
        try {
            repeat(32) { directory(root, "conversation:local-conv-$it") }
            val server = RecordingClient(root, fail = true, delayMs = 500)
            val discovery = ConversationDiscovery(root, null, ::decodeTestKey) { testScheduler.currentTime }
            discovery.prime(server, true)
            assertEquals(1_500L, testScheduler.currentTime)
            assertEquals(3, server.ids.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancelledRetrievalRemainsQueuedForRetry() = runTest {
        val root = createTempDirectory("discovery-cancel").toFile()
        try {
            directory(root, "conversation:local-conv-cancelled")
            val gate = CompletableDeferred<Unit>()
            val server = RecordingClient(root, gate = gate)
            var now = 0L
            val discovery = ConversationDiscovery(root, null, ::decodeTestKey) { now }
            val first = async { discovery.prime(server, true) }
            runCurrent()
            first.cancelAndJoin()
            gate.complete(Unit)
            now += 5_000
            discovery.prime(server, true)
            assertEquals(listOf("local-conv-cancelled", "local-conv-cancelled"), server.ids)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun concurrentCallsShareOnePass() = runTest {
        withOverlappingPasses("discovery-single-flight", "local-conv-one") {
            assertEquals(1, server.ids.size)
            gate.complete(Unit)
            first.await()
            second.await()
            assertEquals(1, server.ids.size)
        }
    }

    /**
     * After a restart every list request ran into the same pass: the ones arriving while it retrieved
     * waited for it on the scanning lock, then found the interval not elapsed and did nothing.
     */
    @Test
    fun aCallArrivingDuringAPassDoesNotWaitForIt() = runTest {
        withOverlappingPasses("discovery-no-wait", "local-conv-slow") {
            assertTrue(second.isCompleted, "a list never waits on another request's discovery pass")
            gate.complete(Unit)
            first.await()
            assertEquals(listOf("local-conv-slow"), server.ids)
        }
    }

    /** Two passes over one conversation, the second started while the first's retrieval is held at [gate]. */
    private class OverlappingPasses(
        val server: RecordingClient,
        val gate: CompletableDeferred<Unit>,
        val first: Deferred<Unit>,
        val second: Deferred<Unit>,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun TestScope.withOverlappingPasses(prefix: String, id: String, check: suspend OverlappingPasses.() -> Unit) {
        val root = createTempDirectory(prefix).toFile()
        try {
            directory(root, "conversation:$id")
            val gate = CompletableDeferred<Unit>()
            val server = RecordingClient(root, gate = gate)
            val discovery = ConversationDiscovery(root, null, ::decodeTestKey) { 0L }
            val first = async { discovery.prime(server, true) }
            runCurrent()
            val second = async { discovery.prime(server, true) }
            runCurrent()
            OverlappingPasses(server, gate, first, second).check()
        } finally {
            root.deleteRecursively()
        }
    }

    private fun decodeTestKey(name: String): String? {
        val key = String(Base64.getUrlDecoder().decode(name), Charsets.UTF_8)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(key.toByteArray()) == name)
        if (!key.startsWith("conversation:")) return null
        return key.removePrefix("conversation:").takeIf { it.isNotEmpty() && it != "default" }
    }

    private suspend fun ConversationDiscovery.prime(server: RecordingClient, authenticated: Boolean) {
        prime(authenticated, { _, _, _ -> }) { id, _ ->
            val response = server.conversationRetrieve(AppServerCommand.ConversationRetrieve("bounds", id))
            response.success && (response.conversation?.get("id") as? JsonPrimitive)?.contentOrNull == id
        }
    }

    private suspend fun assertRetriedWithoutRescan(root: File, id: String, server: RecordingClient, repair: () -> Unit) {
        val watched = CountingDirectory(root)
        var now = 0L
        val discovery = ConversationDiscovery(watched, null, ::decodeTestKey) { now }
        discovery.prime(server, true)
        repair()
        now += 5_000
        discovery.prime(server, true)
        assertEquals(listOf(id, id), server.ids)
        assertEquals(1, watched.reads)
    }

    private fun directory(root: File, key: String) {
        val name = Base64.getUrlEncoder().withoutPadding().encodeToString(key.toByteArray())
        val dir = File(root, name).apply { mkdir() }
        if (key.startsWith("conversation:")) {
            val id = key.removePrefix("conversation:")
            File(dir, "conversation.json").writeText("""{"id":"$id","agent_id":"agent-1"}""")
        }
    }

    private class CountingDirectory(root: File) : File(root.path) {
        var reads = 0
        override fun list(): Array<String>? {
            reads++
            return super.list()
        }
    }

    private class RecordingClient(
        private val root: File,
        var fail: Boolean = false,
        val gate: CompletableDeferred<Unit>? = null,
        val delayMs: Long = 0,
    ) : AppServerClient by FakeNativeClient() {
        val ids = mutableListOf<String>()
        override suspend fun conversationRetrieve(
            command: AppServerCommand.ConversationRetrieve,
        ): AppServerInboundFrame.ConversationRetrieveResponse {
            ids += command.conversationId
            gate?.await()
            if (delayMs > 0) delay(delayMs)
            val record = validRecord(command.conversationId).takeUnless { fail }
            return AppServerInboundFrame.ConversationRetrieveResponse(
                requestId = command.requestId,
                success = record != null,
                conversation = record,
            )
        }

        private fun validRecord(id: String): JsonObject? {
            val name = Base64.getUrlEncoder().withoutPadding().encodeToString("conversation:$id".toByteArray())
            val row = readValidTestConversation(File(root, name)) ?: return null
            if ((row["id"] as? JsonPrimitive)?.contentOrNull != id) return null
            val agentFree = (row["agent_free"] as? JsonPrimitive)?.booleanOrNull == true
            return if (agentFree) JsonObject(row + ("agent_id" to JsonNull)) else row
        }
    }
}
