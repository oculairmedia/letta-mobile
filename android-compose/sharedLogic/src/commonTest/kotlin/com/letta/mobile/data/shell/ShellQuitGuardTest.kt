package com.letta.mobile.data.shell

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
}
