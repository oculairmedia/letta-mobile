package com.letta.mobile.runtime.local

import android.util.Log
import com.letta.mobile.BuildConfig
import java.io.File
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
class NativeLettaCodeNodeBridge @Inject constructor() : LettaCodeNodeBridge {
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

    override suspend fun start(request: LettaCodeNodeStartRequest): Result<Unit> = startMutex.withLock {
        if (!BuildConfig.EMBEDDED_LETTACODE_NATIVE_ENABLED) {
            return Result.failure(
                IllegalStateException(
                    "Embedded LettaCode native runtime is not enabled in this build. " +
                        "Build with -PembedLettaCodeNative=true after adding nodejs-mobile libnode.",
                )
            )
        }
        loadFailure?.let { return Result.failure(it) }
        startFailure?.let { return Result.failure(it) }
        if (started) return Result.success(Unit)

        activeBridge = this
        val env = request.environment.map { (key, value) -> "$key=$value" }.toTypedArray()
        val args = request.arguments.toTypedArray()
        val cwd = request.workingDirectory.absolutePath
        started = true
        thread(name = "letta-code-node", isDaemon = true) {
            val exitCode = runCatching { nativeStart(args, env, cwd) }
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

    private companion object {
        private const val TAG = "LettaCodeNodeBridge"
        private const val OUTPUT_BUFFER_CAPACITY = 256

        @Volatile private var activeBridge: NativeLettaCodeNodeBridge? = null

        @JvmStatic
        fun onNativeStdoutLine(line: String) {
            activeBridge?._outputLines?.tryEmit(line)
        }

        @JvmStatic
        fun onNativeStderrLine(line: String) {
            Log.w(TAG, line)
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
