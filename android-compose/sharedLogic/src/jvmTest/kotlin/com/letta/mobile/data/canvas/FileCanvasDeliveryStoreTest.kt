package com.letta.mobile.data.canvas

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-qygvv.23: the relay advances a cursor for every op it applies; the file behind it is
 * read and written on the store's IO dispatcher, never on the thread that called.
 */
class FileCanvasDeliveryStoreTest {
    private lateinit var tempDir: Path
    private val ioExecutor = Executors.newSingleThreadExecutor { Thread(it, IO_THREAD) }
    private val io = Recording(ioExecutor.asCoroutineDispatcher())

    /** Counts what it is handed and remembers the threads its work ran on. */
    private class Recording(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        val threads = mutableSetOf<String>()

        override fun dispatch(context: CoroutineContext, block: Runnable) =
            delegate.dispatch(context) {
                synchronized(threads) { threads += Thread.currentThread().name }
                block.run()
            }
    }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("canvas_delivery_test_")
    }

    @AfterTest
    fun tearDown() {
        ioExecutor.shutdownNow()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun advancingACursorWritesTheFileOnTheIoDispatcher() = runBlocking {
        val file = tempDir.resolve("delivery.json").toFile()
        val store = FileCanvasDeliveryStore(file, io)

        store.advanceCursor(HOST, TOPIC, 23L)

        assertTrue(file.isFile, "the cursor is on disk")
        assertEquals(setOf(IO_THREAD), io.threads, "every read and write ran on the IO dispatcher")
        assertTrue(Thread.currentThread().name != IO_THREAD, "and not on the caller's thread")
    }

    @Test
    fun aCursorWrittenIsReadBackByTheNextStore() = runBlocking {
        val file = tempDir.resolve("delivery.json").toFile()
        FileCanvasDeliveryStore(file, io).advanceCursor(HOST, TOPIC, 23L)

        assertEquals(23L, FileCanvasDeliveryStore(file, io).cursor(HOST, TOPIC))
    }

    private companion object {
        const val IO_THREAD = "canvas-delivery-io"
        const val HOST = "host-1"
        const val TOPIC = "topic-1"
    }
}
