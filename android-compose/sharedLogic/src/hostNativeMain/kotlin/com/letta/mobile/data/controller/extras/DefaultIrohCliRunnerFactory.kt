package com.letta.mobile.data.controller.extras

/**
 * letta-mobile-bn008-phase2-custom-tool (1vuec): host-native (macOS / Linux /
 * Windows native) `actual` for [defaultIrohCliRunnerOrNull]. Returns null —
 * the native target doesn't ship the JVM/Android ProcessBuilder, and no
 * native CLI binary path is configured. The tool surface degrades to a
 * structured "no IrohCliRunner available" error.
 *
 * If a future port needs the Iroh agent-message tool on a native target
 * (e.g. a Kotlin/Native operator CLI), wire the actual implementation here.
 */
actual fun defaultIrohCliRunnerOrNull(): IrohCliRunner? = null
