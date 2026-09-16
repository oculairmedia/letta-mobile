package com.letta.mobile

import com.letta.mobile.crash.CrashReporter
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.storage.SecureSettingsStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Constructor-injected Activity collaborators for [MainActivity].
 *
 * `@AndroidEntryPoint` activities cannot use constructor injection on the
 * Activity itself (letta-mobile-l2ew9.2).
 */
@Singleton
class MainActivityDependencies @Inject constructor(
    val settingsRepository: ISettingsRepository,
    val crashReporter: CrashReporter,
    /** The roster and the per-agent settings the mascot identity registry is derived from. */
    val agentRepository: IAgentRepository,
    val secureSettingsStore: SecureSettingsStore,
    val conversationRunRegistry: com.letta.mobile.data.presence.ConversationRunRegistry,
)
