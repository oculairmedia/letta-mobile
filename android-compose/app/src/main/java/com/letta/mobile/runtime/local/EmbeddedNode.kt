package com.letta.mobile.runtime.local

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The in-process node of nodejs-mobile (letta-mobile-by4vm.110.5). `node::Start` runs once per OS
 * process, so whoever starts it first owns it until the app process dies.
 */
interface EmbeddedNode {
    /**
     * Starts node with [request] on its own thread and returns at once; [output] gets every stdout
     * and stderr line, then the exit code once `node::Start` returns. Throws
     * [IllegalStateException] when this build has no native runtime or node already ran in this
     * process.
     */
    fun launch(request: LettaCodeNodeStartRequest, output: EmbeddedNodeOutput)
}

/** Receives an [EmbeddedNode]'s output; called from native pump threads. */
interface EmbeddedNodeOutput {
    fun stdout(line: String)

    fun stderr(line: String)

    /** `node::Start` returned (or threw: -1). Every line was delivered before this. */
    fun exited(code: Int)
}

/** The headless stream-json runtime's output: stdout to its line flow, stderr to logcat. */
internal class HeadlessNodeOutput(private val lines: MutableSharedFlow<String>) : EmbeddedNodeOutput {
    override fun stdout(line: String) {
        lines.tryEmit(line)
    }

    override fun stderr(line: String) {
        Log.w(TAG, line)
    }

    override fun exited(code: Int) = Unit

    private companion object {
        private const val TAG = "LettaCodeNodeBridge"
    }
}
