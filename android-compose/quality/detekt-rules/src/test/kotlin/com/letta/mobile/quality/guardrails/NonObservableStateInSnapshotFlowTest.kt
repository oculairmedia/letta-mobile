package com.letta.mobile.quality.guardrails

import com.letta.mobile.quality.lintAt
import io.gitlab.arturbosch.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class NonObservableStateInSnapshotFlowTest {
    private val rule = NonObservableStateInSnapshotFlow()

    @Test
    fun `plain class, top-level, constructor and local vars read in snapshotFlow are reported`() {
        val code = """
            var autoFollow = true
            class Chat(
                var draftsOpen: Boolean,
                val host: Host,
            ) {
                var followEnabled = false
                fun observe(auto: Boolean) {
                    var activity = 0
                    return snapshotFlow { followEnabled to autoFollow to draftsOpen to activity }
                }
            }
        """.trimIndent()
        assertEquals(4, rule.lint(code).size)
    }

    @Test
    fun `explicit this-receiver reads of a plain var are reported`() {
        val code = """
            class Chat {
                var followEnabled = false
                fun observe() = snapshotFlow { this.followEnabled }
            }
        """.trimIndent()
        assertEquals(1, rule.lint(code).size)
    }

    @Test
    fun `each plain var read in one snapshotFlow receives its own finding`() {
        val code = """
            class Chat {
                var followEnabled = false
                var streaming: Boolean = false
                fun observe() = snapshotFlow { followEnabled to streaming }
            }
        """.trimIndent()
        assertEquals(2, rule.lint(code).size)
    }

    @Test
    fun `a local plain var shadows a delegated member and is reported`() {
        val code = """
            class Chat {
                var following by mutableStateOf(false)
                fun observe(autoscroll: Boolean): Boolean {
                    var following = autoscroll
                    return snapshotFlow { following }.first()
                }
            }
        """.trimIndent()
        assertEquals(1, rule.lint(code).size)
    }

    @Test
    fun `vars delegated to snapshot state are not reported`() {
        val code = """
            class Chat {
                var followEnabled by mutableStateOf(false)
                var count by mutableIntStateOf(0)
                var ratio by mutableFloatStateOf(1f)
                var items by mutableStateListOf()
                fun observe() = snapshotFlow { followEnabled to count to ratio to items }
            }
        """.trimIndent()
        assertEquals(0, rule.lint(code).size)
    }

    @Test
    fun `val properties, State-backed values and State-typed vars are not reported`() {
        val code = """
            class Chat {
                val followEnabled = false
                val prefs = mutableStateOf(0)
                var stateList = rememberLazyListState()
                var stream: StateFlow<Int> = MutableStateFlow(0)
                fun observe() = snapshotFlow { followEnabled to prefs.value to stateList to stream }
            }
        """.trimIndent()
        assertEquals(0, rule.lint(code).size)
    }

    @Test
    fun `plain vars read outside snapshotFlow or only as its key are not reported`() {
        val code = """
            class Chat(
                var conversationId: String,
            ) {
                var followEnabled = false
                fun observe() = flowOf(followEnabled)
                fun keyed() = snapshotFlow(conversationId) { 1 }
            }
        """.trimIndent()
        assertEquals(0, rule.lint(code).size)
    }

    @Test
    fun `qualified reads through other receivers are not resolved`() {
        val code = """
            class Host {
                var pollNeeded = false
            }
            class Mascot(private val host: Host) {
                var pollNeeded = false
                fun observe() = snapshotFlow { host.pollNeeded }
            }
        """.trimIndent()
        assertEquals(0, rule.lint(code).size)
    }

    @Test
    fun `a read before a later same-named local still binds the member scope`() {
        val code = """
            class Chat {
                var following by mutableStateOf(false)
                fun observe(): Flow<Boolean> {
                    val flow = snapshotFlow { following }
                    var following = false
                    following = true
                    return flow
                }
            }
        """.trimIndent()
        assertEquals(0, rule.lint(code).size)
    }

    @Test
    fun `test sources are skipped`(@TempDir root: File) {
        val code = """
            var missed = 0
            fun observe() = snapshotFlow { missed }
        """.trimIndent()
        assertEquals(0, rule.lintAt(root, "sharedUI/src/commonTest/kotlin/Observe.kt", code).size)
        assertEquals(0, rule.lintAt(root, "sharedUI/src/jvmTest/kotlin/Observe.kt", code).size)
        assertEquals(1, rule.lintAt(root, "sharedUI/src/commonMain/kotlin/Observe.kt", code).size)
    }
}
