package com.letta.mobile.data.shell

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellQuitGuardTest {
    @Test
    fun quitsWhenEverythingIsSaved() = runTest {
        var prepared = 0
        val guard = ShellQuitGuard { prepared++; true }

        assertTrue(guard.requestQuit())
        assertEquals(1, prepared)
    }

    @Test
    fun theFirstQuitThatCannotSaveIsRefusedAndTheSecondDiscards() = runTest {
        var prepared = 0
        val guard = ShellQuitGuard { prepared++; false }

        assertFalse(guard.requestQuit())
        assertTrue(guard.requestQuit())
        assertEquals(2, prepared)
    }

    @Test
    fun aQuitWhileAnotherIsStillSavingIsRefused() = runTest {
        val release = CompletableDeferred<Unit>()
        val guard = ShellQuitGuard { release.await(); false }

        val first = async { guard.requestQuit() }
        runCurrent()
        assertFalse(guard.requestQuit())
        release.complete(Unit)
        assertFalse(first.await())
        assertTrue(guard.requestQuit())
    }
}
