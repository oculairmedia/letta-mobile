package com.letta.mobile.data.controller.extras

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-bn008-phase2-custom-tool (1vuec): the JVM/Android runner that
 * actually spawns `meridian agent-message send` as a subprocess.
 *
 * Tests here use shell scripts the test harness writes under [TempDir] as
 * stand-ins for the real `meridian` binary. The runner MUST:
 *   - Pipe the body through stdin (`--body-file -`) — never via `--body "<...>"`
 *     shell escaping. This is the load-bearing line that fixes the Meridian→
 *     Lester regression.
 *   - Pass `--from`, `--to`, `--msg-id` as discrete argv tokens (NOT in a
 *     shell-quoted blob) — every element of the argv list reaches the
 *     child without a shell parsing layer.
 *   - Surface stdout JSON as Delivered when exit 0 + ok:true, Unaddressable
 *     when exit != 0 + error=unaddressable, Failed otherwise.
 *   - Honor its timeout by destroying the child when it overruns.
 */
class DefaultIrohCliRunnerTest {

    @BeforeTest
    fun setUp() {
        org.junit.Assume.assumeFalse(System.getProperty("os.name").lowercase().contains("windows"))
    }

    /**
     * The most important test in the suite: the multi-line body MUST round
     * trip through stdin unchanged. This pins bn008-phase2-handoff risk #1
     * (Meridian→Lester body collapse) at the wrapper boundary.
     */
    @Test
    fun multiLineBodyRoundTripsThroughStdin() = runBlocking {
        val tmp = kotlin.io.path.createTempDirectory("iroh-runner-test").toFile()
        val stdoutFile = File(tmp, "stdout.txt")
        val bodyIn = "Line 1: hello\nLine 2: world\nLine 3: special \"quoted\" & <url> https://x?a=1&b=2\n"
        val script = writeCaptureScript(
            tmp = tmp,
            name = "exit-ok.sh",
            stdoutFile = stdoutFile,
            body = """
                #!/bin/sh
                cat > "@STDOUT_PATH@"
                printf '{"ok":true,"accepted":true,"applicationDelivered":true,"msgId":"echo-id"}'
                exit 0
            """.trimIndent(),
        )
        val runner = DefaultIrohCliRunner(invocationTimeoutMs = 10_000L)
        val result = runner.send(
            binary = script.absolutePath,
            fromAgentId = "agent-sender",
            toAgentId = "agent-target",
            body = bodyIn,
            paths = IrohCliPaths(),
        )
        assertEquals(
            bodyIn,
            stdoutFile.readText(),
            "stdin body must round-trip byte-for-byte (newlines, quotes, ampersands, URL all preserved)",
        )
        assertTrue(
            result is IrohCliSendResult.Delivered,
            "exit 0 + ok:true must yield Delivered, got $result",
        )
    }

    @Test
    fun deliveredMapsToDelivered() = runBlocking {
        val tmp = kotlin.io.path.createTempDirectory("iroh-runner-test").toFile()
        val script = writeCaptureScript(
            tmp = tmp,
            name = "ok.sh",
            stdoutFile = File(tmp, "stdout.txt"),
            body = """
                #!/bin/sh
                cat > /dev/null
                printf '{"ok":true,"accepted":true,"applicationDelivered":true,"msgId":"live-msg-6f2963bb-4936-4fff-97b2-0604128e2f42","futureField":"ignored"}'
                exit 0
            """.trimIndent(),
        )
        val runner = DefaultIrohCliRunner()
        val result = runner.send(
            binary = script.absolutePath,
            fromAgentId = "from",
            toAgentId = "to",
            body = "hi",
            paths = IrohCliPaths(),
        )
        assertTrue(
            result is IrohCliSendResult.Delivered,
            "exit 0 + ok:true must yield Delivered, got: $result",
        )
        assertEquals("live-msg-6f2963bb-4936-4fff-97b2-0604128e2f42", (result as IrohCliSendResult.Delivered).msgId)
    }

    @Test
    fun acceptedButNotDeliveredIsNotReportedAsDelivered() = runBlocking {
        val tmp = kotlin.io.path.createTempDirectory("iroh-runner-test").toFile()
        val script = writeCaptureScript(tmp, "accepted.sh", File(tmp, "stdout.txt"), """
            #!/bin/sh
            cat >/dev/null
            printf '{"ok":false,"accepted":true,"applicationDelivered":false,"msgId":"m-accepted","reason":"delivery_timeout","future":123}'
            exit 1
        """.trimIndent())
        val result = DefaultIrohCliRunner().send(script.absolutePath, "from", "to", "hi", IrohCliPaths())
        assertTrue(result is IrohCliSendResult.Accepted)
        assertEquals("m-accepted", (result as IrohCliSendResult.Accepted).msgId)
    }

