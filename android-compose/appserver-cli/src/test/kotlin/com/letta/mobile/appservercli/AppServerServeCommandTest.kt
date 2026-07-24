package com.letta.mobile.appservercli

import com.letta.mobile.appserver.AppServerServeSpec
import com.letta.mobile.appserver.AppServerServeSpecException
import com.letta.mobile.appserver.buildAppServerServeCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Wiring check that `:appserver-cli` resolves the shared serve-spec builder
 * (audit P1.6). Comprehensive argument/validation coverage lives in
 * `:sharedLogic`'s `AppServerServeSpecTest`.
 */
class AppServerServeCommandTest {
    @Test
    fun `resolves shared builder for a default loopback command`() {
        val command = buildAppServerServeCommand(AppServerServeSpec())

        assertEquals(
            listOf("letta", "app-server", "--listen", "ws://127.0.0.1:4500"),
            command,
        )
    }

    @Test
    fun `non loopback listen requires websocket auth`() {
        assertThrows(AppServerServeSpecException::class.java) {
            buildAppServerServeCommand(AppServerServeSpec(listen = "ws://0.0.0.0:4500"))
        }
    }
}
