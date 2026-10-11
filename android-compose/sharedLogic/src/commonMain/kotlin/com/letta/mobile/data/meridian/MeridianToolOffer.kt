package com.letta.mobile.data.meridian

import com.letta.mobile.data.controller.extras.ExternalTool
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ToolAudience
import com.letta.mobile.data.controller.extras.ToolOffer

/**
 * The [ToolOffer] behind `agent-tools-mode` (letta-mobile-jna0o.8/.9), per agent:
 * - [AgentToolsMode.NATIVE]: every tool, byte-identical to [ToolOffer.Native];
 * - [AgentToolsMode.CLI]: nothing, since every tool is reached through the `meridian` CLI;
 * - [AgentToolsMode.META]: the `meridian` meta-tool in their place.
 *
 * The meta-tool routes through a [MeridianCommandRouter] on the same registry, so every tool stays
 * reachable whichever the agent is offered.
 */
class MeridianToolOffer(
    private val modes: AgentToolsModes,
    private val router: MeridianCommandRouter,
) : ToolOffer {
    private val metaTool = MeridianMetaTool { router }

    override val extraTools: List<ExternalTool> = listOf(metaTool)

    override fun offered(invocable: List<ExternalTool>, audience: ToolAudience): List<ExternalTool> =
        when (modes.modeFor(audience.agentId)) {
            AgentToolsMode.NATIVE -> invocable
            AgentToolsMode.CLI -> emptyList()
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
