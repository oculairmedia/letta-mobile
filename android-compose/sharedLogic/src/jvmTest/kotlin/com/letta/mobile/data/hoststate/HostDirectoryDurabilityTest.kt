package com.letta.mobile.data.hoststate

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostDirectoryDurabilityTest {
    @Test
    fun windowsCannotConfirmDirectoryEntries() {
        assertFalse(HostDirectoryDurability.supported("Windows 11"))
        assertFalse(HostDirectoryDurability.supported("windows 10"))
        assertTrue(HostDirectoryDurability.supported("Linux"))
        assertTrue(HostDirectoryDurability.supported("Mac OS X"))
        assertTrue(HostDirectoryDurability.supported("Android"))
    }
}
