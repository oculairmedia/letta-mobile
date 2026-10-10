package com.letta.mobile.data.meridian

import com.letta.mobile.data.controller.extras.ExternalTool
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ToolOffer

/**
 * How an agent is offered the host's tools (letta-mobile-jna0o.8/.9, flag `agent-tools-mode`).
 *
 * - [NATIVE]: every tool as its own external tool, as hosts always have.
 * - [META]: the single `meridian` meta-tool; the tools are reached through it.
 */
enum class AgentToolsMode(val wire: String) {
    NATIVE("native"),
    META("meta"),
    ;

    companion object {
        /** The mode [value] names (case-insensitive), or null when it names none. */
        fun parse(value: String?): AgentToolsMode? = entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) }
    }
}

/** Which [AgentToolsMode] an agent's runtime gets; a null agent means the host default. */
fun interface AgentToolsModes {
    fun modeFor(agentId: String?): AgentToolsMode

    companion object {
        fun all(mode: AgentToolsMode): AgentToolsModes = AgentToolsModes { mode }
    }
}

/**
 * The [ToolOffer] behind `agent-tools-mode` (letta-mobile-jna0o.8): per agent, either every tool
 * ([AgentToolsMode.NATIVE], byte-identical to [ToolOffer.Native]) or the `meridian` meta-tool in
 * their place ([AgentToolsMode.META]). The meta-tool routes through a [MeridianCommandRouter] on
 * the same registry, so every tool stays reachable whichever the agent is offered.
 */
class MeridianToolOffer(
    private val modes: AgentToolsModes,
    private val router: MeridianCommandRouter,
) : ToolOffer {
    private val metaTool = MeridianMetaTool { router }

    override val extraTools: List<ExternalTool> = listOf(metaTool)

    override fun offered(invocable: List<ExternalTool>, agentId: String?): List<ExternalTool> =
        when (modes.modeFor(agentId)) {
            AgentToolsMode.NATIVE -> invocable
            AgentToolsMode.META -> if (invocable.isEmpty()) emptyList() else listOf(metaTool)
        }

    companion object {
        /** The offer for a registry being built: `ExternalToolRegistry(..., offer = MeridianToolOffer.of(modes))`. */
        fun of(
            modes: AgentToolsModes,
            limits: MeridianLimits = MeridianLimits(),
            rateLimiter: MeridianRateLimiter = MeridianRateLimiter.Unlimited,
        ): (ExternalToolRegistry) -> ToolOffer = { registry ->
            MeridianToolOffer(modes, MeridianCommandRouter(registry, limits, rateLimiter))
        }
    }
}
