package com.letta.mobile.runtime.local

import com.letta.mobile.data.runtime.host.LocalAppServerLaunch
import com.letta.mobile.data.runtime.host.LocalAppServerLauncher
import com.letta.mobile.data.runtime.host.LocalAppServerProcess
import com.letta.mobile.data.runtime.host.LocalAppServerStartException
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow

/**
 * Runs the local App Server on the app's in-process libnode (letta-mobile-by4vm.110.5): the
 * Android launcher of the shared [com.letta.mobile.data.runtime.host.LocalAppServerHost].
 *
 * Node runs inside the app process (decision D3) and `node::Start` runs once per process, so:
 * - [start] works once per app process ([singleLaunch]); a second call throws, and the host
 *   reports it as "restart the app".
 * - [LocalAppServerProcess.kill] cannot stop node. It detaches: the process reads as ended, its
 *   output streams complete, and the node thread keeps running until the app process dies.
 * - No stdin: the App Server is driven over its loopback WebSocket only.
 */
class LibnodeAppServerLauncher @Inject constructor(
    private val node: EmbeddedNode,
) : LocalAppServerLauncher {
    override val singleLaunch: Boolean = true

    override fun start(launch: LocalAppServerLaunch, tokenFile: String, token: String): LocalAppServerProcess {
        try {
            writeOwnerOnly(File(tokenFile), token)
        } catch (e: IOException) {
            throw LocalAppServerStartException("Could not write the App Server token: ${e.message}", null, e)
        }
        val process = LibnodeAppServerProcess()
        node.launch(
            LettaCodeNodeStartRequest(
                arguments = launch.command,
                environment = launch.environment,
                workingDirectory = File(launch.workingDirectory ?: launch.backendDir),
            ),
            output = process,
        )
        return process
    }
}

/**
 * One in-process App Server. Lines are buffered until collected, so the listen line is never lost
 * to a late collector; both streams complete when node ends or the process is detached.
 */
private class LibnodeAppServerProcess : LocalAppServerProcess, EmbeddedNodeOutput {
    private val stdoutLines = Channel<String>(Channel.UNLIMITED)
    private val stderrLines = Channel<String>(Channel.UNLIMITED)
    private val exit = CompletableDeferred<Int?>()

    override val stdout: Flow<String> = stdoutLines.consumeAsFlow()
    override val stderr: Flow<String> = stderrLines.consumeAsFlow()
    override val isAlive: Boolean get() = !exit.isCompleted

    override suspend fun awaitExit(): Int? = exit.await()

    /** Detaches (see [LibnodeAppServerLauncher]): node itself cannot be stopped in-process. */
    override fun kill() = end(null)

    override fun stdout(line: String) {
        stdoutLines.trySend(line)
    }

    override fun stderr(line: String) {
        stderrLines.trySend(line)
    }

    override fun exited(code: Int) = end(code)

    private fun end(code: Int?) {
        if (!exit.complete(code)) return
        stdoutLines.close()
        stderrLines.close()
    }
}

/** Writes [secret] to [file], readable and writable by this app's user only. */
private fun writeOwnerOnly(file: File, secret: String) {
    file.absoluteFile.parentFile?.mkdirs()
    file.delete()
    if (!file.createNewFile()) throw IOException("cannot create ${file.absolutePath}")
    file.setReadable(false, false)
    file.setWritable(false, false)
    file.setReadable(true, true)
    file.setWritable(true, true)
    file.writeText(secret)
}
