package com.letta.mobile.data.node.host

import org.junit.Assume.assumeTrue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Windows identity against the real OS process table. It reads only JDK process handles, so it
 * runs wherever the JDK reports process start times (always on Windows).
 */
class WindowsProcessIdentityLiveTest {
    private val self = ProcessHandle.current()

    @Test
    fun aLiveProcessIsNamedByItsPidAndCreationTime() {
        val started = self.info().startInstant()
        assumeTrue("this JDK reports no process start time here", started.isPresent)
        val identity = WindowsNodeBindings.processIdentity(AppServerProcessSource.Owned { self.pid() })

        val first = identity.current()

        assertNotNull(first)
        assertEquals("pid:${self.pid()}:started:${started.get()}", first)
        assertEquals(first, identity.current())
    }

    @Test
    fun anExitedChildIsUnknown() {
        assumeTrue("this JDK reports no process start time here", self.info().startInstant().isPresent)
        val java = ProcessHandle.current().info().command()
        assumeTrue("no java launcher path to spawn a child with", java.isPresent)
        val child = ProcessBuilder(java.get(), "-version").redirectErrorStream(true).start()
        child.inputStream.readAllBytes()
        assertTrue(child.waitFor(30, TimeUnit.SECONDS))

        val identity = WindowsNodeBindings.processIdentity(AppServerProcessSource.Owned { child.pid() })

        assertNull(identity.current())
    }

    @Test
    fun noChildYetIsUnknown() {
        assertNull(WindowsNodeBindings.processIdentity(AppServerProcessSource.Owned { null }).current())
    }
}
