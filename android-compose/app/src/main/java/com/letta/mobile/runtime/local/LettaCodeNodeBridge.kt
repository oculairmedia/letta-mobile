package com.letta.mobile.runtime.local

import android.util.Log
import ca.oculair.meridian.BuildConfig
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.thread

data class LettaCodeNodeStartRequest(
    val arguments: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: File,
)

interface LettaCodeNodeBridge {
    val outputLines: Flow<String>

    suspend fun start(request: LettaCodeNodeStartRequest): Result<Unit>

    suspend fun writeLine(line: String): Result<Unit>

    suspend fun stop(): Result<Unit>
}

@Singleton
class NativeLettaCodeNodeBridge @Inject constructor() : LettaCodeNodeBridge, EmbeddedNode {
    private val startMutex = Mutex()
    private val _outputLines = MutableSharedFlow<String>(extraBufferCapacity = OUTPUT_BUFFER_CAPACITY)
    override val outputLines: Flow<String> = _outputLines

    @Volatile private var started = false
    @Volatile private var startFailure: Throwable? = null

    private val loadFailure: Throwable? = if (BuildConfig.EMBEDDED_LETTACODE_NATIVE_ENABLED) {
        runCatching { System.loadLibrary("lettacode_node_bridge") }.exceptionOrNull()
    } else {
        null
    }

    private val headlessOutput = HeadlessNodeOutput(_outputLines)

    override suspend fun start(request: LettaCodeNodeStartRequest): Result<Unit> = startMutex.withLock {
        nativeUnavailable()?.let { return Result.failure(it) }
        startFailure?.let { return Result.failure(it) }
        if (started) return Result.success(Unit)
        // The headless path keeps its old behaviour; it only refuses a node the App Server owns.
        nodeOwner.compareAndSet(null, NodeOwner.HEADLESS)
        if (nodeOwner.get() == NodeOwner.APP_SERVER) return Result.failure(IllegalStateException(APP_SERVER_OWNS_NODE))

        activeOutput = headlessOutput
        started = true
        runNode(NodeOwner.HEADLESS, request) { result ->
            val exitCode = result
                .onFailure { error ->
                    startFailure = error
                    Log.e(TAG, "Embedded LettaCode node runtime crashed", error)
                    emitSyntheticError(error.message ?: "Embedded LettaCode node runtime crashed.")
                }
                .getOrDefault(-1)
            if (exitCode != 0 && startFailure == null) {
                val error = IllegalStateException("Embedded LettaCode node runtime exited with code $exitCode.")
                startFailure = error
                emitSyntheticError(error.message ?: "Embedded LettaCode node runtime exited.")
            }
            started = false
        }
        Result.success(Unit)
    }

    override fun launch(request: LettaCodeNodeStartRequest, output: EmbeddedNodeOutput) {
        nativeUnavailable()?.let { throw IllegalStateException(it.message, it) }
        check(nodeOwner.compareAndSet(null, NodeOwner.APP_SERVER)) { NODE_ALREADY_RAN }
        activeOutput = output
        runNode(NodeOwner.APP_SERVER, request) { result ->
            val exitCode = result
                .onFailure { error -> Log.e(TAG, "Embedded App Server node crashed", error) }
                .getOrDefault(-1)
            Log.w(TAG, "Embedded App Server node::Start returned $exitCode")
            output.exited(exitCode)
        }
    }

    /** Runs `node::Start` on a dedicated thread; [onExit] gets its exit code or what it threw. */
    private fun runNode(owner: NodeOwner, request: LettaCodeNodeStartRequest, onExit: (Result<Int>) -> Unit) {
        val args = request.arguments.toTypedArray()
        val env = request.environment.map { (key, value) -> "$key=$value" }.toTypedArray()
        val cwd = request.workingDirectory.absolutePath
        thread(name = owner.threadName, isDaemon = true) { onExit(runCatching { nativeStart(args, env, cwd) }) }
    }

    private fun nativeUnavailable(): Throwable? = when {
        !BuildConfig.EMBEDDED_LETTACODE_NATIVE_ENABLED -> IllegalStateException(
            "Embedded LettaCode native runtime is not enabled in this build. " +
                "Build with -PembedLettaCodeNative=true after adding nodejs-mobile libnode.",
        )
        else -> loadFailure
    }

    override suspend fun writeLine(line: String): Result<Unit> {
        if (!started) {
            return Result.failure(IllegalStateException("Embedded LettaCode node runtime is not started."))
        }
        return runCatching {
            val payload = if (line.endsWith("\n")) line else "$line\n"
            check(nativeWriteStdin(payload)) { "Embedded LettaCode stdin write failed." }
        }
    }

    override suspend fun stop(): Result<Unit> = runCatching {
        if (BuildConfig.EMBEDDED_LETTACODE_NATIVE_ENABLED && started) {
            nativeStop()
        }
        started = false
    }

    private fun emitSyntheticError(message: String) {
        _outputLines.tryEmit("""{"type":"error","message":${message.jsonString()},"stop_reason":"error"}""")
    }

    private external fun nativeStart(
        arguments: Array<String>,
        environment: Array<String>,
        workingDirectory: String,
    ): Int

    private external fun nativeWriteStdin(line: String): Boolean

    private external fun nativeStop(): Boolean

    private enum class NodeOwner(val threadName: String) {
        HEADLESS("letta-code-node"),
        APP_SERVER("letta-app-server-node"),
    }

    private companion object {
        private const val TAG = "LettaCodeNodeBridge"
        private const val OUTPUT_BUFFER_CAPACITY = 256
        private const val NODE_ALREADY_RAN =
            "The on-device node already ran in this app process and cannot start again. Restart the app."
        private const val APP_SERVER_OWNS_NODE =
            "The on-device App Server owns node in this app process. Restart the app to use the headless runtime."

        /** Process-wide, like node itself: the first path to start node keeps it. */
        private val nodeOwner = AtomicReference<NodeOwner?>(null)

        @Volatile private var activeOutput: EmbeddedNodeOutput? = null

        @JvmStatic
        fun onNativeStdoutLine(line: String) {
            activeOutput?.stdout(line)
        }

        @JvmStatic
        fun onNativeStderrLine(line: String) {
            activeOutput?.stderr(line) ?: Log.w(TAG, line)
        }
    }
}

private fun String.jsonString(): String = buildString {
    append('"')
    for (char in this@jsonString) {
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
    append('"')
}
