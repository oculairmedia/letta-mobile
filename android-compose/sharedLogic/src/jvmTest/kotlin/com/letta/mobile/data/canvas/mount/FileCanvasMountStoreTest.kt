package com.letta.mobile.data.canvas.mount

import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.NotebookCanvasDocumentStore
import com.letta.mobile.data.canvas.NotebookDocumentIndex
import com.letta.mobile.data.canvas.NotebookHistoryArchive
import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.canvas.notes.NotebookCanvasIdentity
import com.letta.mobile.data.hoststate.DurableHostFile
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.HostId
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeFalse

class FileCanvasMountStoreTest {
    private val host = HostId("host-one")
    private val agent = AgentId("agent-one")
    private val conversation = ConversationId("conversation-one")
    private val first = CanvasId("semantic-board-one")
    private val second = CanvasId("semantic-board-two")

    @Test
    fun unsupportedDirectoryDurabilityStillPersistsMounts() = runBlocking {
        val directory = Files.createTempDirectory("canvas-mounts-unsupported")
        val unsupportedSync: (Path) -> Unit = {}
        val store = FileCanvasMountStore(directory, host, unsupportedSync)
        assertEquals(first, store.bindIfAbsent(agent, conversation, first).durable())
        assertEquals(second, store.replaceBinding(agent, conversation, second).durable())
        assertEquals(first, store.setDefault(agent, first).durable())
        val reopened = FileCanvasMountStore(directory, host, unsupportedSync)
        assertEquals(second, reopened.readBinding(agent, conversation))
        assertEquals(first, reopened.readDefault(agent))
    }

    @Test
    fun reopeningPreservesDefaultsAndIndependentOverrides() = runBlocking {
        val directory = Files.createTempDirectory("canvas-mounts")
        val store = FileCanvasMountStore(directory, host)
        assertNull(store.readBinding(agent, conversation))
        store.setDefault(agent, first)
        assertNull(store.readBinding(agent, conversation))
        assertEquals(first, store.bindIfAbsent(agent, conversation, first).durable())
        store.replaceBinding(agent, conversation, second)
        store.bindIfAbsent(agent, ConversationId("another"), first)
        store.setDefault(agent, second)
        val reopened = FileCanvasMountStore(directory, host)
        assertEquals(second, reopened.readDefault(agent))
        assertEquals(second, reopened.readBinding(agent, conversation))
        assertEquals(first, reopened.readBinding(agent, ConversationId("another")))
        assertNull(reopened.readBinding(AgentId("another-agent"), conversation))
        assertEquals(second, reopened.bindIfAbsent(agent, conversation, first).durable())
    }

    @Test
    fun concurrentInstancesRetainChangesAndBindHasOneWinner() = runBlocking {
        val directory = Files.createTempDirectory("canvas-mounts-concurrent")
        (1..20).map { number ->
            async(Dispatchers.Default) {
                FileCanvasMountStore(directory, host).setDefault(AgentId("agent-$number"), CanvasId("board-$number"))
            }
        }.awaitAll()
        (1..20).forEach { number ->
            assertEquals(CanvasId("board-$number"), FileCanvasMountStore(directory, host).readDefault(AgentId("agent-$number")))
        }
        val winners = (1..20).map { number ->
            async(Dispatchers.Default) {
                FileCanvasMountStore(directory, host).bindIfAbsent(agent, conversation, CanvasId("proposal-$number")).durable()
            }
        }.awaitAll()
        assertEquals(1, winners.distinct().size)
        assertEquals(winners.first(), FileCanvasMountStore(directory, host).readBinding(agent, conversation))
    }

    @Test
    fun versionOneFixtureUsesSemanticStringsNotDocumentMetadataObjects() = runBlocking {
        val directory = Files.createTempDirectory("canvas-mounts-v1-fixture")
        val store = FileCanvasMountStore(directory, host)
        store.setDefault(agent, first)
        store.bindIfAbsent(agent, conversation, first)
        Files.writeString(recordPath(directory, "default"),
            """{"version":1,"host":"host-one","agent":"agent-one","conversation":null,"canvas":"default-board"}""")
        Files.writeString(recordPath(directory, "binding"),
            """{"version":1,"host":"host-one","agent":"agent-one","conversation":"conversation-one","canvas":"override-board"}""")
        assertEquals(CanvasId("default-board"), store.readDefault(agent))
        assertEquals(CanvasId("override-board"), store.readBinding(agent, conversation))
    }

