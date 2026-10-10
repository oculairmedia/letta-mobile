package com.letta.mobile.data.meridian

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * letta-mobile-jna0o.8, extending the jna0o.1 harness (CanvasToolTokenBudgetTest): what each
 * offer costs on every LLM step, serialised exactly as `runtime_start.external_tools` sends it
 * (tokens ~ chars / 3.5). Native is the Iroh host's full set (canvas incl. preview, a2a, a plugin
 * action); meta is the one `meridian` tool, which must stay within ~100 tokens.
 */
class MeridianToolTokenBudgetTest {
    private val wire = Json { encodeDefaults = true }

    private fun chars(registry: ExternalToolRegistry): Int =
        registry.advertisedToolsCommandGroups().orEmpty().flatMap { it.tools }.sumOf { wire.encodeToString(it).length }

    private fun tokens(chars: Int): Int = (chars / CHARS_PER_TOKEN).toInt()

    @Test
    fun theMetaToolCostsAboutOneHundredTokensAgainstTheNativeSet() {
        val native = chars(MeridianTestHost().registry)
        val meta = chars(MeridianTestHost(offer = MeridianToolOffer.of(AgentToolsModes.all(AgentToolsMode.META))).registry)
        println("agent-tools-mode  chars  ~tokens")
        println("native            ${native.toString().padStart(5)}  ${tokens(native).toString().padStart(7)}")
        println("meta              ${meta.toString().padStart(5)}  ${tokens(meta).toString().padStart(7)}")
        assertTrue(tokens(meta) <= META_BUDGET_TOKENS, "meta is ${tokens(meta)} tokens ($meta chars), over $META_BUDGET_TOKENS")
        assertTrue(tokens(native) >= NATIVE_FLOOR_TOKENS, "native baseline ${tokens(native)} tokens: the host set shrank, re-measure")
        assertTrue(meta * SAVING_FACTOR < native, "meta ($meta) should be a small fraction of native ($native)")
    }

    private companion object {
        const val CHARS_PER_TOKEN = 3.5
        const val META_BUDGET_TOKENS = 100
        const val NATIVE_FLOOR_TOKENS = 4_500
        const val SAVING_FACTOR = 40
    }
}
