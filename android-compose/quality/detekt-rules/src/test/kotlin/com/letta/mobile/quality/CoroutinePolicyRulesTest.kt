package com.letta.mobile.quality

import io.gitlab.arturbosch.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CoroutinePolicyRulesTest {
    @Test
    fun `runBlocking in production code is reported`() {
        val code = """
            class Loader { fun load(): String = runBlocking { fetch() } }
            fun warm() { kotlinx.coroutines.runBlocking { fetch() } }
        """.trimIndent()
        assertEquals(2, NoRunBlockingInProduction().lint(code).size)
    }

    @Test
    fun `runBlocking in main entry points and tests passes`(@TempDir root: File) {
        val main = """
            fun main() = runBlocking { serve() }
            object Cli { @JvmStatic fun main(args: Array<String>) { runBlocking { serve() } } }
        """.trimIndent()
        val test = """class LoaderTest { fun loads() = runBlocking { check(load()) } }"""
        assertEquals(0, NoRunBlockingInProduction().lint(main).size)
        assertEquals(0, NoRunBlockingInProduction().lintAt(root, "app/src/test/kotlin/LoaderTest.kt", test).size)
    }

    @Test
    fun `unbounded channels and shared flows are reported`() {
        val code = """
            val a = Channel<Int>(Channel.UNLIMITED)
            val b = Channel<Int>(capacity = UNLIMITED)
            val c = events.buffer(Channel.UNLIMITED)
            val d = MutableSharedFlow<Int>(extraBufferCapacity = Int.MAX_VALUE)
            val e = MutableSharedFlow<Int>(replay = Int.MAX_VALUE)
            fun CoroutineScope.f() = produce<Int>(capacity = Channel.UNLIMITED) { send(1) }
        """.trimIndent()
        assertEquals(6, UnboundedBufferNeedsPolicy().lint(code).size)
    }

    @Test
    fun `bounded buffers and justified unbounded ones pass`() {
        val code = """
            val a = Channel<Int>(64, BufferOverflow.DROP_OLDEST)
            val b = Channel<Int>(Channel.CONFLATED)
            val c = MutableSharedFlow<Int>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            val d = Channel<Int>(Channel.UNLIMITED) // overflow: one element per user tap, drained immediately
            // overflow: the producer is bounded by the page size
            val e = events.buffer(Channel.UNLIMITED)
            val f = Channel<Int>()
        """.trimIndent()
        assertEquals(0, UnboundedBufferNeedsPolicy().lint(code).size)
    }

    @Test
    fun `unbounded buffers in tests pass`(@TempDir root: File) {
        val code = "val a = Channel<Int>(Channel.UNLIMITED)\n"
        assertEquals(0, UnboundedBufferNeedsPolicy().lintAt(root, "sharedLogic/src/commonTest/kotlin/A.kt", code).size)
    }
}
