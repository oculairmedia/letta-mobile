package com.letta.mobile.desktop

import dev.nucleusframework.aot.runtime.AotRuntime
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.WindowEvent
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** How long the training run keeps going after its first window opens, so startup's loads finish too. */
internal const val AOT_TRAINING_SETTLE_MS: Long = 15_000L

/**
 * Packaging builds the JVM's AOT cache by launching the packaged app once in training mode. In
 * that run the app opens its window, lets the rest of startup settle, and exits normally, which is
 * when the JVM writes the cache. Every other launch is untouched.
 */
internal class DesktopAotTrainingExit(
    private val training: Boolean = AotRuntime.isTraining(),
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit = ::scheduleOnDaemon,
    private val exit: () -> Unit = { exitProcess(0) },
) {
    private val armed = AtomicBoolean(false)

    /** Watches for the first window when this is a training run; does nothing otherwise. */
    fun install() {
        if (!training) return
        Toolkit.getDefaultToolkit().addAWTEventListener(
            { event -> if (event.id == WindowEvent.WINDOW_OPENED) onWindowOpened() },
            AWTEvent.WINDOW_EVENT_MASK,
        )
    }

    /** The first window is up: exit once startup has settled. Later windows change nothing. */
    fun onWindowOpened() {
        if (!training || !armed.compareAndSet(false, true)) return
        schedule(AOT_TRAINING_SETTLE_MS, exit)
    }
}

private fun scheduleOnDaemon(delayMs: Long, action: () -> Unit) {
    Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "aot-training-exit").apply { isDaemon = true } }
        .schedule(action, delayMs, TimeUnit.MILLISECONDS)
}
