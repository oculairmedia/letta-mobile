package com.letta.mobile.data.controller.node.iroh

/**
 * Explicit timeout / circuit-breaker policy for [NativeAdmin.require].
 *
 * Handlers must pass a policy — operation-name heuristics are not used.
 * [MutationAmbiguous] covers any command that may already have mutated durable
 * App Server state when the wait times out (no read-breaker trip; longer budget).
 */
enum class NativeAdminOperationPolicy {
    Read,
    MutationAmbiguous,
}

/**
 * Exhaustive catalog of native admin ops that go through [NativeAdmin.require].
 * Adding a new `require` call site without an entry here (or with the wrong
 * policy) fails [NativeAdminOperationPolicyContractTest].
 */
enum class NativeAdminOp(
    val method: String,
    val policy: NativeAdminOperationPolicy,
) {
    AgentList("agent.list", NativeAdminOperationPolicy.Read),
    AgentGet("agent.get", NativeAdminOperationPolicy.Read),
    AgentCreate("agent.create", NativeAdminOperationPolicy.MutationAmbiguous),
    AgentUpdate("agent.update", NativeAdminOperationPolicy.MutationAmbiguous),
    AgentDelete("agent.delete", NativeAdminOperationPolicy.MutationAmbiguous),

    ConversationList("conversation.list", NativeAdminOperationPolicy.Read),
    ConversationGet("conversation.get", NativeAdminOperationPolicy.Read),
    ConversationCreate("conversation.create", NativeAdminOperationPolicy.MutationAmbiguous),
    ConversationUpdate("conversation.update", NativeAdminOperationPolicy.MutationAmbiguous),
    ConversationArchive("conversation.archive", NativeAdminOperationPolicy.MutationAmbiguous),
    ConversationRestore("conversation.restore", NativeAdminOperationPolicy.MutationAmbiguous),

    MessageList("message.list", NativeAdminOperationPolicy.Read),
    MessageGet("message.get", NativeAdminOperationPolicy.Read),
    ToolReturnGet("tool_return.get", NativeAdminOperationPolicy.Read),

    ModelList("model.list", NativeAdminOperationPolicy.Read),

    SkillInstall("skill.install", NativeAdminOperationPolicy.MutationAmbiguous),
    SkillUninstall("skill.uninstall", NativeAdminOperationPolicy.MutationAmbiguous),
    ;

    companion object {
        fun byMethod(method: String): NativeAdminOp? =
            entries.firstOrNull { it.method.equals(method, ignoreCase = true) }
    }
}
