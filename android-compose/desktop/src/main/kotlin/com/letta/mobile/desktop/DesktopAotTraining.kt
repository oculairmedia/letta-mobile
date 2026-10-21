package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import dev.nucleusframework.aot.runtime.AotRuntime
import kotlinx.coroutines.delay

/** How long the training run keeps going after its first frame, so startup's loads finish too. */
internal const val AOT_TRAINING_SETTLE_MS: Long = 15_000L

/**
 * Packaging builds the JVM's AOT cache by launching the packaged app once in training mode. In
 * that run the app draws its first frame, lets the rest of startup settle, and exits normally,
 * which is when the JVM writes the cache. Every other launch is untouched.
 */
@Composable
internal fun DesktopAotTrainingExit(
    training: Boolean = AotRuntime.isTraining(),
    exit: () -> Unit,
) {
    if (!training) return
    LaunchedEffect(Unit) {
        withFrameNanos { }
        delay(AOT_TRAINING_SETTLE_MS)
        exit()
    }
}
