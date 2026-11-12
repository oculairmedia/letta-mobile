package com.letta.mobile.quality

import io.gitlab.arturbosch.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WallClockInSharedLogicTest {
    private val rule = WallClockInSharedLogic()

    @Test
    fun `direct system clock reads are reported`() {
        val code = """
            fun a() = System.currentTimeMillis()
            fun b() = System.nanoTime()
            fun c() = Clock.System.now()
            fun d() = kotlin.time.Clock.System.now()
            fun e() = Instant.now()
            fun f() = java.time.LocalDateTime.now()
            fun g() = Date()
        """.trimIndent()
        assertEquals(7, rule.lint(code).size)
    }

    @Test
    fun `injected clocks, their defaults and other calls pass`() {
        val code = """
            class Expiry(
                private val clock: Clock = Clock.System,
                private val nowMs: () -> Long = { System.currentTimeMillis() },
            ) {
                fun expired(at: Instant) = clock.now() > at
                fun stamp() = nowMs()
                fun fixed() = Date(1_000L)
                fun parsed() = Instant.parse("2026-01-01T00:00:00Z")
            }
        """.trimIndent()
        assertEquals(0, rule.lint(code).size)
    }

    @Test
    fun `tests may read the clock`(@TempDir root: File) {
        val code = "fun a() = System.currentTimeMillis()\n"
        assertEquals(0, rule.lintAt(root, "sharedLogic/src/jvmTest/kotlin/A.kt", code).size)
    }
}
