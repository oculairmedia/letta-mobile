package com.letta.mobile.desktop.plugin.view

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Where the embedded browser is: not asked for yet, being installed or started, ready, or not available here. */
internal sealed interface BrowserRuntimeState<out T> {
    data object Idle : BrowserRuntimeState<Nothing>

    /** First run: the native bundle is downloading or unpacking; [progress] is 0..1, or null when unknown. */
    data class Preparing(val stage: String, val progress: Float?) : BrowserRuntimeState<Nothing>

    data class Ready<T>(val runtime: T) : BrowserRuntimeState<T>

    /** No live views on this machine (offline on first run, an unsupported platform, turned off); [reason] is shown on the cards. */
    data class Unavailable(val reason: String) : BrowserRuntimeState<Nothing>
}

/** Reports install progress while [BrowserStarter.start] runs. */
internal fun interface BrowserProgress {
    fun report(stage: String, progress: Float?)
}

/** Starts the browser runtime once per process, blocking; throws when it cannot. */
internal fun interface BrowserStarter<T> {
    fun start(progress: BrowserProgress): T
}

/**
 * The one embedded browser runtime of the process (one shared, lazy `CefApp`), started the first
 * time a live view asks for it and never again: a failure is final for the process and every live
 * view falls back to its card with the [BrowserRuntimeState.Unavailable] reason. The start blocks
 * (the first run downloads the native bundle), so it runs on its own thread through [launcher],
 * never on the UI thread, and no view's lifecycle can cut it short.
 */
internal class BrowserRuntime<T : Any>(
    private val starter: BrowserStarter<T>,
    disabledReason: String? = null,
    private val launcher: (Runnable) -> Unit = { work -> thread(name = "letta-browser-runtime", isDaemon = true) { work.run() } },
) {
    private val started = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<BrowserRuntimeState<T>>(
        disabledReason?.let { BrowserRuntimeState.Unavailable(it) } ?: BrowserRuntimeState.Idle,
    )

    val state: StateFlow<BrowserRuntimeState<T>> = mutableState.asStateFlow()

    /** Starts the runtime unless it was started (or is disabled) already. */
    fun ensureStarted() {
        if (mutableState.value is BrowserRuntimeState.Unavailable || !started.compareAndSet(false, true)) return
        launcher { mutableState.value = startOrReason() }
    }

    /** The runtime once ready, or null when it is unavailable. */
    suspend fun await(): T? {
        ensureStarted()
        val settled = state.first { it is BrowserRuntimeState.Ready || it is BrowserRuntimeState.Unavailable }
        return (settled as? BrowserRuntimeState.Ready)?.runtime
    }

    /** The runtime when it is ready right now. */
    fun readyOrNull(): T? = (mutableState.value as? BrowserRuntimeState.Ready)?.runtime

    private fun startOrReason(): BrowserRuntimeState<T> {
        mutableState.value = BrowserRuntimeState.Preparing(STAGE_STARTING, null)
        return runCatching { starter.start { stage, progress -> mutableState.value = BrowserRuntimeState.Preparing(stage, progress) } }
            .fold(
                onSuccess = { BrowserRuntimeState.Ready(it) },
                onFailure = { BrowserRuntimeState.Unavailable(unavailableReason(it)) },
            )
    }

    companion object {
        const val STAGE_STARTING = "starting"

        /** A short, safe reason for a start that failed: the first line of the failure, else its type. */
        fun unavailableReason(thrown: Throwable): String {
            val detail = thrown.message?.lineSequence()?.firstOrNull()?.trim()?.take(MAX_REASON)?.takeIf { it.isNotEmpty() }
            return "Web view unavailable: " + (detail ?: thrown::class.simpleName ?: "unknown error")
        }

        private const val MAX_REASON = 160
    }
}
