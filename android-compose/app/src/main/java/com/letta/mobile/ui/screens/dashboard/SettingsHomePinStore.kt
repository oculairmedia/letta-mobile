package com.letta.mobile.ui.screens.dashboard

import com.letta.mobile.data.home.HomePinStore
import com.letta.mobile.data.home.HomePinnedItem
import com.letta.mobile.data.repository.api.ISettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Android's [HomePinStore]: the unified pinned-item order and pinned-agent name cache Android has
 * always kept in [ISettingsRepository], so existing pins carry over to the shared Home page as-is.
 * Shared while the Home controller follows them.
 */
internal class SettingsHomePinStore(
    private val settings: ISettingsRepository,
    private val scope: CoroutineScope,
) : HomePinStore {
    override val pinnedKeys: StateFlow<List<String>> =
        settings.getPinnedItemsOrder().stateIn(scope, SharingStarted.WhileSubscribed(), emptyList())

    override val persistedAgentNames: StateFlow<Map<String, String>> =
        settings.getPinnedAgentNames().stateIn(scope, SharingStarted.WhileSubscribed(), emptyMap())

    override fun setOrder(keys: List<String>) {
        scope.launch { settings.setPinnedItemsOrder(keys) }
    }

    override fun setPinned(key: String, pinned: Boolean) {
        scope.launch {
            val shortcut = HomePinnedItem.parseShortcutKey(key)
            val agentId = HomePinnedItem.parseAgentKey(key)
            when {
                shortcut != null && pinned -> settings.addPinnedShortcut(shortcut.name)
                shortcut != null -> settings.removePinnedShortcut(shortcut.name)
                agentId != null -> settings.setAgentPinned(agentId, pinned)
            }
        }
    }
}
