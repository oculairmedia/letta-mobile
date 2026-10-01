package com.letta.mobile.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.desktop.data.DesktopSharedChatPageFlagStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** System property that forces the shared chat page on for one launch. */
internal const val SHARED_CHAT_SYSTEM_PROPERTY = "letta.desktop.sharedChat"

/** Environment variable that forces the shared chat page on. */
internal const val SHARED_CHAT_ENV_VARIABLE = "LETTA_DESKTOP_SHARED_CHAT"

private val TRUTHY_FLAG_VALUES = setOf("1", "true", "yes", "on")

/** True when [raw] spells "on" (1/true/yes/on, any case, surrounding blanks ignored). */
internal fun parseSharedChatFlagValue(raw: String?): Boolean =
    raw?.trim()?.lowercase() in TRUTHY_FLAG_VALUES

/** Where the launch-time override is read from; injectable so tests never touch the real env. */
internal data class SharedChatFlagEnvironment(
    val systemProperty: (String) -> String? = System::getProperty,
    val environmentVariable: (String) -> String? = System::getenv,
) {
    /** The launch forces the page on, regardless of the persisted toggle. */
    fun forcesOn(): Boolean =
        parseSharedChatFlagValue(systemProperty(SHARED_CHAT_SYSTEM_PROPERTY)) ||
            parseSharedChatFlagValue(environmentVariable(SHARED_CHAT_ENV_VARIABLE))
}

/**
 * letta-mobile-bglj6.1: whether the Conversations destination renders the shared KMP chat page
 * (`sharedUI`'s `ChatSurface`) instead of desktop's own `ChatDetailPane`.
 *
 * On when the launch forces it (system property [SHARED_CHAT_SYSTEM_PROPERTY] or env
 * [SHARED_CHAT_ENV_VARIABLE]) or when the persisted settings toggle is on. Off by default: the old
 * page stays the default until the shared page reaches parity.
 */
internal class DesktopSharedChatPageFlag(
    private val store: DesktopSharedChatPageFlagStore = DesktopSharedChatPageFlagStore(),
    environment: SharedChatFlagEnvironment = SharedChatFlagEnvironment(),
    /** Where the toggle file is written: never the UI thread. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The launch environment forces the page on; the settings toggle cannot turn it off. */
    val forcedByEnvironment: Boolean = environment.forcesOn()

    private val persisted = MutableStateFlow(runCatching { store.load() }.getOrDefault(false))

    /** The user's saved toggle, independent of any launch override. */
    val persistedEnabled: StateFlow<Boolean> = persisted.asStateFlow()

    private val effective = MutableStateFlow(forcedByEnvironment || persisted.value)

    /** Whether the shared chat page is in use right now. */
    val enabled: StateFlow<Boolean> = effective.asStateFlow()

    /**
     * Saves the settings toggle. The choice applies at once; the file is written on [ioDispatcher].
     * A failed write keeps the choice for this session.
     */
    suspend fun setPersistedEnabled(enabled: Boolean) {
        persisted.value = enabled
        effective.value = forcedByEnvironment || enabled
        withContext(ioDispatcher) { runCatching { store.save(enabled) } }
    }

    companion object {
        /** The process-wide flag the app reads; tests construct their own. */
        val Default: DesktopSharedChatPageFlag by lazy { DesktopSharedChatPageFlag() }
    }
}

/** The flag the shell reads; overridable for tests and previews. */
internal val LocalDesktopSharedChatPageFlag = staticCompositionLocalOf { DesktopSharedChatPageFlag.Default }
