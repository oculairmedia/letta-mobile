package com.letta.mobile

import com.letta.mobile.crash.CrashReporter
import com.letta.mobile.data.notify.AttentionPrefsRepository
import com.letta.mobile.data.presence.ConversationRunRegistry
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.runtime.AgentDefaultsRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.storage.SecureSettingsStore
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Constructor-injected Activity collaborators for [MainActivity].
 *
 * `@AndroidEntryPoint` activities cannot use constructor injection on the
 * Activity itself (letta-mobile-l2ew9.2).
 *
 * letta-mobile-wyo3p: the settings/secure-store/agent graph is held behind
 * [Lazy]. Building it constructs the Keystore-backed EncryptedSharedPreferences,
 * which `AppStartupCoordinator` already warms on IO at process start; eager
 * injection made `MainActivity.onCreate` wait ~100 ms on that singleton's lock
 * before it could call `setContent`. Resolved lazily, the first read happens in
 * first composition, by which point the warm-up has normally finished.
 * [crashReporter] stays eager: the Application already built it.
 */
@Singleton
class MainActivityDependencies @Inject constructor(
    private val lazySettingsRepository: Lazy<ISettingsRepository>,
    val crashReporter: CrashReporter,
    private val lazyAgentRepository: Lazy<IAgentRepository>,
    private val lazySecureSettingsStore: Lazy<SecureSettingsStore>,
    private val lazyConversationRunRegistry: Lazy<ConversationRunRegistry>,
    private val lazyAttentionPrefs: Lazy<AttentionPrefsRepository>,
    private val lazyAgentDefaults: Lazy<AgentDefaultsRepository>,
) {
    val settingsRepository: ISettingsRepository get() = lazySettingsRepository.get()

    /** The roster and the per-agent settings the mascot identity registry is derived from. */
    val agentRepository: IAgentRepository get() = lazyAgentRepository.get()

    val secureSettingsStore: SecureSettingsStore get() = lazySecureSettingsStore.get()

    val conversationRunRegistry: ConversationRunRegistry get() = lazyConversationRunRegistry.get()

    /** letta-mobile-bzvro.27: per-agent notification mutes, for the drawer card's switch. */
    val attentionPrefs: AttentionPrefsRepository get() = lazyAttentionPrefs.get()

    /** letta-mobile-k5nbq.9: per-agent default cwd, the store the Iroh turn engine also reads. */
    val agentDefaults: AgentDefaultsRepository get() = lazyAgentDefaults.get()
}
