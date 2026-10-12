@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.providers.icons

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Provider logos (letta-mobile-zxk2n.35): every catalog id resolves to a logo or the documented fallback. */
class ProviderIconTest {
    /**
     * The provider ids a letta-code host lists (`provider.list` rows of 0.32/0.33: its pi-ai catalog, its
     * native provider types and subscription logins) plus the route keys model handles carry.
     */
    private val catalog = listOf(
        "amazon-bedrock", "ant-ling", "anthropic", "anthropic-oauth", "azure-openai-responses", "baseten", "cerebras",
        "cloudflare-ai-gateway", "cloudflare-workers-ai", "deepseek", "fireworks", "github-copilot", "google",
        "google-vertex", "groq", "huggingface", "kimi-coding", "minimax", "minimax-cn", "mistral", "moonshotai",
        "moonshotai-cn", "nvidia", "openai", "openai-codex", "opencode", "opencode-go", "openrouter", "qwen-token-plan",
        "qwen-token-plan-cn", "qwen-token-plan-individual", "together", "vercel-ai-gateway", "xai", "xiaomi",
        "xiaomi-token-plan-ams", "xiaomi-token-plan-cn", "xiaomi-token-plan-sgp", "zai", "zai-coding-cn",
        // Native provider types and route keys.
        "lmstudio", "ollama", "openai-compatible", "lc-openai", "lc-anthropic", "google_ai", "google_vertex", "azure",
        "bedrock", "chatgpt_oauth", "vllm", "sglang", "perplexity", "cohere", "sambanova", "letta",
    )

    /** Ids with no logo here: generic endpoints and brands lobe-icons has no mark for in this set. */
    private val documentedFallbacks = setOf(
        "opencode", "opencode-go", "xiaomi", "xiaomi-token-plan-ams", "xiaomi-token-plan-cn", "xiaomi-token-plan-sgp",
        "openai-compatible", "vllm", "sglang", "letta",
    )

    @Test
    fun everyCatalogIdHasALogoOrIsADocumentedFallback() {
        val unresolved = catalog.filter { ProviderIcons.brandOf(it) == null }
        assertEquals(documentedFallbacks, unresolved.toSet(), "ids without a logo must be the documented fallbacks")
    }

    @Test
    fun specificBrandsWinOverTheNamesTheyContain() {
        assertEquals(ProviderBrand.AZURE, ProviderIcons.brandOf("azure-openai-responses"))
        assertEquals(ProviderBrand.VERTEX, ProviderIcons.brandOf("google-vertex"))
        assertEquals(ProviderBrand.GEMINI, ProviderIcons.brandOf("google_ai"))
        assertEquals(ProviderBrand.OPENAI, ProviderIcons.brandOf("lc-openai"))
        assertEquals(ProviderBrand.OPENAI, ProviderIcons.brandOf("chatgpt_oauth"))
        assertEquals(ProviderBrand.ANTHROPIC, ProviderIcons.brandOf("Anthropic (Claude Pro/Max)"))
        assertEquals(ProviderBrand.ANT_LING, ProviderIcons.brandOf("Ant Ling"))
        assertEquals(ProviderBrand.LM_STUDIO, ProviderIcons.brandOf("LM Studio"))
        assertEquals(ProviderBrand.ZAI, ProviderIcons.brandOf("Z.AI"))
        assertEquals(ProviderBrand.GROQ, ProviderIcons.brandOf("groq"))
        assertEquals(ProviderBrand.XAI, ProviderIcons.brandOf("xAI (Grok/X subscription)"))
        assertNull(ProviderIcons.brandOf(""))
        assertNull(ProviderIcons.brandOf(null))
    }

    @Test
    fun aliasesAreTriedInOrder() {
        assertEquals(ProviderBrand.LM_STUDIO, ProviderIcons.brandOfAny(listOf("my-local", null, "lmstudio")))
        assertNull(ProviderIcons.brandOfAny(listOf("my-local", "letta")))
    }

    @Test
    fun everyLogoBuildsFromItsPathData() {
        ProviderBrand.entries.forEach { brand ->
            val logo = brand.logo
            assertTrue(logo.root.size > 0, "${brand.name} has no paths")
            assertEquals(24f, logo.viewportWidth, brand.name)
        }
    }

    @Test
    fun rendersLogosAndTheInitialFallback() = runComposeUiTest {
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Row {
                    ProviderIcon(ProviderIdentity("openai"))
                    ProviderIcon(ProviderIdentity("my-box", aliases = listOf("lmstudio")), connected = true)
                    ProviderIcon(ProviderIdentity("letta", label = "Letta"))
                }
            }
        }
        onNodeWithTag(ProviderIconTags.LOGO_PREFIX + "openai").assertIsDisplayed()
        onNodeWithTag(ProviderIconTags.LOGO_PREFIX + "my-box").assertIsDisplayed()
        onNodeWithTag(ProviderIconTags.INITIAL_PREFIX + "letta").assertIsDisplayed()
        onNodeWithText("L").assertIsDisplayed()
    }
}
