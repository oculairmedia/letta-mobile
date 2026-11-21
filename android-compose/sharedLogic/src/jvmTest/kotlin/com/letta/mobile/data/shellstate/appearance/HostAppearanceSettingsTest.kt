package com.letta.mobile.data.shellstate.appearance

import com.letta.mobile.data.clientstore.hostAppearanceOver
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.settings.AppearanceSettings
import com.letta.mobile.data.settings.hostAppearanceSettings
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.data.shellstate.AppearanceKeys
import com.letta.mobile.data.shellstate.HostAppearance
import com.letta.mobile.data.shellstate.channel.channelShellStateLink
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
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
        val here = client(host.transport("desktop"))
        val other = client(host.transport("browser"))

        here.section.theme!!.set(AppTheme.DARK)

        within { while (host.entries()[AppearanceKeys.THEME] != JsonPrimitive("DARK")) delay(POLL_MS) }
        assertEquals(AppTheme.DARK, within { other.section.theme!!.current.first { it == AppTheme.DARK } })
        assertEquals(AppTheme.DARK, within { here.section.theme!!.current.first { it == AppTheme.DARK } })
        assertNull(here.section.notice!!.value, "nothing to say when the host took it")
    }

    @Test
    fun aNonDefaultPresetTurnsDynamicColourOffAndTheDefaultLeavesItAsTheHostHoldsIt() = runBlocking {
        val host = RouterShellStateHost()
        val here = client(host.transport("desktop"), dynamicColorSupported = true)
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
    fun theDynamicColourRowIsLeftOutWhereThePlatformHasNone() = runBlocking {
        val host = RouterShellStateHost()

        val unsupported = client(host.transport("desktop")).section
        val supported = client(host.transport("phone"), dynamicColorSupported = true).section

        assertNull(unsupported.dynamicColor)
        assertTrue(unsupported.theme != null && unsupported.themePreset != null, "theme and preset are still offered")
        assertTrue(supported.dynamicColor != null, "dynamic colour is offered where the platform has it")
    }

    @Test
    fun anEditTheHostCannotTakeIsRefusedWithTheReasonAndTheLastValueKeepsShowing() = runBlocking {
        val host = RouterShellStateHost()
        val seed = client(host.transport("other"))
        seed.section.theme!!.set(AppTheme.LIGHT)
        within { while (host.entries()[AppearanceKeys.THEME] != JsonPrimitive("LIGHT")) delay(POLL_MS) }
        val link = host.transport("desktop")
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

    private fun client(transport: IChannelTransport, dynamicColorSupported: Boolean = false): Client {
        val appearance = hostAppearanceOver(channelShellStateLink(MutableStateFlow(transport)), InMemorySecureSettingsStore())
        scope.launch { appearance.run() }
        val section = hostAppearanceSettings(appearance, scope, dynamicColorSupported)
        return Client(appearance, section)
    }

    private suspend fun <T> within(block: suspend () -> T): T = withTimeout(TIMEOUT_MS) { block() }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 400L
        const val POLL_MS = 20L
    }
}
