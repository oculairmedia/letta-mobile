package com.letta.mobile.data.controller.extras

/**
 * letta-mobile-bn008-phase2-custom-tool (1vuec): JVM/Android `actual` for
 * [defaultIrohCliRunnerOrNull]. Returns a singleton [DefaultIrohCliRunner]
 * that uses `ProcessBuilder` + stdin body piping.
 *
 * The single-instance shape (vs. constructing one per invocation) is
 * deliberate: [DefaultIrohCliRunner] holds no per-call state, so reusing
 * the same instance avoids an allocation on every `external_tool_call_request`
 * the dispatcher fans out.
 */
private val DEFAULT_RUNNER: IrohCliRunner by lazy { DefaultIrohCliRunner() }

actual fun defaultIrohCliRunnerOrNull(): IrohCliRunner? = DEFAULT_RUNNER
