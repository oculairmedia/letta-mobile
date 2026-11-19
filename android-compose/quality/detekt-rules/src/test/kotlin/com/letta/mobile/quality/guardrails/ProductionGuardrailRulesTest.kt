package com.letta.mobile.quality.guardrails

import io.gitlab.arturbosch.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProductionGuardrailRulesTest {
    @Test
    fun `hardcoded left or right padding calculations are reported while start and end pass`() {
        val bad = """
            fun pad(insets: PaddingValues) {
                val a = insets.calculateLeftPadding(LayoutDirection.Ltr)
                val b = insets.calculateRightPadding(LayoutDirection.Rtl)
            }
        """.trimIndent()
        val good = """
            fun pad(insets: PaddingValues, dir: LayoutDirection) {
                val a = insets.calculateStartPadding(dir)
                val b = insets.calculateEndPadding(dir)
            }
        """.trimIndent()
        assertEquals(2, NoHardcodedPaddingDirection().lint(bad).size)
        assertEquals(0, NoHardcodedPaddingDirection().lint(good).size)
    }

    @Test
    fun `deprecated browser unload listeners are reported while pagehide passes`() {
        val bad = """
            fun main() {
                window.addEventListener("unload", { close() })
                window.addEventListener("beforeunload", { close() })
            }
        """.trimIndent()
        val good = """
            fun main() {
                window.addEventListener("pagehide", { close() })
                window.addEventListener("visibilitychange", { close() })
            }
        """.trimIndent()
        assertEquals(2, NoDeprecatedBrowserUnloadEvent().lint(bad).size)
        assertEquals(0, NoDeprecatedBrowserUnloadEvent().lint(good).size)
    }

    @Test
    fun `non-atomic StateFlow self copy is reported while update passes`() {
        val bad = """
            class Controller {
                fun setFilter(id: String?) {
                    stateFlow.value = stateFlow.value.copy(filter = id)
                }
            }
        """.trimIndent()
        val good = """
            class Controller {
                fun setFilter(id: String?) {
                    stateFlow.update { it.copy(filter = id) }
                    stateFlow.value = otherFlow.value.copy(filter = id)
                }
            }
        """.trimIndent()
        assertEquals(1, NoNonAtomicStateFlowSelfCopy().lint(bad).size)
        assertEquals(0, NoNonAtomicStateFlowSelfCopy().lint(good).size)
    }

    @Test
    fun `unremembered toolCall facts inside Composable is reported while remembered passes`() {
        val bad = """
            @Composable
            fun Card(toolCall: UiToolCall) {
                val args = toolCall.facts()
            }
        """.trimIndent()
        val good = """
            @Composable
            fun Card(toolCall: UiToolCall) {
                val args = remember(toolCall.arguments) { toolCall.facts() }
            }
            @Composable
            fun rememberToolLine(toolCall: UiToolCall) = parse(toolCall.facts())
            fun nonComposable(toolCall: UiToolCall) = toolCall.facts()
        """.trimIndent()
        assertEquals(1, NoUnrememberedToolFactsInComposable().lint(bad).size)
        assertEquals(0, NoUnrememberedToolFactsInComposable().lint(good).size)
    }
}
