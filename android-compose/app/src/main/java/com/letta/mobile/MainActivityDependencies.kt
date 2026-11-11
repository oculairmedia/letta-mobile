package com.letta.mobile

import com.letta.mobile.crash.CrashReporter
import com.letta.mobile.data.canvas.library.CanvasBoardSessions
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerRegistry
import com.letta.mobile.data.notify.AttentionPrefsRepository
import com.letta.mobile.data.presence.ConversationRunRegistry
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.runtime.AgentDefaultsRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.storage.SecureSettingsStore
import dagger.Lazy
import javax.inject.Inject
import dagger.hilt.android.scopes.ActivityScoped
import com.letta.mobile.ui.screens.canvas.AndroidCanvasBoards

/**
 * Constructor-injected Activity collaborators for [MainActivity] (an `@AndroidEntryPoint` activity cannot
 * use constructor injection itself). The settings / secure-store / agent graph is held behind [Lazy]:
 * building it constructs the Keystore-backed EncryptedSharedPreferences, which `AppStartupCoordinator`
 * warms on IO at process start, and eager injection made `onCreate` wait ~100 ms on that singleton's lock
 * before `setContent`. Read lazily, in first composition, the warm-up has normally finished.
 * [crashReporter] stays eager: the Application already built it.
 */
@ActivityScoped
class MainActivityDependencies @Inject constructor(
    private val lazySettingsRepository: Lazy<ISettingsRepository>,
    val crashReporter: CrashReporter,
    private val lazyAgentRepository: Lazy<IAgentRepository>,
    private val lazySecureSettingsStore: Lazy<SecureSettingsStore>,
    private val lazyConversationRunRegistry: Lazy<ConversationRunRegistry>,
    private val lazyAttentionPrefs: Lazy<AttentionPrefsRepository>,
    private val lazyAgentDefaults: Lazy<AgentDefaultsRepository>,
    private val lazyCanvasBoards: Lazy<AndroidCanvasBoards>,
    private val lazyCanvasViewers: Lazy<CanvasDocumentViewerRegistry>,
    private val lazySubagentRepository: Lazy<ISubagentRepository>,
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

    /** The window's activity-retained owner, shared with its navigation destination ViewModels. */
    val canvasOwner: AndroidCanvasBoards get() = lazyCanvasBoards.get()

    /** The current backend's table; rotation retains it and a backend identity change replaces it. */
    val canvasBoards: CanvasBoardSessions get() = canvasOwner.boards.value

    /** The same trusted notebook viewers used by canvas pages and shell note modules. */
    val canvasViewers: CanvasDocumentViewerRegistry get() = lazyCanvasViewers.get()

    /** The current session's subagent registry, where the shell's tasks modules list background tasks. */
    val subagentRepository: ISubagentRepository get() = lazySubagentRepository.get()
}
