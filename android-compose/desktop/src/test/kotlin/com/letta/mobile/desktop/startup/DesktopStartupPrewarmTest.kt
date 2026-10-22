package com.letta.mobile.desktop.startup

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopStartupPrewarmTest {
    @Test
    fun everyStepRunsOnABackgroundThreadEvenAfterOneFails() {
        val threads = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)

        DesktopStartupPrewarm(
            { threads += Thread.currentThread().name },
            { error("this step's failure is the app's to meet later") },
            {
                threads += Thread.currentThread().name
                done.countDown()
            },
        ).start()

        assertTrue(done.await(5, TimeUnit.SECONDS), "the last step never ran")
        assertEquals(listOf("startup-prewarm", "startup-prewarm"), threads.toList())
        assertNotEquals(Thread.currentThread().name, threads.first())
    }
}
