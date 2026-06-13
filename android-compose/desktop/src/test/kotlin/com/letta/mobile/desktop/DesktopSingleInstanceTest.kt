package com.letta.mobile.desktop

import java.nio.file.Files
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopSingleInstanceTest {
    @Test
    fun primaryWritesDynamicPortAndSecondaryNotifiesIt() {
        val stateDirectory = Files.createTempDirectory("letta-desktop-single-instance")
        val commandLatch = CountDownLatch(1)
        val commands = mutableListOf<DesktopIpcCommand>()
        val primary = assertIs<DesktopSingleInstance.Primary>(
            DesktopSingleInstance.acquire(stateDirectory) { command ->
                commands += command
                commandLatch.countDown()
            },
        )
        try {
            assertTrue(primary.port > 0)
            assertEquals(primary.port.toString(), Files.readString(stateDirectory.resolve("app.port")).trim())

            val secondary = assertIs<DesktopSingleInstance.Secondary>(
                DesktopSingleInstance.acquire(stateDirectory) {},
            )

            assertTrue(secondary.notifyPrimary())
            assertTrue(commandLatch.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(DesktopIpcCommand.ShowUserThatAppIsRunning), commands)
        } finally {
            primary.close()
            stateDirectory.deleteRecursively()
        }
    }

    @Test
    fun closingPrimaryRemovesPortFileAndAllowsNewPrimary() {
        val stateDirectory = Files.createTempDirectory("letta-desktop-single-instance")
        val portFile = stateDirectory.resolve("app.port")
        val first = assertIs<DesktopSingleInstance.Primary>(
            DesktopSingleInstance.acquire(stateDirectory) {},
        )

        first.close()
        assertFalse(Files.exists(portFile))

        val second = assertIs<DesktopSingleInstance.Primary>(
            DesktopSingleInstance.acquire(stateDirectory) {},
        )
        try {
            assertTrue(Files.exists(portFile))
            assertTrue(second.port > 0)
        } finally {
            second.close()
            stateDirectory.deleteRecursively()
        }
    }

    @Test
    fun secondaryReturnsFalseWhenPortFileIsMissing() {
        val stateDirectory = Files.createTempDirectory("letta-desktop-single-instance")
        val primary = assertIs<DesktopSingleInstance.Primary>(
            DesktopSingleInstance.acquire(stateDirectory) {},
        )
        try {
            Files.deleteIfExists(stateDirectory.resolve("app.port"))
            val secondary = assertIs<DesktopSingleInstance.Secondary>(
                DesktopSingleInstance.acquire(stateDirectory) {},
            )

            assertFalse(secondary.notifyPrimary())
        } finally {
            primary.close()
            stateDirectory.deleteRecursively()
        }
    }

    private fun java.nio.file.Path.deleteRecursively() {
        Files.walk(this).use { paths ->
            paths
                .sorted(Comparator.reverseOrder())
                .forEach(Files::deleteIfExists)
        }
    }
}
