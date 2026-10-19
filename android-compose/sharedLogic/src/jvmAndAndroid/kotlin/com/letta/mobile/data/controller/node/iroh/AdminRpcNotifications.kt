package com.letta.mobile.data.controller.node.iroh

/**
 * Host-owned change publishers and turn-identity join used by admin handlers. Null hooks keep reads and writes
 * free of push side effects.
 */
class AdminRpcNotifications(
    /** See [NativeReadTiers.agentChanges]. */
    val agentChanges: AgentChangeNotifier? = null,
    /** See [NativeReadTiers.conversationChanges]. */
    val conversationChanges: ConversationChangeNotifier? = null,
    /** See [NativeReadTiers.turnIdentity]. */
    val turnIdentity: com.letta.mobile.data.runtime.TurnIdentityLedger? = null,
    /** Pushes confirmed permission-mode changes to viewers; null pushes nothing. */
    val permissionModes: PermissionModePublisher? = null,
)
