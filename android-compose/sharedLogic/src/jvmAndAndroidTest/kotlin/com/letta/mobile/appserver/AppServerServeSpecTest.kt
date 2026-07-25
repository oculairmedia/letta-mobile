package com.letta.mobile.appserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppServerServeSpecTest {
    @Test
    fun defaultSpecLaunchesHostAppServerOnLoopback() {
        val command = buildAppServerServeCommand(AppServerServeSpec())

        assertEquals(
            listOf("letta", "app-server", "--listen", "ws://127.0.0.1:4500"),
            command,
        )
    }

    @Test
    fun passesThroughInstallAndSignedBearerAuthArguments() {
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
    fun preservesCapabilityTokenAuthFlags() {
        val command = buildAppServerServeCommand(
            AppServerServeSpec(
                wsAuth = "capability-token",
                wsTokenFile = "token.txt",
                wsTokenSha256 = "sha256",
            ),
        )

        assertEquals(
            listOf(
                "letta",
                "app-server",
                "--listen",
                "ws://127.0.0.1:4500",
                "--ws-auth",
                "capability-token",
                "--ws-token-file",
                "token.txt",
                "--ws-token-sha256",
                "sha256",
            ),
            command,
        )
    }

    @Test
    fun invalidAuthModeFailsBeforeLaunchingProcess() {
        assertFailsWith<AppServerServeSpecException> {
            buildAppServerServeCommand(AppServerServeSpec(wsAuth = "basic"))
        }
    }

    @Test
    fun nonLoopbackListenRequiresWebsocketAuth() {
        assertFailsWith<AppServerServeSpecException> {
            buildAppServerServeCommand(AppServerServeSpec(listen = "ws://0.0.0.0:4500"))
        }
    }

    @Test
    fun localhostListenRunsWithoutWebsocketAuth() {
        val command = buildAppServerServeCommand(
            AppServerServeSpec(listen = "ws://localhost:4500"),
        )

        assertEquals(
            listOf("letta", "app-server", "--listen", "ws://localhost:4500"),
            command,
        )
    }

    @Test
    fun blankValuesFailBeforeLaunchingProcess() {
        assertFailsWith<AppServerServeSpecException> {
            buildAppServerServeCommand(AppServerServeSpec(lettaCommand = " "))
        }
    }

    @Test
    fun nonPositiveClockSkewIsRejected() {
        assertFailsWith<AppServerServeSpecException> {
            buildAppServerServeCommand(
                AppServerServeSpec(
                    listen = "ws://localhost:4500",
                    wsMaxClockSkewSeconds = 0,
                ),
            )
        }
    }

    @Test
    fun malformedListenUrlIsRejected() {
        assertFailsWith<AppServerServeSpecException> {
            buildAppServerServeCommand(AppServerServeSpec(listen = "not a url"))
        }
    }

    @Test
    fun formattedCommandQuotesWhitespaceArguments() {
        val rendered = formatProcessCommand(
            listOf("letta", "app-server", "--ws-token-file", "C:\\Users\\Test User\\token.txt"),
        )

        assertEquals(
            "letta app-server --ws-token-file \"C:\\Users\\Test User\\token.txt\"",
            rendered,
        )
    }
}