    @Test
    fun malformedAndUnsupportedFilesNeverResetOrMutate() = runBlocking {
        val directory = Files.createTempDirectory("canvas-mounts-invalid")
        val store = FileCanvasMountStore(directory, host)
        store.bindIfAbsent(agent, conversation, first)
        store.setDefault(agent, first)
        val file = recordPath(directory, "binding")
        val defaultFile = recordPath(directory, "default")
        val fields = """"host":"host-one","agent":"agent-one","conversation":"conversation-one","canvas":"x"}"""
        val prefix = """{"version":1,"host":"host-one","agent":"agent-one","conversation":"conversation-one","""
        val fixtures = listOf(
            "not-json", "", "{}",
            """{"version":2,""" + fields,
            """{"version":"1",""" + fields,
            """{"version":1,"version":1,""" + fields,
            prefix + """"canvas":"x","canv\u0061s":"y"}""",
            prefix + """"canvas":12}""",
            prefix + """"canvas":""}""",
            prefix + """"canvas":"x","extra":true}""",
            """{"version":1,"host":"other","agent":"agent-one","conversation":"conversation-one","canvas":"x"}""",
            """{"version":1,"host":"host-one","agent":"other","conversation":"conversation-one","canvas":"x"}""",
            """{"version":1,"host":"host-one","agent":"agent-one","conversation":"other","canvas":"x"}""",
            """{"version":1,"host":"host-one","defaults":[],"bindings":[]}""",
        )
        for (text in fixtures) {
            Files.writeString(file, text)
            assertFailsWith<Exception> { store.readBinding(agent, conversation) }
            store.bindIfAbsent(agent, conversation, first).notAppliedCause()
            store.replaceBinding(agent, conversation, first).notAppliedCause()
            assertEquals(text, Files.readString(file))
            // A corrupt binding does not poison an independent default, or vice versa.
            Files.deleteIfExists(defaultFile)
            store.setDefault(agent, first)
            assertEquals(first, store.readDefault(agent))
            Files.writeString(defaultFile, text)
            assertFailsWith<Exception> { store.readDefault(agent) }
            store.setDefault(agent, first).notAppliedCause()
            assertEquals(text, Files.readString(defaultFile))
        }
    }

    @Test
    fun failureBeforeReplaceDoesNotAcknowledgeOrEraseOtherWrites() = runBlocking {
        val directory = Files.createTempDirectory("canvas-mounts-before-replace")
        val good = FileCanvasMountStore(directory, host)
        good.setDefault(agent, first)
        val failing = FileCanvasMountStore(directory, host) { throw IOException("before replacement") }
        assertIs<IOException>(failing.replaceBinding(agent, conversation, second).notAppliedCause())
        good.setDefault(AgentId("other"), second)
        assertEquals(first, good.readDefault(agent))
        assertEquals(second, good.readDefault(AgentId("other")))
        assertNull(good.readBinding(agent, conversation))
    }

    @Test
    fun unsupportedDirectorySyncFailsVisiblyWithoutChangingExistingState(): Unit = runBlocking {
        val directory = Files.createTempDirectory("canvas-mounts-unsupported-sync")
        FileCanvasMountStore(directory, host).setDefault(agent, first)
        val file = recordPath(directory, "default")
        val before = Files.readAllBytes(file)
        val failing = FileCanvasMountStore(directory, host) { throw UnsupportedOperationException("injected unsupported sync") }
        assertIs<UnsupportedOperationException>(failing.setDefault(agent, second).notAppliedCause())
        assertTrue(before.contentEquals(Files.readAllBytes(file)))
    }

    @Test
    fun ambiguousReplacementIsReadBackAndReconfirmedOnRetry() = runBlocking {
        assumeFalse("Directory fsync fault injection requires a directory channel", isWindows())
        val directory = Files.createTempDirectory("canvas-mounts-after-replace")
        val syncs = AtomicInteger()
        val failing = FileCanvasMountStore(directory, host) { parent ->
            if (syncs.incrementAndGet() == 5) throw IOException("after replacement")
            forceDirectory(parent)
        }
        assertEquals(CanvasMountMutation.OutcomeUnknown, failing.bindIfAbsent(agent, conversation, first))
        assertTrue(Files.exists(recordPath(directory, "binding")), "Real replacement must have happened")
        val other = FileCanvasMountStore(directory, host)
        other.setDefault(AgentId("other"), second)
        assertEquals(CanvasMountMutation.Applied(first, written = false), failing.bindIfAbsent(agent, conversation, second))
        assertEquals(second, other.readDefault(AgentId("other")))
        assertTrue(syncs.get() >= 8, "Returning a winner must reconfirm parent, entry and stripe durability")
    }

    @Test
    fun retryCannotAcknowledgeWinnerWithoutDurabilityBarrier(): Unit = runBlocking {
        val directory = Files.createTempDirectory("canvas-mounts-retry")
        FileCanvasMountStore(directory, host).bindIfAbsent(agent, conversation, first)
        val failing = FileCanvasMountStore(directory, host) { throw IOException("durability unavailable") }
        assertIs<IOException>(failing.bindIfAbsent(agent, conversation, second).notAppliedCause())
    }

