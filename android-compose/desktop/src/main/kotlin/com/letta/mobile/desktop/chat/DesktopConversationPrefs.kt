package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.display.ChatDisplayPrefs
import com.letta.mobile.data.chat.runtime.ConversationListPrefs
import com.letta.mobile.data.chat.runtime.PinnedConversations
import com.letta.mobile.data.model.RecentModelsStore
import com.letta.mobile.data.model.SettingsStoreRecentModels
import com.letta.mobile.data.storage.SecureSettingsStore

/**
 * letta-mobile-bzvro.17 / .18: the client-side conversation preferences the desktop chat keeps:
 * pinned conversations and recently used models; letta-mobile-zxk2n.5 adds the list's marks
 * (settled, markers, auto-pin) and the chat's display options. Null members turn the feature off
 * (tests, demo).
 */
data class DesktopConversationPrefs(
    val pins: PinnedConversations? = null,
    val recentModels: RecentModelsStore? = null,
    val listMarks: ConversationListPrefs? = null,
    val display: ChatDisplayPrefs? = null,
) {
    companion object {
        /** Every preference, kept in the app's own settings store. */
        fun from(store: SecureSettingsStore): DesktopConversationPrefs =
            DesktopConversationPrefs(
                pins = PinnedConversations(store, key = PINNED_KEY),
                recentModels = RecentModelsStore(SettingsStoreRecentModels(store)),
                listMarks = ConversationListPrefs(store, keyPrefix = LIST_PREFIX),
                display = ChatDisplayPrefs(store, keyPrefix = DISPLAY_PREFIX),
            )

        const val PINNED_KEY = "desktop.conversations.pinned"
        const val LIST_PREFIX = "desktop.conversations.list"
        const val DISPLAY_PREFIX = "desktop.chat.display"
    }
}
