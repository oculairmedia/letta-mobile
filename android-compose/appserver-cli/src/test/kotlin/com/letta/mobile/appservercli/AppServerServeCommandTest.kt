package com.letta.mobile.appservercli

import com.github.ajalt.clikt.core.UsageError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AppServerServeCommandTest {
    @Test
    fun `default command launches host letta app server on loopback`() {
        val command = buildAppServerServeCommand(AppServerServeSpec())

        assertEquals(
            listOf(
                "letta",
                "app-server",
                "--listen",
                "ws://127.0.0.1:4500",
            ),
            command,
        )
    }

    @Test
    fun `command passes through install and websocket auth arguments`() {
        val command = buildAppServerServeCommand(
            AppServerServeSpec(
                listen = "ws://0.0.0.0:4500",
                lettaCommand = "pnpm",
                lettaArguments = listOf("dlx", "@letta-ai/letta-code@0.27.15"),
                wsAuth = "signed-bearer-token",
                wsSharedSecretFile = "secret.txt",
                wsIssuer = "meridian",
                wsAudience = "letta-mobile",
                wsMaxClockSkewSeconds = 60,
            ),
        )

        assertEquals(
            listOf(
                "pnpm",
                "dlx",
                "@letta-ai/letta-code@0.27.15",
                "app-server",
                "--listen",
                "ws://0.0.0.0:4500",
                "--ws-auth",
                "signed-bearer-token",
                "--ws-shared-secret-file",
                "secret.txt",
                "--ws-issuer",
                "meridian",
                "--ws-audience",
                "letta-mobile",
                "--ws-max-clock-skew-seconds",
                "60",
            ),
            command,
        )
    }

    @Test
    fun `invalid auth mode fails before launching process`() {
        assertThrows(UsageError::class.java) {
            buildAppServerServeCommand(
                AppServerServeSpec(wsAuth = "basic"),
            )
        }
    }

    @Test
    fun `non loopback listen requires websocket auth`() {
        assertThrows(UsageError::class.java) {
            buildAppServerServeCommand(
                AppServerServeSpec(listen = "ws://0.0.0.0:4500"),
            )
        }
    }

    @Test
    fun `localhost listen can run without websocket auth`() {
        val command = buildAppServerServeCommand(
            AppServerServeSpec(listen = "ws://localhost:4500"),
        )

        assertEquals(
            listOf(
                "letta",
                "app-server",
                "--listen",
                "ws://localhost:4500",
            ),
            command,
        )
    }

    @Test
    fun `formatted command quotes whitespace arguments`() {
        val rendered = formatProcessCommand(
            listOf("letta", "app-server", "--ws-token-file", "C:\\Users\\Test User\\token.txt"),
        )

        assertEquals(
            "letta app-server --ws-token-file \"C:\\Users\\Test User\\token.txt\"",
            rendered,
        )
    }
}
