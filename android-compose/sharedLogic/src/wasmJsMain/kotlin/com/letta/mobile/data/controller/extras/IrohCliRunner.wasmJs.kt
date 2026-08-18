package com.letta.mobile.data.controller.extras

/**
 * Wasm/Browser actual for [defaultIrohCliRunnerOrNull]. Returns null —
 * browsers cannot spawn child processes. The tool surface degrades to a
 * structured "no IrohCliRunner available" error.
 */
actual fun defaultIrohCliRunnerOrNull(): IrohCliRunner? = null
