package com.letta.mobile.data.runtime.host

import com.letta.mobile.data.runtime.supervisor.RuntimeCrashPolicy
import com.letta.mobile.data.runtime.supervisor.RuntimeCrashPolicyConfig
import com.letta.mobile.data.transport.appserver.AppServerDiscovery
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.future.await

/**
 * Runs the App Server as a child process of this JVM (desktop, the Iroh wrapper, appserver-cli).
 * [onSpawned] sees each child as it starts (desktop records it for orphan reaping).
 */
class JvmLocalAppServerLauncher(
    private val onSpawned: (Process) -> Unit = {},
    private val stopTimeoutMs: Long = DEFAULT_STOP_TIMEOUT_MS,
) : LocalAppServerLauncher {
    override fun start(launch: LocalAppServerLaunch, tokenFile: String, token: String): LocalAppServerProcess {
        val process = try {
            writeOwnerOnly(File(tokenFile), token)
            ProcessBuilder(launch.command).apply {
                environment().putAll(launch.environment)
                launch.workingDirectory?.let { directory(File(it)) }
            }.start()
        } catch (e: IOException) {
            throw LocalAppServerStartException("Could not start ${launch.command.firstOrNull()}: ${e.message}", null, e)
        }
        onSpawned(process)
        return JvmLocalAppServerProcess(process, stopTimeoutMs)
    }

    companion object {
        const val DEFAULT_STOP_TIMEOUT_MS: Long = 5_000L
    }
}

/**
 * A host for a JVM command-line process that owns one App Server (the wrapper's
 * `--own-app-server`, appserver-cli's `app-server-serve`). It never restarts: the first exit is
 * final, so the caller's own supervisor (systemd) decides what happens next; wait for it with
 * `health.first { it.needsForceRestart }`.
 */
fun ownedLocalAppServerHost(
    lettaCommand: List<String>,
    backendDir: String,
    scope: CoroutineScope,
    logLine: (String) -> Unit,
): LocalAppServerHost = LocalAppServerHost(
    launcher = JvmLocalAppServerLauncher(),
    launch = { LocalAppServerLaunch(command = lettaCommand, backendDir = File(backendDir).absolutePath) },
    readyz = AppServerDiscovery(HttpClient(CIO))::readyz,
    scope = scope,
    logLine = logLine,
    policy = RuntimeCrashPolicy(RuntimeCrashPolicyConfig(maxCrashesInWindow = 1)),
)

private class JvmLocalAppServerProcess(
    private val process: Process,
    private val stopTimeoutMs: Long,
) : LocalAppServerProcess {
    override val stdout: Flow<String> = process.inputStream.lines()
    override val stderr: Flow<String> = process.errorStream.lines()
    override val isAlive: Boolean get() = process.isAlive

    override suspend fun awaitExit(): Int? = process.onExit().await().exitValue()

    /** Ends the descendants with the root so no node child keeps the port. */
    override fun kill() {
        val descendants = process.descendants().toList()
        descendants.filter { it.isAlive }.forEach { it.destroy() }
        if (process.isAlive) process.destroy()
        if (process.isAlive && !process.waitFor(stopTimeoutMs, TimeUnit.MILLISECONDS)) {
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(stopTimeoutMs, TimeUnit.MILLISECONDS)
        } else {
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
        }
    }
}

/** The stream's lines until EOF; a pipe closed under the read (the process was destroyed) also ends it. */
private fun InputStream.lines(): Flow<String> = flow {
    bufferedReader().use { reader ->
        while (true) {
            val line = try {
                reader.readLine()
            } catch (_: IOException) {
                null
            } ?: break
            emit(line)
        }
    }
}.flowOn(Dispatchers.IO)

/** Writes [secret] to [file], created readable and writable by this user only where POSIX allows. */
private fun writeOwnerOnly(file: File, secret: String) {
    file.absoluteFile.parentFile?.mkdirs()
    val path = file.toPath()
    Files.deleteIfExists(path)
    if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } else {
        Files.createFile(path)
    }
    Files.writeString(path, secret)
}
