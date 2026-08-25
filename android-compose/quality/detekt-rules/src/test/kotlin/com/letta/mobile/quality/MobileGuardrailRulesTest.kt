package com.letta.mobile.quality

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MobileGuardrailRulesTest {
    @Test
    fun `Any and aliases fail while typed boundaries pass`() {
        val bad = """
            typealias Payload = Any?
            fun send(value: Payload, values: Array<Any>, metadata: Map<String, Any?>, vararg rest: Any) = Unit
        """.trimIndent()
        val good = """
            data class Payload(val id: String) {
                override fun equals(other: Any?): Boolean = other is Payload && other.id == id
                override fun hashCode(): Int = id.hashCode()
            }
            fun send(value: Payload, values: Array<Payload>, vararg rest: Payload) = Unit
        """.trimIndent()
        assertTrue(NoAnyType().compileAndLint(bad).size >= 5)
        assertEquals(0, NoAnyType().compileAndLint(good).size)
    }

    @Test
    fun `Room star projection fails and named columns pass`() {
        val bad = """interface Dao { @Query("SELECT * FROM agents") fun agents(): List<String> }"""
        val good = """interface Dao { @Query("SELECT id, name FROM agents") fun agents(): List<String> }"""
        assertEquals(1, NoSelectStarInRoomDao().compileAndLint(bad).size)
        assertEquals(0, NoSelectStarInRoomDao().compileAndLint(good).size)
    }

    @Test
    fun `process global mutable state fails and instance ownership passes`() {
        val bad = """
            val values = mutableMapOf<String, String>()
            object Cache {
                val queue: MutableList<String> = listOf()
                var snapshot: List<String> = listOf()
            }
        """.trimIndent()
        val good = """class Cache { private val values = mutableMapOf<String, String>() }"""
        assertEquals(3, NoProcessGlobalMutableState().compileAndLint(bad).size)
        assertEquals(0, NoProcessGlobalMutableState().compileAndLint(good).size)
    }

    @Test
    fun `generic coroutine catches must propagate cancellation`() {
        val bad = """suspend fun load() { try { work() } catch (error: Exception) { log(error) } }"""
        val good = """
            suspend fun load() {
                try { work() } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    log(error)
                }
            }
        """.trimIndent()
        val goodOrderedCatch = """
            suspend fun load() {
                try { work() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { log(error) }
            }
        """.trimIndent()
        assertEquals(1, CancellationMustPropagate().compileAndLint(bad).size)
        assertEquals(0, CancellationMustPropagate().compileAndLint(good).size)
        assertEquals(0, CancellationMustPropagate().compileAndLint(goodOrderedCatch).size)
    }

    @Test
    fun `generic catches inside coroutine builders require cancellation propagation`() {
        val code = """
            fun load() = runBlocking {
                try { work() } catch (error: Throwable) { log(error) }
            }
        """.trimIndent()
        assertEquals(1, CancellationMustPropagate().compileAndLint(code).size)
    }

    @Test
    fun `detached coroutine patterns fail and injected lifecycle scope passes`() {
        val bad = """
            fun start() {
                GlobalScope.launch { work() }
                val scope = CoroutineScope(Job())
                job.invokeOnCompletion { scope.async { work() } }
            }
        """.trimIndent()
        val good = """
            class Worker(private val scope: CoroutineScope) {
                fun start() = scope.launch { work() }
            }
        """.trimIndent()
        assertTrue(NoDetachedCoroutineLifecycle().compileAndLint(bad).size >= 3)
        assertEquals(0, NoDetachedCoroutineLifecycle().compileAndLint(good).size)
    }

    @Test
    fun `eager sharing on an external scope fails`() {
        val bad = """fun stream(flow: Flow<String>, scope: CoroutineScope) =
            flow.shareIn(scope, SharingStarted.Eagerly, replay = 1)
        """.trimIndent()
        assertEquals(1, NoDetachedCoroutineLifecycle().compileAndLint(bad).size)
    }

    @Test
    fun `coroutine tests require assertions and useful performance bounds`() {
        val assertionless = """@Test fun loads() = runTest { repository.load() }"""
        val tautology = """
            @Test fun fast() = runTest {
                val elapsedMillis = measure()
                assertTrue(elapsedMillis >= 0)
            }
        """.trimIndent()
        val good = """@Test fun loads() = runTest { assertEquals("ok", repository.load()) }"""
        assertEquals(1, CoroutineTestGuardrails().compileAndLint(assertionless).size)
        assertEquals(1, CoroutineTestGuardrails().compileAndLint(tautology).size)
        assertEquals(0, CoroutineTestGuardrails().compileAndLint(good).size)
    }
}
