package com.letta.mobile.ui.components.beam

/** Host-installed diagnostics for shader failures; install before composing any beam. */
object BeamShaderDiagnostics {
    /** Reports a compiler failure. Hosts bind this to their telemetry warning sink at startup. */
    var reportFailure: (Throwable) -> Unit = {}
}

/** Keeps compiler failures recoverable so the perimeter fallback remains available. */
internal fun <T> compileBeamShader(compile: () -> T): T? = runCatching(compile).onFailure(BeamShaderDiagnostics.reportFailure).getOrNull()
