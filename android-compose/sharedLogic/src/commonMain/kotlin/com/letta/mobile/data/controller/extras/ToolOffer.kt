package com.letta.mobile.data.controller.extras

/**
 * Who a tool list is advertised to: the agent whose runtime the `runtime_start` is for, or, with
 * no [agentId], the host default (a re-advertisement or allowlist that has no single agent).
 */
data class ToolAudience(val agentId: String? = null) {
    companion object {
        val HostDefault = ToolAudience()
    }
}

/**
 * Which of a registry's tools the model is offered (letta-mobile-jna0o.8): advertised in
 * `runtime_start.external_tools`, so paid for on every LLM step. Every tool stays invocable either
 * way; an offer only decides what is listed. [Native] lists them all, as hosts always have. The
 * Meridian offer lists one `meridian` meta-tool in their place, which reaches the same tools
 * through the command router.
 */
interface ToolOffer {
    /** The tools to advertise to [audience], out of [invocable]. */
    fun offered(invocable: List<ExternalTool>, audience: ToolAudience): List<ExternalTool>

    /**
     * Tools this offer brings beyond the registry's own (the meta-tool): invocable by name when
     * offered, never among [ExternalToolRegistry.invocableTools], so the command surface cannot list
     * or call itself.
     */
    val extraTools: List<ExternalTool>

    /** Every tool, listed: the behaviour of every host before on-demand tools. */
    object Native : ToolOffer {
        override fun offered(invocable: List<ExternalTool>, audience: ToolAudience): List<ExternalTool> = invocable
        override val extraTools: List<ExternalTool> = emptyList()
    }
}
