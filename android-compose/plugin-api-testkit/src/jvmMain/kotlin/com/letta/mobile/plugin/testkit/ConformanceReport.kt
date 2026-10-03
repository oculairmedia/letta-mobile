package com.letta.mobile.plugin.testkit

/** The contract rules [PluginConformance] holds a plugin to. */
public enum class ConformanceRule {
    /**
     * The lifecycle: `initialize` and `activate` succeed once, in order; the host is not used for
     * work (emit, assets, reads) before `activate` nor at all after `deactivate`; `deactivate`
     * tolerates a second call; element events and settings changes do not throw; and the plugin is
     * not `Failed` right after activation.
     */
    LIFECYCLE,

    /**
     * Actions against the manifest: every declared action, given an input valid against its
     * schema, answers `Ok` or an `Error` with a code (never a throw, never `unknown_action`), and an
     * undeclared action answers `Error(unknown_action)`.
     */
    ACTION,

    /** Emits against the element kinds: declared kinds only, at their `schemaVersion`, props valid, own elements only. */
    EMIT,

    /** Capabilities: emits need `canvas:place`, assets `assets:write`, reads `canvas:read`, the network `net:connect` and a declared origin. */
    CAPABILITY,

    /** Deadlines: every call answers within its [com.letta.mobile.plugin.api.LcpMethod] deadline. */
    DEADLINE,

    /** No secret value in logs, emits, results, plugin info or URLs. */
    SECRET_LEAK,
}

/** One way the plugin broke [rule], in words a plugin author can act on. */
public data class ConformanceFinding(public val rule: ConformanceRule, public val message: String) {
    override fun toString(): String = "[$rule] $message"
}

/** What [PluginConformance.run] found: no [findings] means the plugin keeps the contract. */
public class ConformanceReport(public val findings: List<ConformanceFinding>) {
    public val passed: Boolean get() = findings.isEmpty()

    /** The rules broken, for tests that expect a particular failure. */
    public val brokenRules: Set<ConformanceRule> get() = findings.map { it.rule }.toSet()

    /** Throws an [AssertionError] listing every finding unless the plugin passed. */
    public fun assertPassed() {
        if (!passed) throw AssertionError("The plugin breaks the LCP v1 contract:\n" + findings.joinToString("\n") { "  $it" })
    }

    override fun toString(): String = if (passed) "ConformanceReport(passed)" else "ConformanceReport($findings)"
}
