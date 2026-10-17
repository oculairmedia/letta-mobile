package com.letta.mobile.data.model

import kotlinx.collections.immutable.toImmutableList

/**
 * The compact agent profile: how an agent made for a small model starts lean. It is chosen from the
 * MODEL (its catalog metadata), never from the platform, so an agent on the same small model gets the
 * same profile whether a phone, the desktop or the web created it.
 *
 * What letta-code 0.34.14 compiles for a default agent on such a model (measured on the host): an
 * 84 KB request with 20 client tool schemas (77 KB) and a ~5.8 K char system prompt that is almost
 * all the bundled skills catalog. The profile keeps what the agent record can hold (a short system
 * prompt, letta-code's own [MEMFS_DISABLED_TAG], the [TAG] marker, no attached tools); the per-run
 * parts letta-code only takes per runtime or per input (no skills catalog, no client tools) are
 * applied by [com.letta.mobile.data.transport.appserver.CompactProfileRuntimePolicy] for every agent
 * carrying [TAG].
 */
object CompactAgentProfile {
    /** Marks an agent as compact; the shared App Server client keeps its runs free of tools and skills. */
    const val TAG: String = "profile:compact"

    /** letta-code's own create-time opt-out of the memory filesystem (`MEMFS_DISABLED_TAG`). */
    const val MEMFS_DISABLED_TAG: String = "memfs:disabled"

    /** The short instructions a compact agent gets when its prompt is left blank. */
    const val SYSTEM_PROMPT: String =
        "You are a helpful assistant running on a small language model. Reply in plain, natural " +
            "language. Keep answers short: one to three sentences unless the user asks for more. " +
            "Answer what was asked, then stop. Never repeat yourself or restate the question. If you " +
            "are unsure, say so briefly instead of guessing. You have no tools: you cannot browse, " +
            "run code or read files, so do not offer to, and do not write tool calls or JSON. " +
            "Messages wrapped in <system-reminder> are context from the software; use them only " +
            "if they help, and never repeat them."

    /**
     * A window this small cannot hold letta-code's full harness (~21 K tokens of tool schemas alone),
     * so its models get the compact profile by default.
     */
    const val SMALL_CONTEXT_WINDOW: Int = 32_768

    // On-device runtimes (LiteRT-LM, MediaPipe .task) and Gemma 3n's effective-size names (E2B, E4B).
    private val OnDeviceClass = Regex("""(?i)(litert|\.task$|(^|[-_/])e[24]b([-_.]|$))""")

    // A parameter count of 3B or less in the model name ("llama-3.2-1b", "qwen2.5-0.5b-instruct").
    private val SmallParameterCount = Regex("""(?i)(^|[-_/:])(0\.\d+|[1-3](\.\d+)?)b([-_.:]|$)""")

    /** Whether [model] is small enough that its agents should start compact. */
    fun isSmallModel(model: LlmModel): Boolean {
        val window = model.contextWindow
        if (window != null && window in 1..SMALL_CONTEXT_WINDOW) return true
        return listOfNotNull(model.handle, model.id, model.name, model.model).any(::isSmallModelName)
    }

    /** Whether a model handle or id names an on-device or ≤3B model. */
    fun isSmallModelName(name: String): Boolean = OnDeviceClass.containsMatchIn(name) || SmallParameterCount.containsMatchIn(name)

    /** Whether [tags] mark a compact agent. */
    fun isCompact(tags: Collection<String>?): Boolean = tags?.contains(TAG) == true

    /** [tags] with the compact marker added or removed. */
    fun tagsWith(tags: Collection<String>?, compact: Boolean): List<String> {
        val rest = tags.orEmpty().filter { it != TAG }
        return if (compact) rest + TAG else rest
    }

    /**
     * [params] as a compact agent: the short prompt where none was written, the compact and memfs
     * opt-out tags, and no tools attached.
     */
    fun applyTo(params: AgentCreateParams): AgentCreateParams = params.copy(
        system = params.system?.takeIf { it.isNotBlank() } ?: SYSTEM_PROMPT,
        tags = (tagsWith(params.tags, compact = true) + MEMFS_DISABLED_TAG).distinct().toImmutableList(),
        tools = null,
        toolIds = null,
        includeBaseTools = false,
    )
}
