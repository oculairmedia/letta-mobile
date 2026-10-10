package com.letta.mobile.data.meridian

import com.letta.mobile.data.controller.extras.ExternalToolRegistry

/**
 * How an agent is offered the host's tools (letta-mobile-jna0o.9, flag `agent-tools-mode`).
 *
 * - [NATIVE]: every tool as its own external tool, as hosts always have. The default.
 * - [CLI]: no tool at all; the agent runs the `meridian` CLI from its shell, pointed at by the
 *   `meridian-canvas` skill line (the shim and skill are letta-mobile-jna0o.4/.5). Only a host with
 *   that front door may use it ([AgentToolsModePolicy.withoutCli]).
 * - [META]: the single `meridian` meta-tool; the tools are reached through it.
 */
enum class AgentToolsMode(val wire: String) {
    NATIVE("native"),
    CLI("cli"),
    META("meta"),
    ;

    companion object {
        /** The mode [value] names (case-insensitive), or null when it names none. */
        fun parse(value: String?): AgentToolsMode? = entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) }

        /** The accepted values, for help and error text. */
        val choices: String get() = entries.joinToString("|") { it.wire }
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
 * The `agent-tools-mode` flag: a host default, overridable per agent (letta-mobile-jna0o.9).
 * [isNativeEverywhere] hosts keep their registry untouched, so native stays byte-identical.
 */
data class AgentToolsModePolicy(
    val hostDefault: AgentToolsMode = AgentToolsMode.NATIVE,
    val perAgent: Map<String, AgentToolsMode> = emptyMap(),
) : AgentToolsModes {
    override fun modeFor(agentId: String?): AgentToolsMode = agentId?.let(perAgent::get) ?: hostDefault

    val isNativeEverywhere: Boolean
        get() = hostDefault == AgentToolsMode.NATIVE && perAgent.values.all { it == AgentToolsMode.NATIVE }

    /**
     * This policy for a host with no `meridian` CLI front door (desktop direct, Android embedded):
     * [AgentToolsMode.CLI] would leave its agents with no way to reach the tools, so it is native
     * there until the in-process loopback (letta-mobile-jna0o.11) lands.
     */
    fun withoutCli(): AgentToolsModePolicy = AgentToolsModePolicy(
        hostDefault.withoutCli(),
        perAgent.mapValues { it.value.withoutCli() },
    )

    private fun AgentToolsMode.withoutCli() = if (this == AgentToolsMode.CLI) AgentToolsMode.NATIVE else this

    /** [registry] with this policy's offer, or [registry] itself when every agent is native. */
    fun apply(registry: ExternalToolRegistry): ExternalToolRegistry =
        if (isNativeEverywhere) registry else registry.offering(MeridianToolOffer.of(this))

    /** The flag values, as a startup log line prints them. */
    fun describe(): String = buildString {
        append(hostDefault.wire)
        if (perAgent.isNotEmpty()) append(perAgent.entries.joinToString(", ", " (", ")") { "${it.key}=${it.value.wire}" })
    }

    companion object {
        /**
         * Reads the flag: [hostDefault] is `native|cli|meta` (blank: native) and [overrides] is
         * `agentId=mode` pairs separated by commas. A value that names no mode fails with the reason.
         */
        fun parse(hostDefault: String?, overrides: String?): Result<AgentToolsModePolicy> {
            val default = if (hostDefault.isNullOrBlank()) AgentToolsMode.NATIVE else AgentToolsMode.parse(hostDefault)
                ?: return invalid("agent-tools-mode '$hostDefault' is not one of ${AgentToolsMode.choices}")
            val perAgent = overrides.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.associate { pair ->
                val agentId = pair.substringBefore('=', missingDelimiterValue = "").trim()
                val mode = AgentToolsMode.parse(pair.substringAfter('=', missingDelimiterValue = ""))
                if (agentId.isEmpty() || mode == null) {
                    return invalid("agent-tools-mode override '$pair' is not agentId=${AgentToolsMode.choices}")
                }
                agentId to mode
            }
            return Result.success(AgentToolsModePolicy(default, perAgent))
        }

        private fun invalid(message: String): Result<AgentToolsModePolicy> = Result.failure(IllegalArgumentException(message))
    }
}
