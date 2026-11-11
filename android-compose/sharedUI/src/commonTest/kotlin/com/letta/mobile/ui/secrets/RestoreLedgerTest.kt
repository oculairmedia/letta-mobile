package com.letta.mobile.ui.secrets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reference counting the Android secure surface applies to `FLAG_SECURE` (and the autofill and
 * content-sensitivity settings), over a stand-in window: the flag is set by the first surface only,
 * kept while any surface remains, and put back to what the window had before the first one.
 */
class RestoreLedgerTest {
    /** A stand-in for an Android window's secure flag. */
    private class FakeWindow(var secure: Boolean = false)

    /** What the Android binding does on enter and exit, against a [FakeWindow]. */
    private class FlagBinding(private val ledger: RestoreLedger<FakeWindow, Boolean>) {
        fun enter(window: FakeWindow) {
            val already = window.secure
            if (ledger.acquire(window, already) && !already) window.secure = true
        }

        fun exit(window: FakeWindow) {
            val release = ledger.release(window)
            if (release is LedgerRelease.Restore && !release.previous) window.secure = false
        }
    }

    @Test
    fun twoSurfacesOnOneWindowDoNotClearEachOther() {
        val ledger = RestoreLedger<FakeWindow, Boolean>()
        val binding = FlagBinding(ledger)
        val window = FakeWindow()
        binding.enter(window)
        binding.enter(window)
        binding.exit(window)
        assertTrue(window.secure, "the second surface still needs the flag")
        binding.exit(window)
        assertFalse(window.secure, "the last surface put the flag back")
        assertEquals(0, ledger.size)
    }

    @Test
    fun aWindowThatWasAlreadySecureStaysSecure() {
        val binding = FlagBinding(RestoreLedger())
        val window = FakeWindow(secure = true)
        binding.enter(window)
        binding.exit(window)
        assertTrue(window.secure)
    }

    @Test
    fun windowsAreCountedSeparatelyAndAnUnpairedReleaseChangesNothing() {
        val ledger = RestoreLedger<FakeWindow, Boolean>()
        val binding = FlagBinding(ledger)
        val dialog = FakeWindow()
        val activity = FakeWindow()
        binding.enter(dialog)
        binding.enter(activity)
        binding.exit(dialog)
        assertFalse(dialog.secure)
        assertTrue(activity.secure)
        assertEquals(LedgerRelease.NotHeld, ledger.release(dialog))
        binding.exit(activity)
        assertFalse(activity.secure)
    }

    @Test
    fun anIntSettingIsRestoredToItsFirstValue() {
        val ledger = RestoreLedger<String, Int>()
        assertTrue(ledger.acquire("view", 1))
        assertFalse(ledger.acquire("view", 8))
        assertEquals(LedgerRelease.StillHeld, ledger.release("view"))
        assertEquals(LedgerRelease.Restore(1), ledger.release("view"))
    }
}
