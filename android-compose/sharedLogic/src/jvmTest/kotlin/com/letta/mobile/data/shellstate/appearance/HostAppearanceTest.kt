package com.letta.mobile.data.shellstate.appearance

import com.letta.mobile.data.clientstore.ShellStateCacheStore
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.shellstate.ShellStateActor
import com.letta.mobile.data.shellstate.ShellStateEntry
import com.letta.mobile.data.shellstate.ShellStateSnapshot
import com.letta.mobile.data.shellstate.shared.ShellStateEditOutcome
import com.letta.mobile.data.shellstate.shared.ShellStateLink
import com.letta.mobile.data.shellstate.shared.ShellStateReply
import com.letta.mobile.data.shellstate.shared.SharedShellState
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Appearance on the host's shell state document, over the production host routes and state file
 * ([RouterShellStateHost]): what two paired devices share, what an offline device may do, how the old
 * device-local choices migrate, and what never reaches the document.
 */
class HostAppearanceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stop() = scope.cancel()

    @Test
    fun twoDevicesOverOneHostSeeTheSameAppearanceAndEachOthersChanges() = runBlocking {
        val host = RouterShellStateHost()
        val phone = following(host.device("phone"))
        val desktop = following(host.device("desktop"))

        assertEquals(ShellStateEditOutcome.APPLIED, phone.appearance.setTheme(AppTheme.DARK))
        assertEquals(AppTheme.DARK, within { desktop.appearance.values.first { it.theme == AppTheme.DARK } }.theme)

        assertEquals(ShellStateEditOutcome.APPLIED, desktop.appearance.setThemePreset(ThemePreset.OCEAN))
        assertEquals(ThemePreset.OCEAN, within { phone.appearance.values.first { it.themePreset == ThemePreset.OCEAN } }.themePreset)

        assertEquals(ShellStateEditOutcome.APPLIED, phone.appearance.setDynamicColor(false))
        val seen = within { desktop.appearance.values.first { it.dynamicColor == false } }
        assertEquals(AppearanceValues(AppTheme.DARK, ThemePreset.OCEAN, dynamicColor = false), seen)
        assertEquals(setOf(AppearanceKeys.THEME, AppearanceKeys.THEME_PRESET, AppearanceKeys.DYNAMIC_COLOR), host.entries().keys)
    }

    @Test
    fun anEditWhileTheHostIsOutOfReachIsRefusedAndNeverQueued() = runBlocking {
        val host = RouterShellStateHost()
        val link = host.device("phone")
        val phone = following(link)

        link.cut()
        assertEquals(ShellStateEditOutcome.OFFLINE, phone.appearance.setTheme(AppTheme.DARK))
        assertEquals(null, phone.appearance.values.first().theme, "the refused edit is not shown either")

        link.restore()
        within { phone.shell.state.first { it.editable } }
        delay(SETTLE_MS)
        assertTrue(host.entries().isEmpty(), "nothing was queued and replayed on reconnect")
        assertTrue(host.proposals.isEmpty())
    }

    @Test
    fun aColdStartWhileTheHostIsOutOfReachShowsTheLastDocumentButCannotEditIt() = runBlocking {
        val host = RouterShellStateHost()
        val cache = ShellStateCacheStore(InMemorySecureSettingsStore())
        cache.save(document(AppearanceKeys.THEME to JsonPrimitive("DARK"), AppearanceKeys.THEME_PRESET to JsonPrimitive("SAKURA")))
        val link = host.device("phone").also { it.cut() }
        val phone = following(link, cache)

        assertEquals(AppearanceValues(AppTheme.DARK, ThemePreset.SAKURA), phone.appearance.values.first())
        assertFalse(phone.shell.state.value.editable)
        assertEquals(ShellStateEditOutcome.OFFLINE, phone.appearance.setTheme(AppTheme.LIGHT))
        assertTrue(host.entries().isEmpty())
    }

    @Test
    fun theHostsDocumentReplacesAStaleMirrorAndTheMirrorFollowsTheHost() = runBlocking {
        val host = RouterShellStateHost()
        following(host.device("other")).appearance.setTheme(AppTheme.LIGHT)
        val store = InMemorySecureSettingsStore()
        val cache = ShellStateCacheStore(store)
        cache.save(document(AppearanceKeys.THEME to JsonPrimitive("DARK")))
        val phone = following(host.device("phone"), cache)

        assertEquals(AppTheme.LIGHT, within { phone.appearance.values.first { it.theme == AppTheme.LIGHT } }.theme)
        within { while (cache.load()?.entries?.get(AppearanceKeys.THEME)?.value != JsonPrimitive("LIGHT")) delay(POLL_MS) }
    }

    @Test
    fun migrationProposesTheDevicesChoicesOnceToAHostThatHasNone() = runBlocking {
        val host = RouterShellStateHost()
        val legacy = FakeLegacy(AppearanceValues(AppTheme.DARK, ThemePreset.OCEAN, dynamicColor = false))
        val link = host.device("phone")
        val phone = following(link, legacy = legacy)

        within { legacy.values.first { it.isEmpty } }
        assertEquals(
            listOf(listOf(AppearanceKeys.THEME, AppearanceKeys.THEME_PRESET, AppearanceKeys.DYNAMIC_COLOR)),
            host.proposals,
            "one proposal carrying all three",
        )
        assertEquals(
            mapOf<String, Any>(
                AppearanceKeys.THEME to JsonPrimitive("DARK"),
                AppearanceKeys.THEME_PRESET to JsonPrimitive("OCEAN"),
                AppearanceKeys.DYNAMIC_COLOR to JsonPrimitive(false),
            ),
            host.entries(),
        )

        link.cut()
        link.restore()
        within { phone.shell.state.first { it.editable } }
        delay(SETTLE_MS)
        assertEquals(1, host.proposals.size, "a reconnect proposes nothing more")
    }

    @Test
    fun migrationNeverOverwritesAChoiceAnotherDeviceAlreadyMade() = runBlocking {
        val host = RouterShellStateHost()
        following(host.device("desktop")).appearance.setTheme(AppTheme.LIGHT)
        host.proposals.clear()
        val legacy = FakeLegacy(AppearanceValues(AppTheme.DARK, ThemePreset.OCEAN))
        val phone = following(host.device("phone"), legacy = legacy)

        within { legacy.values.first { it.isEmpty } }
        assertEquals(listOf(listOf(AppearanceKeys.THEME_PRESET)), host.proposals, "only the preset the host lacked")
        assertEquals(JsonPrimitive("LIGHT"), host.entries().getValue(AppearanceKeys.THEME))
        assertEquals(JsonPrimitive("OCEAN"), host.entries().getValue(AppearanceKeys.THEME_PRESET))
        val shown = within { phone.appearance.values.first { it.themePreset == ThemePreset.OCEAN } }
        assertEquals(AppTheme.LIGHT, shown.theme)
    }

    @Test
    fun aChoiceAnotherDeviceMakesBetweenTheReadAndTheMigrationStillWins() = runBlocking {
        val host = RouterShellStateHost()
        val desktop = following(host.device("desktop"))
        val legacy = FakeLegacy(AppearanceValues(AppTheme.DARK, ThemePreset.OCEAN))
        val interfering = InterferingLink(host.device("phone")) { desktop.appearance.setTheme(AppTheme.LIGHT) }
        following(interfering, legacy = legacy)

        within { legacy.values.first { it.isEmpty } }
        assertEquals(JsonPrimitive("LIGHT"), host.entries().getValue(AppearanceKeys.THEME), "compare-and-set refused the stale write")
        assertEquals(JsonPrimitive("OCEAN"), host.entries().getValue(AppearanceKeys.THEME_PRESET))
    }

    @Test
    fun anUnreachableHostKeepsTheDeviceChoicesForALaterAttempt() = runBlocking {
        val host = RouterShellStateHost()
        val legacy = FakeLegacy(AppearanceValues(AppTheme.DARK))
        val link = host.device("phone").also { it.cut() }
        val phone = following(link, legacy = legacy)

        assertEquals(AppTheme.DARK, phone.appearance.values.first().theme, "shown from the device's own choice until the host answers")
        delay(SETTLE_MS)
        assertFalse(legacy.values.first().isEmpty, "not forgotten before the host holds it")
        assertTrue(host.proposals.isEmpty())

        link.restore()
        within { legacy.values.first { it.isEmpty } }
        assertEquals(JsonPrimitive("DARK"), host.entries().getValue(AppearanceKeys.THEME))
    }

    @Test
    fun noSecretAndNoKeyOutsideAppearanceEverReachesTheHostOrTheDevice() = runBlocking {
        val host = RouterShellStateHost()
        val store = RecordingStore()
        val phone = following(host.device("phone"), ShellStateCacheStore(store), FakeLegacy(AppearanceValues(AppTheme.DARK, dynamicColor = true)))
        within { phone.shell.state.first { it.editable } }

        // A chat-role device cannot write a secret, or any key outside the allowed namespace, even by hand.
        listOf("secrets.hf_token", "hugging_face_token", "appearance_x", "layout.sidebar", "voice.endpoint.apiKey").forEach { key ->
            assertEquals(ShellStateEditOutcome.REFUSED, phone.shell.set(key, JsonPrimitive("hf_secret")), key)
        }
        phone.appearance.setThemePreset(ThemePreset.SPRING)
        within { phone.appearance.values.first { it.themePreset == ThemePreset.SPRING } }
        within { while (store.getString("shell_state_mirror_v1")?.contains("SPRING") != true) delay(POLL_MS) }

        assertTrue(host.entries().keys.all { it in AppearanceKeys.all }, "the host holds only the moved keys: ${host.entries().keys}")
        val mirrored = ShellStateCacheStore(store).load()!!.entries.keys
        assertTrue(mirrored.all { it in AppearanceKeys.all }, "the device mirrors only the moved keys: $mirrored")
        assertFalse(store.writes.any { (_, value) -> "hf_secret" in value }, "no write to the device carried the secret")
        assertEquals(setOf("shell_state_mirror_v1"), store.writes.map { it.first }.toSet())
    }

    // --- harness -----------------------------------------------------------------------------------------------

    private class Following(val shell: SharedShellState, val appearance: HostAppearance)

    /** A device that follows the host; when it can reach it, returns once the document is read (edits are refused before). */
    private fun following(
        link: ShellStateLink,
        cache: ShellStateCacheStore = ShellStateCacheStore(InMemorySecureSettingsStore()),
        legacy: LegacyAppearance = LegacyAppearance.None,
    ): Following {
        val shell = SharedShellState(link, cache)
        val appearance = HostAppearance(shell, legacy)
        scope.launch { appearance.run() }
        if (link.isConnected) runBlocking { within { shell.state.first { it.editable } } }
        return Following(shell, appearance)
    }

    private suspend fun <T> within(block: suspend () -> T): T = withTimeout(TIMEOUT_MS) { block() }

    private fun document(vararg entries: Pair<String, JsonPrimitive>) = ShellStateSnapshot(
        1, 7, entries.associate { (key, value) -> key to ShellStateEntry(value, 7, ShellStateActor.User("endpoint-b"), 1) },
    )

    /** An in-memory store that remembers every write, to assert what reached the device. */
    private class RecordingStore(private val inner: InMemorySecureSettingsStore = InMemorySecureSettingsStore()) :
        com.letta.mobile.data.storage.SecureSettingsStore by inner {
        val writes = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()

        override fun putString(key: String, value: String) {
            writes += key to value
            inner.putString(key, value)
        }
    }

    private class FakeLegacy(initial: AppearanceValues) : LegacyAppearance {
        private val state = MutableStateFlow(initial)
        override val values: Flow<AppearanceValues> = state
        override suspend fun clear() {
            state.value = AppearanceValues.NONE
        }
    }

    /** Runs [before] once, just ahead of the first proposal this device sends: another device writing in between. */
    private class InterferingLink(private val inner: ShellStateLink, private val before: suspend () -> Unit) : ShellStateLink by inner {
        private var fired = false

        override suspend fun call(method: String, params: JsonObject): ShellStateReply {
            if (method == ShellStateWire.PROPOSE && !fired) {
                fired = true
                before()
            }
            return inner.call(method, params)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 400L
        const val POLL_MS = 20L
    }
}
