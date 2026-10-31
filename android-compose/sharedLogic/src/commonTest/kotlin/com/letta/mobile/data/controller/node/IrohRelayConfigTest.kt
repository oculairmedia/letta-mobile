package com.letta.mobile.data.controller.node

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IrohRelayConfigTest {

    @Test
    fun testCustomConfigThrowsOnEmptyList() {
        val exception = assertFailsWith<IllegalArgumentException> {
            IrohRelayConfig.Custom(emptyList())
        }
        assertEquals("Custom relay config must have at least one URL", exception.message)
    }

    @Test
    fun testCustomConfigSucceedsWithValidUrls() {
        val urls = listOf("https://relay.example.com")
        val config = IrohRelayConfig.Custom(urls)
        assertEquals(urls, config.urls)
    }
}
