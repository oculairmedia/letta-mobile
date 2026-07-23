package com.letta.mobile.data.transport.appserver

/**
 * Retry classification for App Server commands across an ambiguous disconnect
 * (letta-mobile-lgns8.5).
 *
 * When a request is in flight and the connection drops, the client cannot know
 * whether the server received and applied it. Blindly re-sending is only safe
 * for **idempotent** operations. Non-idempotent mutations (creating a turn,
 * aborting, responding to an approval) may have already committed server-side;
 * re-sending them can duplicate a committed turn or double-apply an effect.
 *
 * This classifier is the policy the request/reconnect layer consults:
 * - [SafeRead]: idempotent — safe to re-send after reconnect.
 * - [AmbiguousMutation]: may have committed before the disconnect — must NOT be
 *   blindly re-sent. If it carries a dedup key ([dedupKey] non-null), recovery
 *   is possible via server-side idempotency (the server rejects/ignores the
 *   duplicate by key); otherwise the caller must reconcile (e.g. via sync /
 *   message.list) before deciding whether to re-issue.
 */
sealed interface AppServerCommandRetryClass {
    /** Idempotent read/attach; safe to retry after an ambiguous disconnect. */
    data object SafeRead : AppServerCommandRetryClass

    /**
     * Non-idempotent mutation. [dedupKey] is the client-supplied idempotency /
     * client_message_id that lets the server dedupe a re-send, or null if the
     * command has no such key (then the caller must reconcile before re-issuing).
     */
    data class AmbiguousMutation(val dedupKey: String?) : AppServerCommandRetryClass

    companion object {
        /**
         * AdminRpc methods whose name marks them as pure reads. Anything not
         * matching is treated as an ambiguous mutation (fail-closed).
         */
        private val READ_METHOD_PREFIXES = listOf(
            "list", "get", "context", "status", "read", "search", "fetch", "describe",
        )

        /** Classify [command] for post-disconnect retry safety. */
        fun of(command: AppServerCommand): AppServerCommandRetryClass = when (command) {
            // Idempotent: re-auth, attach-by-scope (server dedups by scope),
            // and replay/read sync are all safe to repeat.
            is AppServerCommand.Auth -> SafeRead
            is AppServerCommand.RuntimeStart -> SafeRead
            is AppServerCommand.Sync -> SafeRead

            // AdminRpc: reads are safe; everything else is an ambiguous mutation.
            is AppServerCommand.AdminRpc ->
                if (isReadMethod(command.method)) SafeRead
                else AmbiguousMutation(dedupKey = null)

            // Turn creation carries client_message_id for server-side dedup.
            is AppServerCommand.Input ->
                AmbiguousMutation(dedupKey = clientMessageId(command.payload))

            // Aborting / approval / tool-result are effectful and non-idempotent.
            is AppServerCommand.AbortMessage -> AmbiguousMutation(dedupKey = null)
            is AppServerCommand.ExternalToolCallResponse -> AmbiguousMutation(dedupKey = null)

            // Native admin operations (lgns8.7): reads replay safely; entity
            // mutations may have committed before an ambiguous disconnect.
            is AppServerCommand.AgentList -> SafeRead
            is AppServerCommand.AgentRetrieve -> SafeRead
            is AppServerCommand.ConversationList -> SafeRead
            is AppServerCommand.ConversationRetrieve -> SafeRead
            is AppServerCommand.ConversationMessagesList -> SafeRead
            is AppServerCommand.AgentCreate -> AmbiguousMutation(dedupKey = null)
            is AppServerCommand.AgentUpdate -> AmbiguousMutation(dedupKey = null)
            is AppServerCommand.AgentDelete -> AmbiguousMutation(dedupKey = null)
            is AppServerCommand.ConversationCreate -> AmbiguousMutation(dedupKey = null)
            is AppServerCommand.ConversationUpdate -> AmbiguousMutation(dedupKey = null)

            // Control capabilities (lgns8.8): model listing is a read; skill
            // enable/disable are idempotent-by-target but treated as ambiguous.
            is AppServerCommand.ListModels -> SafeRead
            is AppServerCommand.SkillEnable -> AmbiguousMutation(dedupKey = null)
            is AppServerCommand.SkillDisable -> AmbiguousMutation(dedupKey = null)

            // Cron scheduling (lgns8.8): reads replay safely; schedule
            // mutations and manual triggers are ambiguous after disconnect.
            is AppServerCommand.CronList -> SafeRead
            is AppServerCommand.CronGet -> SafeRead
            is AppServerCommand.CronRuns -> SafeRead
            is AppServerCommand.CronAdd -> AmbiguousMutation(dedupKey = null)
            is AppServerCommand.CronTrigger -> AmbiguousMutation(dedupKey = null)
            is AppServerCommand.CronUpdate -> AmbiguousMutation(dedupKey = null)
            is AppServerCommand.CronDelete -> AmbiguousMutation(dedupKey = null)
            is AppServerCommand.CronDeleteAll -> AmbiguousMutation(dedupKey = null)
        }

        /** True if this command may be re-sent verbatim after a reconnect. */
        fun isRetryableAfterAmbiguousDisconnect(command: AppServerCommand): Boolean =
            of(command) is SafeRead

        private fun isReadMethod(method: String): Boolean {
            val normalized = method.substringAfterLast('.').lowercase()
            return READ_METHOD_PREFIXES.any { normalized.startsWith(it) }
        }

        private fun clientMessageId(payload: AppServerInputPayload): String? =
            when (payload) {
                is AppServerInputPayload.CreateMessage ->
                    payload.messages.firstNotNullOfOrNull { it.clientMessageId }
                else -> null
            }
    }
}
