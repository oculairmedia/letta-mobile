package com.letta.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopJewelWindowTest {
    @Test
    fun windowsWithJetBrainsRuntimeUsesJewelDecoratedChrome() {
        assertEquals(
            DesktopWindowChrome.JewelDecorated,
            selectDesktopWindowChrome(osName = "Windows 11", isJetBrainsRuntimeAvailable = true),
        )
    }

    @Test
    fun windowsWithoutJetBrainsRuntimeKeepsSystemChrome() {
        assertEquals(
            DesktopWindowChrome.JewelSystemDecorated,
            selectDesktopWindowChrome(osName = "Windows 11", isJetBrainsRuntimeAvailable = false),
        )
    }

    @Test
    fun nonWindowsKeepsSystemChrome() {
        assertEquals(
            DesktopWindowChrome.JewelSystemDecorated,
            selectDesktopWindowChrome(osName = "Linux", isJetBrainsRuntimeAvailable = true),
        )
    }
}
