package com.letta.mobile.runtime.local

import org.junit.Assert.assertFalse
import org.junit.Test

class EmbeddedLettaCodeRuntimeStatusTest {
    @Test
    fun runnableRemainsDisabledEvenWhenNativeAndAssetsArePresent() {
        assertFalse(status(nativeEnabled = true, assetsEnabled = true).runnable)
        assertFalse(status(nativeEnabled = false, assetsEnabled = true).runnable)
        assertFalse(status(nativeEnabled = true, assetsEnabled = false).runnable)
        assertFalse(status(nativeEnabled = false, assetsEnabled = false).runnable)
    }

    private fun status(
        nativeEnabled: Boolean,
        assetsEnabled: Boolean,
    ) = EmbeddedLettaCodeRuntimeStatus(
        nativeEnabled = nativeEnabled,
        assetsEnabled = assetsEnabled,
        version = "0.0.0-test",
        integrity = "sha512-test",
    )
}
