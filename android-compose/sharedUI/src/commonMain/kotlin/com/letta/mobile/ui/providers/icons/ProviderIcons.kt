package com.letta.mobile.ui.providers.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A provider brand the app carries a logo for. [tokens] are the names a
 * provider id, type or display name is matched on; entries are checked in order, so a more specific
 * brand (Azure OpenAI, Vertex) sits before the one its name also contains (OpenAI, Google).
 *
 * The logos are trademarks of their owners, shown nominatively to identify each provider.
 */
enum class ProviderBrand(internal val tokens: Set<String>, private val load: () -> ImageVector) {
    AZURE(setOf("azure"), { AzureLogo }),
    VERTEX(setOf("vertex", "vertexai"), { VertexAiLogo }),
    BEDROCK(setOf("bedrock", "amazon", "aws"), { BedrockLogo }),
    GITHUB_COPILOT(setOf("copilot", "githubcopilot"), { GitHubCopilotLogo }),
    CLOUDFLARE(setOf("cloudflare"), { CloudflareLogo }),
    VERCEL(setOf("vercel"), { VercelLogo }),
    OPENROUTER(setOf("openrouter"), { OpenRouterLogo }),
    OPENAI(setOf("openai", "chatgpt", "codex"), { OpenAiLogo }),
    ANTHROPIC(setOf("anthropic", "claude"), { AnthropicLogo }),
    GEMINI(setOf("google", "gemini"), { GeminiLogo }),
    LM_STUDIO(setOf("lmstudio"), { LmStudioLogo }),
    OLLAMA(setOf("ollama"), { OllamaLogo }),
    MISTRAL(setOf("mistral"), { MistralLogo }),
    GROQ(setOf("groq"), { GroqLogo }),
    CEREBRAS(setOf("cerebras"), { CerebrasLogo }),
    DEEPSEEK(setOf("deepseek"), { DeepSeekLogo }),
    XAI(setOf("xai", "grok"), { XaiLogo }),
    TOGETHER(setOf("together"), { TogetherLogo }),
    FIREWORKS(setOf("fireworks"), { FireworksLogo }),
    BASETEN(setOf("baseten"), { BasetenLogo }),
    PERPLEXITY(setOf("perplexity", "pplx"), { PerplexityLogo }),
    COHERE(setOf("cohere"), { CohereLogo }),
    HUGGING_FACE(setOf("huggingface", "hf"), { HuggingFaceLogo }),
    NVIDIA(setOf("nvidia", "nim"), { NvidiaLogo }),
    SAMBANOVA(setOf("sambanova"), { SambaNovaLogo }),
    ZAI(setOf("zai", "zhipu", "glm", "bigmodel"), { ZaiLogo }),
    KIMI(setOf("kimi"), { KimiLogo }),
    MOONSHOT(setOf("moonshot", "moonshotai"), { MoonshotLogo }),
    QWEN(setOf("qwen", "dashscope", "bailian", "alibaba"), { QwenLogo }),
    MINIMAX(setOf("minimax"), { MiniMaxLogo }),
    ANT_LING(setOf("ling", "antling", "bailing"), { AntGroupLogo }),
    ;

    /** The monochrome logo, tinted by whoever draws it. */
    val logo: ImageVector get() = load()
}

/**
 * The provider logo registry. Ids are matched loosely (`lc-` prefix dropped, case and `-`/`_`/`.`/space
 * separators ignored) against [ProviderBrand.tokens]: a short token must be a whole segment of the id
 * (`xai`, `hf`), a longer one may sit inside it (`fireworks-ai`, `mistralai`).
 *
 * Documented fallback: anything else — generic endpoints (`openai-compatible`, `vllm`, `sglang`),
 * brands without a lobe logo here (`opencode`, `xiaomi`) or a self-named alias — returns null, and
 * [ProviderIcon] draws the provider's initial instead.
 */
object ProviderIcons {
    private val separators = Regex("[-_./()\\s]+")

    /** Segments that make an id a generic endpoint rather than the brand it is compatible with. */
    private val genericSegments = setOf("compatible", "custom")

    /** Tokens at least this long also match inside a compacted id. */
    private const val SUBSTRING_TOKEN_LENGTH = 5

    fun brandOf(id: String?): ProviderBrand? {
        val normalized = id?.trim()?.lowercase()?.removePrefix("lc-").orEmpty()
        val segments = normalized.split(separators).filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.any { it in genericSegments }) return null
        val compact = segments.joinToString("")
        return ProviderBrand.entries.firstOrNull { brand ->
            brand.tokens.any { token ->
                token in segments || token == compact || (token.length >= SUBSTRING_TOKEN_LENGTH && token in compact)
            }
        }
    }

    /** The logo for a provider id, type or name; null means the documented initial fallback. */
    fun forProvider(id: String?): ImageVector? = brandOf(id)?.logo

    /** The first of [candidates] (id, type, name…) that names a known brand. */
    fun brandOfAny(candidates: Iterable<String?>): ProviderBrand? = candidates.firstNotNullOfOrNull(::brandOf)
}
