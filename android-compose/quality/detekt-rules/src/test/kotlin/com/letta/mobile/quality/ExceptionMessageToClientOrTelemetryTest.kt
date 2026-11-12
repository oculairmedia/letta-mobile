package com.letta.mobile.quality

import io.gitlab.arturbosch.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ExceptionMessageToClientOrTelemetryTest {
    private val rule = ExceptionMessageToClientOrTelemetry()

    @Test
    fun `raw exception text into admin errors and telemetry is reported`() {
        val code = """
            fun a(e: Exception) { adminError("failed: ${'$'}{e.message}") }
            fun b() {
                try { work() } catch (boom: IllegalStateException) {
                    throw AdminRpcClientError(boom.localizedMessage ?: "failed")
                }
            }
            fun c(error: Throwable) {
                Telemetry.event("Tag", "failed", "error" attr (error.message ?: error.toString()))
            }
            fun d(cause: Throwable) { Telemetry.event("Tag", "failed", attr("trace", cause.stackTraceToString())) }
            fun e(writeError: Throwable) { adminError("write: " + writeError.message?.take(200)) }
        """.trimIndent()
        assertEquals(5, rule.lint(code).size)
    }

    @Test
    fun `stable codes, redacted text, logged throwables and non throwable messages pass`() {
        val code = """
            fun a(e: Exception) {
                adminError("upstream_failed")
                adminError(redact(e.message))
            }
            fun c(error: Throwable) {
                Telemetry.event("Tag", "failed", "error" attr error::class.simpleName.orEmpty())
                Telemetry.error("Tag", "failed", error)
                log("failed: ${'$'}{error.message}")
            }
            fun d(outcome: Outcome) { adminError("context_limit_failed: ${'$'}{outcome.message}") }
        """.trimIndent()
        assertEquals(0, rule.lint(code).size)
    }

    @Test
    fun `iroh response builders and json error frames are sinks only in the iroh controller`(@TempDir root: File) {
        val code = """
            fun respond(id: String, e: Exception): String {
                send(encodeFailure(id, e.message ?: "Internal error"))
                return ${"\"\"\""}{"type":"error","message":"${'$'}{e.message}"}${"\"\"\""}
            }
        """.trimIndent()
        val iroh = "sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh/H.kt"
        assertEquals(2, rule.lintAt(root, iroh, code).size)
        assertEquals(0, rule.lintAt(root, "sharedLogic/src/jvmAndAndroid/kotlin/Other.kt", code).size)
    }

    @Test
    fun `test sources are skipped`(@TempDir root: File) {
        val code = """fun a(e: Exception) { adminError("failed: " + e.message) }"""
        assertEquals(0, rule.lintAt(root, "sharedLogic/src/jvmTest/kotlin/A.kt", code).size)
        assertEquals(1, rule.lintAt(root, "sharedLogic/src/jvmMain/kotlin/A.kt", code).size)
    }
}
