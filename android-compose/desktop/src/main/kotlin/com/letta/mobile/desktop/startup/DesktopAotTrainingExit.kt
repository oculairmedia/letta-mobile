package com.letta.mobile.desktop.startup

import dev.nucleusframework.aot.runtime.AotRuntime
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.WindowEvent
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Packaging builds the JVM's AOT cache by launching the packaged app once in training mode. In
 * that run the app opens its window, lets the rest of startup settle for [SETTLE_MS], and exits
 * normally, which is when the JVM writes the cache. Every other launch is untouched: [install]
 * does nothing unless this is a training run.
 */
internal class DesktopAotTrainingExit(
    private val training: Boolean = AotRuntime.isTraining(),
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit = Companion::scheduleOnDaemon,
    private val exit: () -> Unit = { exitProcess(0) },
) : java.awt.event.AWTEventListener {
    private val armed = AtomicBoolean(false)

    fun install() {
        if (training) Toolkit.getDefaultToolkit().addAWTEventListener(this, AWTEvent.WINDOW_EVENT_MASK)
    }

    override fun eventDispatched(event: AWTEvent) = onWindowEvent(event.id)

    /** The first window to open starts the settle time; later windows change nothing. */
    internal fun onWindowEvent(id: Int) {
        val firstWindowOfTraining = training && id == WindowEvent.WINDOW_OPENED
        if (firstWindowOfTraining && armed.compareAndSet(false, true)) schedule(SETTLE_MS, exit)
    }

    companion object {
        /** How long the training run keeps going after its first window opens, so startup's loads finish too. */
        const val SETTLE_MS: Long = 15_000L

        private fun scheduleOnDaemon(delayMs: Long, action: () -> Unit) {
            Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "aot-training-exit").apply { isDaemon = true } }
                .schedule(action, delayMs, TimeUnit.MILLISECONDS)
        }
    }
}