    @Test
    fun malformedAndContradictoryPayloadsFailClosed() = runBlocking {
        fun script(payload: String): File {
            val tmp = kotlin.io.path.createTempDirectory("iroh-runner-test").toFile()
            return writeCaptureScript(tmp, "bad.sh", File(tmp, "stdout.txt"), """
                #!/bin/sh
                cat >/dev/null
                printf '$payload'
                exit 0
            """.trimIndent())
        }
        for (payload in listOf(
            "{}",
            "{\"msgId\":\"m\",\"accepted\":false,\"applicationDelivered\":true}",
            "{\"msgId\":\"m\",\"accepted\":\"yes\",\"applicationDelivered\":true}",
        )) {
            val result = DefaultIrohCliRunner().send(script(payload).absolutePath, "from", "to", "hi", IrohCliPaths())
            assertTrue(result is IrohCliSendResult.Failed, "payload must fail closed: $payload")
        }
    }

    @Test
    fun unaddressableJsonMapsToUnaddressable() = runBlocking {
        val tmp = kotlin.io.path.createTempDirectory("iroh-runner-test").toFile()
        val script = writeCaptureScript(
            tmp = tmp,
            name = "unaddr.sh",
            stdoutFile = File(tmp, "stdout.txt"),
            body = """
                #!/bin/sh
                cat > /dev/null
                printf '{"ok":false,"accepted":false,"applicationDelivered":false,"error":"unaddressable","toAgentId":"to","reason":"no_kv_row","msgId":"m-unaddr"}'
                exit 1
            """.trimIndent(),
        )
        val runner = DefaultIrohCliRunner()
        val result = runner.send(
            binary = script.absolutePath,
            fromAgentId = "from",
            toAgentId = "to",
            body = "hi",
            paths = IrohCliPaths(),
        )
        assertTrue(
            result is IrohCliSendResult.Unaddressable,
            "exit 1 + error=unaddressable must yield Unaddressable, got: $result",
        )
        val unr = result as IrohCliSendResult.Unaddressable
        assertEquals("to", unr.toAgentId)
        assertTrue(unr.reason.isNotEmpty())
    }

    @Test
    fun nonZeroExitWithoutUnaddressableMapsToFailed() = runBlocking {
        val tmp = kotlin.io.path.createTempDirectory("iroh-runner-test").toFile()
        val script = writeCaptureScript(
            tmp = tmp,
            name = "fail.sh",
            stdoutFile = File(tmp, "stdout.txt"),
            body = """
                #!/bin/sh
                cat > /dev/null
                printf '{"ok":false,"accepted":false,"applicationDelivered":false,"error":"failed","msgId":"m-fail","reason":"dial_timed_out"}'
                exit 1
            """.trimIndent(),
        )
        val runner = DefaultIrohCliRunner()
        val result = runner.send(
            binary = script.absolutePath,
            fromAgentId = "from",
            toAgentId = "to",
            body = "hi",
            paths = IrohCliPaths(),
        )
        assertTrue(
            result is IrohCliSendResult.Failed,
            "exit 1 without unaddressable marker must yield Failed, got: $result",
        )
        val failed = result as IrohCliSendResult.Failed
        assertTrue(
            failed.reason.contains("dial_timed_out"),
            "stderr should be surfaced as the failure reason, got: ${failed.reason}",
        )
        assertEquals("to", failed.toAgentId)
    }

    @Test
    fun exitZeroButOkFalseMapsToFailed() = runBlocking {
        // Defensive: CLI should never exit 0 with ok:false, but if it
        // does we degrade to Failed rather than crash.
        val tmp = kotlin.io.path.createTempDirectory("iroh-runner-test").toFile()
        val script = writeCaptureScript(
            tmp = tmp,
            name = "weird.sh",
            stdoutFile = File(tmp, "stdout.txt"),
            body = """
                #!/bin/sh
                cat > /dev/null
                printf '{"ok":false,"accepted":false,"applicationDelivered":false,"error":"failed","msgId":"m-weird","reason":"bad_state"}'
                exit 0
            """.trimIndent(),
        )
        val runner = DefaultIrohCliRunner()
        val result = runner.send(
            binary = script.absolutePath,
            fromAgentId = "from",
            toAgentId = "to",
            body = "hi",
            paths = IrohCliPaths(),
        )
        assertTrue(
            result is IrohCliSendResult.Failed,
            "exit 0 + ok:false must yield Failed (defensive), got: $result",
        )
    }

    @Test
    fun timeoutMapsToFailed() = runBlocking {
        // Hang forever; runner must destroy the child at the deadline.
        val tmp = kotlin.io.path.createTempDirectory("iroh-runner-test").toFile()
        val script = writeCaptureScript(
            tmp = tmp,
            name = "hang.sh",
            stdoutFile = File(tmp, "stdout.txt"),
            body = """
                #!/bin/sh
                cat > /dev/null
                sleep 60
            """.trimIndent(),
        )
        val runner = DefaultIrohCliRunner(invocationTimeoutMs = 500L)
        val result = runner.send(
            binary = script.absolutePath,
            fromAgentId = "from",
            toAgentId = "to",
            body = "hi",
            paths = IrohCliPaths(),
        )
        assertTrue(
            result is IrohCliSendResult.Failed,
            "timeout must yield Failed, got: $result",
        )
        val failed = result as IrohCliSendResult.Failed
        assertTrue(
            failed.reason.contains("timeout"),
            "timeout must surface as Failed with a 'timeout' reason, got: ${failed.reason}",
        )
    }

