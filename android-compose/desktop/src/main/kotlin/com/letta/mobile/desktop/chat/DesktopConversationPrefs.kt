package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.PinnedConversations
import com.letta.mobile.data.model.RecentModelsStore
import com.letta.mobile.data.model.SettingsStoreRecentModels
import com.letta.mobile.data.storage.SecureSettingsStore

/**
 * letta-mobile-bzvro.17 / .18: the client-side conversation preferences the desktop chat keeps:
 * pinned conversations and recently used models. Null members turn the feature off (tests, demo).
 */
data class DesktopConversationPrefs(
    val pins: PinnedConversations? = null,
    val recentModels: RecentModelsStore? = null,
) {
    companion object {
        /** Pins and recent models, both kept in the app's own settings store. */
        fun from(store: SecureSettingsStore): DesktopConversationPrefs =
            DesktopConversationPrefs(
                pins = PinnedConversations(store, key = PINNED_KEY),
                recentModels = RecentModelsStore(SettingsStoreRecentModels(store)),
            )

        const val PINNED_KEY = "desktop.conversations.pinned"
    }
}
