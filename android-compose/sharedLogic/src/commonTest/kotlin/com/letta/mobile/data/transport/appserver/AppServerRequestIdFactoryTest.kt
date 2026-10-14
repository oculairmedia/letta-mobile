package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AppServerRequestIdFactoryTest {

    @Test
    fun concurrentCallersNeverReceiveTheSameId() = runTest {
        val factory = AppServerRequestIdFactory("controller-req")
        val ids = withContext(Dispatchers.Default) {
            (0 until WORKERS).map {
                async { List(IDS_PER_WORKER) { factory() } }
            }.awaitAll().flatten()
        }
        assertEquals(WORKERS * IDS_PER_WORKER, ids.size)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun twoFactoriesWithTheSamePrefixNeverCollide() = runTest {
        val first = AppServerRequestIdFactory("app-server")
        val second = AppServerRequestIdFactory("app-server")
        val ids = withContext(Dispatchers.Default) {
            listOf(first, second).map { factory ->
                async { List(IDS_PER_WORKER) { factory() } }
            }.awaitAll()
        }
        assertTrue(ids[0].toSet().intersect(ids[1].toSet()).isEmpty())
    }

    @Test
    fun idsKeepGreppablePrefixAndDifferAcrossRestarts() {
        // A fresh factory models a process restart: its first id must not repeat
        // the previous process's first id against a long-lived server.
        val beforeRestart = AppServerRequestIdFactory("channel-restore", Random(1))()
        val afterRestart = AppServerRequestIdFactory("channel-restore", Random(2))()
        assertTrue(beforeRestart.startsWith("channel-restore-"))
        assertTrue(afterRestart.startsWith("channel-restore-"))
        assertNotEquals(beforeRestart, afterRestart)
    }

    private companion object {
        const val WORKERS = 64
        const val IDS_PER_WORKER = 500
    }
}
