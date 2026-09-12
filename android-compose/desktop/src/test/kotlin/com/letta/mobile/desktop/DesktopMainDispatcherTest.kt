package com.letta.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Desktop must resolve `Dispatchers.Main` to the AWT event queue Compose renders on.
 *
 * This is not a style preference. Whichever `MainDispatcherFactory` is on the classpath with the
 * highest load priority owns `Dispatchers.Main` for the whole process, and a backend we do not run
 * installs a dispatcher that only queues — tasks sent to it are never executed and never fail.
 * paging-compose hardcodes `Dispatchers.Main` as its presenter dispatcher on desktop, so a dead
 * Main dispatcher renders every paged list as a permanent spinner with the rows already loaded and
 * no error anywhere (letta-mobile-x13xi).
 */
class DesktopMainDispatcherTest {
    @Test
    fun mainDispatcherActuallyRunsWorkOnTheAwtEventQueue() = runBlocking {
        val thread = withTimeout(SECONDS_10) {
            withContext(Dispatchers.Main) { Thread.currentThread().name }
        }

        assertTrue(
            thread.startsWith("AWT-EventQueue"),
            "Dispatchers.Main ran on $thread; desktop Compose renders on the AWT event queue",
        )
    }

    @Test
    fun immediateMainDispatcherRunsOnTheAwtEventQueueToo() = runBlocking {
        val thread = withTimeout(SECONDS_10) {
            withContext(Dispatchers.Main.immediate) { Thread.currentThread().name }
        }

        assertTrue(
            thread.startsWith("AWT-EventQueue"),
            "Dispatchers.Main.immediate ran on $thread; desktop Compose renders on the AWT event queue",
        )
    }

    private companion object {
        // Generous on purpose: a wrong dispatcher does not run late, it never runs at all.
        const val SECONDS_10 = 10_000L
    }
}
