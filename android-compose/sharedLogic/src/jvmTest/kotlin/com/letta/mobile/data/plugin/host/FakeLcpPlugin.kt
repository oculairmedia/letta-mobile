package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.plugin.wire.InitializeResult
import com.letta.mobile.data.plugin.wire.LcpByteSink
import com.letta.mobile.data.plugin.wire.LcpCalls
import com.letta.mobile.data.plugin.wire.LcpEmpty
import com.letta.mobile.data.plugin.wire.LcpHandshake
import com.letta.mobile.data.plugin.wire.LcpPluginSession
import com.letta.mobile.data.plugin.wire.LcpWire
import com.letta.mobile.data.plugin.wire.NdjsonLcpTransport
import com.letta.mobile.data.plugin.wire.serve
import com.letta.mobile.plugin.api.ActionCall
import com.letta.mobile.plugin.api.ActionResult
import com.letta.mobile.plugin.api.PluginHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.system.exitProcess

/**
 * A `process` plugin for the driver tests, run as `java -cp <test classpath> ...FakeLcpPlugin --mode=<mode>`
 * on [LcpPluginSession] over its own stdio. Modes: `normal`; `crash` (exits 3 just after
 * activating); `ignore-term` (ignores SIGTERM and outlives its stdin); `hang-health` (never answers
 * `plugin.health`). Each start appends a line to `starts.log` in the working directory.
 */
object FakeLcpPlugin {
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.firstOrNull { it.startsWith("--mode=") }?.removePrefix("--mode=") ?: "normal"
        File("starts.log").appendText("start\n")
        if (mode == "ignore-term") sun.misc.Signal.handle(sun.misc.Signal("TERM")) { }
        System.err.println("fake plugin up in $mode")
        runBlocking { serve(mode) }
        if (mode == "ignore-term") Thread.sleep(Long.MAX_VALUE)
        exitProcess(0)
    }

    private suspend fun serve(mode: String) = kotlinx.coroutines.coroutineScope {
        val stdin = FileInputStream(FileDescriptor.`in`)
        val stdout = FileOutputStream(FileDescriptor.out)
        val buffer = ByteArray(65536)
        val transport = NdjsonLcpTransport(
            source = { withContext(Dispatchers.IO) { stdin.read(buffer).let { if (it < 0) null else buffer.copyOf(it) } } },
            sink = object : LcpByteSink {
                override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
                    stdout.write(bytes)
                    stdout.flush()
                }

                override fun close() = Unit
            },
        )
        val session = LcpPluginSession(transport, this)
        session.peer.serve(LcpCalls.INITIALIZE) {
            InitializeResult(ok = true, LcpHandshake.choose(it.contractVersions, listOf(LcpWire.CONTRACT_VERSION)))
        }
        session.peer.serve(LcpCalls.ACTIVATE) { LcpEmpty.also { if (mode == "crash") crashSoon(this) } }
        session.peer.serve(LcpCalls.HEALTH) { if (mode == "hang-health") awaitCancellation() else PluginHealth.Ok }
        session.peer.serve(LcpCalls.DEACTIVATE) { LcpEmpty }
        session.peer.serve(LcpCalls.INVOKE) { invoke(it) }
        session.peer.serve(LcpCalls.ELEMENT_EVENT) { }
        session.peer.serve(LcpCalls.SETTINGS_CHANGED) { }
        session.start().join()
        session.close()
    }

    private fun crashSoon(scope: CoroutineScope) {
        scope.launch {
            delay(100)
            exitProcess(3)
        }
    }

    private suspend fun invoke(call: ActionCall): ActionResult.Ok = when (call.action) {
        "env" -> ActionResult.Ok(System.getenv().keys.sorted().joinToString(","))
        "greeting" -> ActionResult.Ok(System.getenv("GREETING").orEmpty())
        "cwd" -> ActionResult.Ok(File(".").canonicalPath)
        "slow" -> {
            delay(60_000)
            ActionResult.Ok("slow")
        }
        "leak" -> {
            System.err.println("token is ${System.getenv("API_TOKEN")}")
            ActionResult.Ok("leaked to stderr")
        }
        else -> ActionResult.Ok("ran ${call.action}")
    }
}