    @Test
    fun nonExecutableBinaryMapsToFailed() = runBlocking {
        // Point at a path that doesn't exist; the spawn itself fails.
        val runner = DefaultIrohCliRunner()
        val result = runner.send(
            binary = "/nonexistent/meridian-${System.nanoTime()}",
            fromAgentId = "from",
            toAgentId = "to",
            body = "hi",
            paths = IrohCliPaths(),
        )
        assertTrue(
            result is IrohCliSendResult.Failed,
            "missing binary must yield Failed, got: $result",
        )
        val failed = result as IrohCliSendResult.Failed
        assertTrue(
            failed.reason.startsWith("cli_invocation_failed"),
            "spawn failure must surface as cli_invocation_failed, got: ${failed.reason}",
        )
    }

    /**
     * The runner must NEVER invoke `bash -c "..."` or any other shell — the
     * argv tokens go directly to the OS exec layer. This test asserts that
     * via the captured argv (we read $1, $2, ... in the script).
     */
    @Test
    fun argvIsPassedAsDiscreteTokens() = runBlocking {
        val tmp = kotlin.io.path.createTempDirectory("iroh-runner-test").toFile()
        val argvFile = File(tmp, "argv.txt")
        val argvPath = argvFile.absolutePath
        // Use raw triple-quoted strings to avoid Kotlin string interpolation
        // mangling the shell positional parameter expansions ($1..$9, $@).
        // The script-side placeholder @ARGV_PATH@ gets rewritten to the
        // per-test tmpdir path by [writeCaptureScript].
        val script = writeCaptureScript(
            tmp = tmp,
            name = "argv.sh",
            stdoutFile = File(tmp, "stdout.txt"),
            extraPlaceholders = mapOf("ARGV_PATH" to argvPath),
            body = """
                #!/bin/sh
                cat > /dev/null
                printf '%s\n' "${'$'}1" "${'$'}2" "${'$'}3" "${'$'}4" "${'$'}5" "${'$'}6" "${'$'}7" "${'$'}8" "${'$'}9" >> "@ARGV_PATH@"
                shift 9
                for a in "${'$'}@"; do printf '%s\n' "${'$'}a" >> "@ARGV_PATH@"; done
                printf '{"ok":true,"delivered":true,"msgId":"x"}'
                exit 0
            """.trimIndent(),
        )
        val runner = DefaultIrohCliRunner()
        runner.send(
            binary = script.absolutePath,
            fromAgentId = "from-argv-test",
            toAgentId = "to-argv-test",
            body = "body content with spaces and \"quotes\"",
            paths = IrohCliPaths(identityDir = "/identities", addressStore = "/addresses.kv"),
        )
        val argv = argvFile.readText().lines().filter { it.isNotEmpty() }
        // Expected argv: agent-message, send, --from, from-argv-test,
        // --to, to-argv-test, --msg-id, msg-<uuid>, --body-file, -,
        // --identity-dir, /identities, --address-store, /addresses.kv.
        assertEquals("agent-message", argv[0])
        assertEquals("send", argv[1])
        assertEquals("--from", argv[2])
        assertEquals("from-argv-test", argv[3])
        assertEquals("--to", argv[4])
        assertEquals("to-argv-test", argv[5])
        assertEquals("--msg-id", argv[6])
        assertTrue(
            argv[7].startsWith("msg-"),
            "8th token must be a generated msg-id, got: ${argv[7]}",
        )
        assertEquals("--body-file", argv[8])
        assertEquals("-", argv[9], "body MUST be stdin ('-'), never inline")
        assertEquals("--identity-dir", argv[10])
        assertEquals("/identities", argv[11])
        assertEquals("--address-store", argv[12])
        assertEquals("/addresses.kv", argv[13])
    }

    // === helpers ===

    /**
     * Write a script under [tmp] whose placeholders (e.g. `@STDOUT_PATH@`,
     * `@ARGV_PATH@`) are substituted with the matching values from
     * [stdoutFile] and [extraPlaceholders]. Chmod +x. Returns the script file.
     */
    private fun writeCaptureScript(
        tmp: File,
        name: String,
        stdoutFile: File,
        body: String,
        extraPlaceholders: Map<String, String> = emptyMap(),
    ): File {
        val script = File(tmp, name)
        val replacements = buildMap {
            put("@STDOUT_PATH@", stdoutFile.absolutePath)
            extraPlaceholders.forEach { (k, v) -> put("@$k@", v) }
        }
        val resolved = replacements.entries.fold(body) { acc, (k, v) -> acc.replace(k, v) }
        script.writeText(resolved)
        script.setExecutable(true)
        return script
    }
}
