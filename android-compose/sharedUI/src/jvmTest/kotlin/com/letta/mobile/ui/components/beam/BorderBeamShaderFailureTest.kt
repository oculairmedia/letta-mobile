package com.letta.mobile.ui.components.beam

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class BorderBeamShaderFailureTest {
    @Test
    fun compilerFailureReachesHostReporterAndKeepsFallbackAvailable() {
        val failure = IllegalArgumentException("invalid shader")
        var reported: Throwable? = null
        val previous = BeamShaderDiagnostics.reportFailure
        try {
            BeamShaderDiagnostics.reportFailure = { reported = it }
            assertNull(compileBeamShader<Any> { throw failure })
            assertSame(failure, reported)
        } finally {
            BeamShaderDiagnostics.reportFailure = previous
        }
    }
}
