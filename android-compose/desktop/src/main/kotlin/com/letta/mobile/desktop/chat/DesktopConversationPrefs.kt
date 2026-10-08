package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.PinnedConversations
import com.letta.mobile.data.model.LettaSettingsRecentModels
import com.letta.mobile.data.model.RecentModelsPersistence
import com.letta.mobile.data.model.RecentModelsStore
import com.letta.mobile.data.model.SettingsStoreRecentModels
import com.letta.mobile.data.storage.SecureSettingsStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * letta-mobile-bzvro.17 / .18: the client-side conversation preferences the desktop chat keeps:
 * pinned conversations and recently used models. Null members turn the feature off (tests, demo).
 */
data class DesktopConversationPrefs(
    val pins: PinnedConversations? = null,
    val recentModels: RecentModelsStore? = null,
) {
    companion object {
        /** Pins in the settings store; recent models shared with the letta-code TUI when it is installed. */
        fun from(store: SecureSettingsStore, home: Path = Path.of(System.getProperty("user.home"))): DesktopConversationPrefs =
            DesktopConversationPrefs(
                pins = PinnedConversations(store, key = PINNED_KEY),
                recentModels = RecentModelsStore(
                    DesktopRecentModelsPersistence(
                        tuiSettings = home.resolve(".letta").resolve("settings.json"),
                        fallback = SettingsStoreRecentModels(store),
                    ),
                ),
            )

        const val PINNED_KEY = "desktop.conversations.pinned"
    }
}

/**
 * letta-mobile-bzvro.18: the recent-models list in letta-code's `~/.letta/settings.json` when that
 * file exists (the TUI and the reference desktop app read the same `recentModels` key), else in the
 * app's own settings store. Writing the TUI file re-reads it first and replaces only that key, so a
 * setting the TUI saved meanwhile is kept; the write goes through a temporary file and a move.
 */
internal class DesktopRecentModelsPersistence(
    private val tuiSettings: Path,
    private val fallback: RecentModelsPersistence,
) : RecentModelsPersistence {
    override fun load(): List<String> =
        if (tuiSettings.exists()) LettaSettingsRecentModels.read(tuiSettings.readText()) else fallback.load()

    override fun save(models: List<String>) {
        if (!tuiSettings.exists()) return fallback.save(models)
        val merged = LettaSettingsRecentModels.merge(tuiSettings.readText(), models) ?: return
        val staging = Files.createTempFile(tuiSettings.parent, "settings", ".json.tmp")
        try {
            staging.writeText(merged)
            Files.move(staging, tuiSettings, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(staging)
        }
    }
}