    @Test
    fun cancellationWhileWaitingForProcessLockReleasesRuntimeOwner() = runBlocking {
        val directory = Files.createTempDirectory("canvas-mounts-cancellation")
        val store = FileCanvasMountStore(directory, host)
        store.bindIfAbsent(agent, conversation, first)
        val file = recordPath(directory, "binding")
        val lockPath = stripeLock(file)
        val before = Files.readString(file)
        FileChannel.open(lockPath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            val lock = channel.lock()
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            val waiting = launch(Dispatchers.Default) {
                started.complete(Unit)
                FileCanvasMountStore(directory, host).replaceBinding(agent, conversation, second)
            }
            started.await()
            yield()
            waiting.cancelAndJoin()
            assertEquals(before, Files.readString(file))
            lock.release()
        }
        withTimeout(5_000) { FileCanvasMountStore(directory, host).replaceBinding(agent, conversation, second) }
        assertEquals(second, FileCanvasMountStore(directory, host).readBinding(agent, conversation))
    }

    @Test
    fun semanticIdsAreIndependentOfNotebookGenerationAndDefaultAliasesAreAgentScoped() = runBlocking {
        val directory = Files.createTempDirectory("canvas-mounts-semantic")
        val store = FileCanvasMountStore(directory, host)
        store.bindIfAbsent(agent, ConversationId("default"), first)
        store.bindIfAbsent(AgentId("other"), ConversationId("default"), second)
        val notebooksDirectory = Files.createTempDirectory("canvas-mount-notebooks")
        val original = NotebookLocalStore(notebooksDirectory, "mount-test").use { notebooks ->
            val documents = NotebookCanvasDocumentStore(notebooks)
            CanvasSession.create(documents, CanvasCreateOptions(canvasId = first, title = "Mounted board"))
            assertTrue(documents.awaitDurableSave(first))
            assertNotNull(NotebookCanvasIdentity(notebooks).notebookOf(first))
        }
        val compacted = NotebookHistoryArchive(notebooksDirectory).compactToNewDocument(
            original.value, 1_000L, NotebookDocumentIndex(notebooksDirectory), 1_024L,
        )
        assertNotEquals(original.value, compacted.newKey, "Actual notebook storage identity must change")
        val reopened = FileCanvasMountStore(directory, host)
        val mounted = assertNotNull(reopened.readBinding(agent, ConversationId("default")))
        assertEquals(first, mounted)
        assertEquals(second, reopened.readBinding(AgentId("other"), ConversationId("default")))
        NotebookLocalStore(notebooksDirectory, "mount-test").use { notebooks ->
            assertEquals("Mounted board", assertNotNull(NotebookCanvasDocumentStore(notebooks).get(mounted)).title)
        }
    }

    @Test
    fun cancellationInsideTransactionReleasesBothLocks() = runBlocking {
        val directory = Files.createTempDirectory("canvas-mounts-owner-cancellation")
        FileCanvasMountStore(directory, host).setDefault(agent, first)
        val path = recordPath(directory, "default")
        val file = DurableHostFile(path, stripeLock(path)) { }
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val owner = launch {
            file.locked {
                FileChannel.open(
                    stripeLock(path), java.nio.file.StandardOpenOption.WRITE,
                ).use { competing ->
                    assertFailsWith<java.nio.channels.OverlappingFileLockException> { competing.tryLock() }
                }
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        entered.await()
        owner.cancelAndJoin()
        withTimeout(5_000) { FileCanvasMountStore(directory, host).setDefault(agent, second) }
        assertEquals(second, FileCanvasMountStore(directory, host).readDefault(agent))
    }

    @Test
    fun transactionLockExcludesAnotherJvmProcess() = runBlocking {
        val directory = Files.createTempDirectory("canvas-mounts-process-lock")
        FileCanvasMountStore(directory, host).setDefault(agent, first)
        val path = recordPath(directory, "default")
        val file = DurableHostFile(path, stripeLock(path)) { }
        val classpath = listOf(FileCanvasMountStoreTest::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .joinToString(java.io.File.pathSeparator)
        val executable = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java")
        file.locked {
            val process = ProcessBuilder(
                executable.toString(), "-cp", classpath, CanvasMountLockProbe::class.java.name,
                stripeLock(path).toString(),
            ).redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS), "Lock probe must terminate")
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.exitValue(), output)
                assertTrue(output.contains("mount-lock-blocked"), output)
            } finally {
                process.destroyForcibly()
            }
        }
    }

    private fun recordPath(root: Path, kind: String): Path = Files.list(root.resolve("canvas-mounts")).use { paths ->
        paths.filter { it.fileName.toString().startsWith("$kind-") }
            .map { it.resolveSibling(it.fileName.toString().removeSuffix(".lock")) }
            .distinct().toList().single()
    }

    private fun isWindows() = System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)
    private fun forceDirectory(directory: Path) = FileChannel.open(directory).use { it.force(true) }
}

private fun stripeLock(record: Path): Path = record.parent.resolve("locks")
    .resolve("${record.fileName.toString().substringAfter('-').take(2)}.lock")

/** A separate JVM proves the lock is not merely a coroutine or process-local mutex. */
object CanvasMountLockProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        FileChannel.open(Path.of(arguments.single()), java.nio.file.StandardOpenOption.WRITE).use { channel ->
            val lock = channel.tryLock()
            if (lock != null) {
                lock.release()
                error("Mount transaction did not exclude another process")
            }
            println("mount-lock-blocked")
        }
    }
}
