package com.letta.mobile.runtime.local

import android.util.Log
import ca.oculair.meridian.BuildConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

data class LettaCodeNodeStartRequest(
    val arguments: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: File,
)

/**
 * nodejs-mobile's libnode over JNI: the in-process node the on-device App Server runs on
 * (letta-mobile-by4vm.110.5). The App Server is driven over its loopback WebSocket only, so the
 * bridge only starts node and pumps its stdout/stderr lines back (letta-mobile-by4vm.110.6 deleted
 * the headless stdin protocol).
 */
@Singleton
class NativeLettaCodeNodeBridge @Inject constructor() : EmbeddedNode {
    private val loadFailure: Throwable? = if (BuildConfig.EMBEDDED_LETTACODE_NATIVE_ENABLED) {
        runCatching { System.loadLibrary("lettacode_node_bridge") }.exceptionOrNull()
    } else {
        null
    }

    override fun launch(request: LettaCodeNodeStartRequest, output: EmbeddedNodeOutput) {
        nativeUnavailable()?.let { throw IllegalStateException(it.message, it) }
        check(started.compareAndSet(false, true)) { NODE_ALREADY_RAN }
        activeOutput = output
        val args = request.arguments.toTypedArray()
        val env = request.environment.map { (key, value) -> "$key=$value" }.toTypedArray()
        val cwd = request.workingDirectory.absolutePath
        Log.i(TAG, "Embedded App Server node::Start begins (cwd=$cwd)")
        thread(name = NODE_THREAD, isDaemon = true) {
            val exitCode = runCatching { nativeStart(args, env, cwd) }
                .onFailure { error -> Log.e(TAG, "Embedded App Server node crashed", error) }
                .getOrDefault(-1)
            Log.w(TAG, "Embedded App Server node::Start returned $exitCode")
            output.exited(exitCode)
        }
    }

    private fun nativeUnavailable(): Throwable? = when {
        !BuildConfig.EMBEDDED_LETTACODE_NATIVE_ENABLED -> IllegalStateException(
            "Embedded LettaCode native runtime is not enabled in this build. " +
                "Build with -PembedLettaCodeNative=true after adding nodejs-mobile libnode.",
        )
        else -> loadFailure
    }

    private external fun nativeStart(
        arguments: Array<String>,
        environment: Array<String>,
        workingDirectory: String,
    ): Int

    private companion object {
        private const val TAG = "LettaCodeNodeBridge"
        private const val NODE_THREAD = "letta-app-server-node"
        private const val NODE_ALREADY_RAN =
            "The on-device node already ran in this app process and cannot start again. Restart the app."

        /** Process-wide, like node itself: `node::Start` runs once per process. */
        private val started = AtomicBoolean(false)

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
