package com.letta.mobile.data.shellstate.appearance

import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.settings.AppearanceSettings
import com.letta.mobile.data.settings.hostAppearanceSettings
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.data.shellstate.AppearanceKeys
import com.letta.mobile.data.shellstate.AppearanceValues
import com.letta.mobile.data.shellstate.HostAppearance
import com.letta.mobile.data.shellstate.channel.channelShellStateLink
import com.letta.mobile.data.shellstate.shared.SharedShellState
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Appearance settings every client draws ([HostAppearanceSettings] over the shared wiring), against the production
 * host routes and capability gate: a change made here is what the host holds and what another client sees, and one the
 * host cannot take is refused with the reason and not queued.
 */
class HostAppearanceSettingsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stop() = scope.cancel()

    @Test
    fun changingTheThemeHereChangesWhatTheHostHoldsAndWhatAnotherClientShows() = runBlocking {
        val host = RouterShellStateHost()
        val here = client(host.transport(Endpoint("desktop")))
        val other = client(host.transport(Endpoint("browser")))

        here.section.theme!!.set(AppTheme.DARK)

        within { while (host.entries()[AppearanceKeys.THEME] != JsonPrimitive("DARK")) delay(POLL_MS) }
        assertEquals(AppTheme.DARK, within { other.section.theme!!.current.first { it == AppTheme.DARK } })
        assertEquals(AppTheme.DARK, within { here.section.theme!!.current.first { it == AppTheme.DARK } })
        assertNull(here.section.notice!!.value, "nothing to say when the host took it")
    }

    @Test
    fun aNonDefaultPresetTurnsDynamicColourOffAndTheDefaultLeavesItAsTheHostHoldsIt() = runBlocking {
        val host = RouterShellStateHost()
        val here = client(host.transport(Endpoint("desktop")), dynamicColorSupported = true)
        here.section.dynamicColor!!.set(true)
        within { here.section.dynamicColor!!.current.first { it } }

        here.section.themePreset!!.set(ThemePreset.DEFAULT)
        within { while (host.entries()[AppearanceKeys.THEME_PRESET] != JsonPrimitive("DEFAULT")) delay(POLL_MS) }
        assertEquals(JsonPrimitive(true), host.entries().getValue(AppearanceKeys.DYNAMIC_COLOR), "Default keeps what the host holds")

        here.section.themePreset!!.set(ThemePreset.OCEAN)
        within { while (host.entries()[AppearanceKeys.THEME_PRESET] != JsonPrimitive("OCEAN")) delay(POLL_MS) }
        within { while (host.entries()[AppearanceKeys.DYNAMIC_COLOR] != JsonPrimitive(false)) delay(POLL_MS) }
    }

    @Test
    fun aPresetAndTheDynamicColourItTurnsOffAreOneProposalSoNoDeviceSeesHalfOfIt() = runBlocking {
        val host = RouterShellStateHost()
        val here = client(host.transport(Endpoint("desktop")), dynamicColorSupported = true)
        val watcher = client(host.transport(Endpoint("browser")))
        here.section.dynamicColor!!.set(true)
        within { while (host.entries()[AppearanceKeys.DYNAMIC_COLOR] != JsonPrimitive(true)) delay(POLL_MS) }
        host.proposals.clear()
        val seen = mutableListOf<AppearanceValues>()
        scope.launch { watcher.appearance.values.collect { seen += it } }

        here.section.themePreset!!.set(ThemePreset.OCEAN)
        within { while (host.entries()[AppearanceKeys.THEME_PRESET] != JsonPrimitive("OCEAN")) delay(POLL_MS) }

        assertEquals(listOf(listOf(AppearanceKeys.THEME_PRESET, AppearanceKeys.DYNAMIC_COLOR)), host.proposals, "one proposal, both keys")
        assertEquals(JsonPrimitive(false), host.entries().getValue(AppearanceKeys.DYNAMIC_COLOR))
        within { watcher.appearance.values.first { it.themePreset == "OCEAN" } }
        assertTrue(seen.none { it.themePreset == "OCEAN" && it.dynamicColor == true }, "never the new preset with the old dynamic colour: $seen")
    }

    @Test
    fun aRefusedPresetChangesNeitherTheHostsPresetNorItsDynamicColour() = runBlocking {
        val host = RouterShellStateHost()
        val link = host.transport(Endpoint("desktop"))
        val here = client(link, dynamicColorSupported = true)
        here.section.dynamicColor!!.set(true)
        within { while (host.entries()[AppearanceKeys.DYNAMIC_COLOR] != JsonPrimitive(true)) delay(POLL_MS) }

        link.cut()
        here.section.themePreset!!.set(ThemePreset.OCEAN)
        within { here.section.notice!!.first { it != null } }
        link.restore()
        delay(SETTLE_MS)

        assertEquals(setOf(AppearanceKeys.DYNAMIC_COLOR), host.entries().keys)
        assertEquals(JsonPrimitive(true), host.entries().getValue(AppearanceKeys.DYNAMIC_COLOR))
    }

    @Test
    fun theDynamicColourRowIsLeftOutWhereThePlatformHasNone() = runBlocking {
        val host = RouterShellStateHost()

        val unsupported = client(host.transport(Endpoint("desktop"))).section
        val supported = client(host.transport(Endpoint("phone")), dynamicColorSupported = true).section

        assertNull(unsupported.dynamicColor)
        assertTrue(unsupported.theme != null && unsupported.themePreset != null, "theme and preset are still offered")
        assertTrue(supported.dynamicColor != null, "dynamic colour is offered where the platform has it")
    }

    @Test
    fun anEditTheHostCannotTakeIsRefusedWithTheReasonAndTheLastValueKeepsShowing() = runBlocking {
        val host = RouterShellStateHost()
        val seed = client(host.transport(Endpoint("other")))
        seed.section.theme!!.set(AppTheme.LIGHT)
        within { while (host.entries()[AppearanceKeys.THEME] != JsonPrimitive("LIGHT")) delay(POLL_MS) }
        val link = host.transport(Endpoint("desktop"))
        val here = client(link)
        within { here.section.theme!!.current.first { it == AppTheme.LIGHT } }

        link.cut()
        here.section.theme!!.set(AppTheme.DARK)

        val notice = within { here.section.notice!!.first { it != null } }!!
        assertEquals(SettingsTone.Error, notice.tone)
        assertTrue("reach your host" in notice.text, notice.text)
        assertEquals(AppTheme.LIGHT, here.section.theme!!.current.value, "still what the host holds")
        link.restore()
        delay(SETTLE_MS)
        assertEquals(JsonPrimitive("LIGHT"), host.entries().getValue(AppearanceKeys.THEME), "nothing was queued")
    }

    private class Client(val appearance: HostAppearance, val section: AppearanceSettings)

    /**
     * A client of the host that is following it and has read its document, so an edit is sent at once instead of being
     * refused as "not read yet": the wait is on the client's own state, not on time.
     */
    private fun client(transport: IChannelTransport, dynamicColorSupported: Boolean = false): Client {
        val shell = SharedShellState(channelShellStateLink(MutableStateFlow(transport)))
        val appearance = SharedShellAppearance(shell)
        scope.launch { appearance.run() }
        runBlocking { within { shell.state.first { it.editable } } }
        return Client(appearance, hostAppearanceSettings(appearance, scope, dynamicColorSupported))
    }

    private suspend fun <T> within(block: suspend () -> T): T = withTimeout(TIMEOUT_MS) { block() }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 400L
        const val POLL_MS = 20L
    }
}
