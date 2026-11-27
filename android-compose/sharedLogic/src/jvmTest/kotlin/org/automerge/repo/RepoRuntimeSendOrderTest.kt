package org.automerge.repo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepoRuntimeSendOrderTest {
    @Test
    fun sendsOnOneConnectionWaitForThePreviousSend() {
        val runtimeClass = Class.forName("org.automerge.repo.RepoRuntime")
        val runtime = runtimeClass.declaredConstructors.single().apply { isAccessible = true }.newInstance(null, null)
        fun field(name: String) = runtimeClass.getDeclaredField(name).apply { isAccessible = true }.get(runtime)
        (field("stopped") as AtomicBoolean).set(true) // Avoid invoking the native hub in this isolated dispatch test.
        val connectionClass = Class.forName("org.automerge.repo.ConnectionId")
        val connection = connectionClass.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(1)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val transport = Transport({ bytes ->
            if (bytes[0].toInt() == 1) {
                firstEntered.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
            } else {
                secondEntered.countDown()
            }
        }, {})
        runtimeClass.getDeclaredMethod("driveConnection", connectionClass, Transport::class.java)
            .apply { isAccessible = true }.invoke(runtime, connection, transport)
        val sendClass = Class.forName("org.automerge.repo.HubIoAction\$Send")
        val idClass = Class.forName("org.automerge.repo.IoTaskId")
        val taskClass = Class.forName("org.automerge.repo.IoTask")
        val sendConstructor = sendClass.declaredConstructors.single().apply { isAccessible = true }
        val idConstructor = idClass.declaredConstructors.single().apply { isAccessible = true }
        val taskConstructor = taskClass.declaredConstructors.single().apply { isAccessible = true }
        val dispatch = runtimeClass.getDeclaredMethod("processNetworkTasks", List::class.java).apply { isAccessible = true }
        fun send(value: Int) {
            val action = sendConstructor.newInstance(connection, byteArrayOf(value.toByte()))
            dispatch.invoke(runtime, listOf(taskConstructor.newInstance(idConstructor.newInstance(value), action)))
        }
        try {
            send(1)
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            send(2)
            assertFalse(secondEntered.await(500, TimeUnit.MILLISECONDS), "Second send overtook a blocked first send")
            releaseFirst.countDown()
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
            val executor = field("serializingNetworkExecutor")
            val queues = executor.javaClass.getDeclaredField("queues").apply { isAccessible = true }.get(executor) as Map<*, *>
            assertTrue(queues.containsKey(connection))
            transport.onClose()
            assertFalse(queues.containsKey(connection), "Closed connection retained its send queue")
        } finally {
            releaseFirst.countDown()
            listOf("hubExecutor", "documentExecutor", "ioExecutor", "tickScheduler").forEach {
                (field(it) as ExecutorService).shutdownNow()
            }
        }
    }
}
