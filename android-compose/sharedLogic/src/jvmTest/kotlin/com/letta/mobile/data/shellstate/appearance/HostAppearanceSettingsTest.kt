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
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
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
    private val edits = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stop() {
        scope.cancel()
        edits.cancel()
    }

    @Test
    fun changingTheThemeHereChangesWhatTheHostHoldsAndWhatAnotherClientShows() = runBlocking<Unit> {
        val host = RouterShellStateHost()
        val here = client(host.transport(Endpoint("desktop")))
        val other = client(host.transport(Endpoint("browser")))

        here.section.theme!!.set(AppTheme.DARK)

        within { here.shell.state.first { it.entries[AppearanceKeys.THEME]?.value == JsonPrimitive("DARK") } }
        assertEquals(AppTheme.DARK, within { other.section.theme!!.current.first { it == AppTheme.DARK } })
        assertEquals(AppTheme.DARK, within { here.section.theme!!.current.first { it == AppTheme.DARK } })
        assertNull(here.section.notice!!.value, "nothing to say when the host took it")
    }

    @Test
    fun aNonDefaultPresetTurnsDynamicColourOffAndTheDefaultLeavesItAsTheHostHoldsIt() = runBlocking<Unit> {
        val host = RouterShellStateHost()
        val here = client(host.transport(Endpoint("desktop")), dynamicColorSupported = true)
        here.section.dynamicColor!!.set(true)
        within { here.section.dynamicColor!!.current.first { it } }

        here.section.themePreset!!.set(ThemePreset.DEFAULT)
        within { here.shell.state.first { it.entries[AppearanceKeys.THEME_PRESET]?.value == JsonPrimitive("DEFAULT") } }
        assertEquals(JsonPrimitive(true), host.entries().getValue(AppearanceKeys.DYNAMIC_COLOR), "Default keeps what the host holds")

        here.section.themePreset!!.set(ThemePreset.OCEAN)
        within { here.shell.state.first { it.entries[AppearanceKeys.THEME_PRESET]?.value == JsonPrimitive("OCEAN") } }
        within { here.shell.state.first { it.entries[AppearanceKeys.DYNAMIC_COLOR]?.value == JsonPrimitive(false) } }
    }

    @Test
    fun aPresetAndTheDynamicColourItTurnsOffAreOneProposalSoNoDeviceSeesHalfOfIt() = runBlocking<Unit> {
        val host = RouterShellStateHost()
        val here = client(host.transport(Endpoint("desktop")), dynamicColorSupported = true)
        val watcher = client(host.transport(Endpoint("browser")))
        here.section.dynamicColor!!.set(true)
        within { here.shell.state.first { it.entries[AppearanceKeys.DYNAMIC_COLOR]?.value == JsonPrimitive(true) } }
        host.proposals.clear()
        val seen = java.util.concurrent.CopyOnWriteArrayList<AppearanceValues>()
        val sawNewPreset = CompletableDeferred<Unit>()
        val collecting = CompletableDeferred<Unit>()
        scope.launch {
            watcher.appearance.values.collect {
                seen += it
                collecting.complete(Unit)
                if (it.themePreset == "OCEAN") sawNewPreset.complete(Unit)
            }
        }
        within { collecting.await() }

        here.section.themePreset!!.set(ThemePreset.OCEAN)
        within { here.shell.state.first { it.entries[AppearanceKeys.THEME_PRESET]?.value == JsonPrimitive("OCEAN") } }

        val bothKeys = listOf(AppearanceKeys.THEME_PRESET, AppearanceKeys.DYNAMIC_COLOR)
        assertEquals(listOf(bothKeys), host.proposals, "one proposal, both keys")
        assertEquals(JsonPrimitive(false), host.entries().getValue(AppearanceKeys.DYNAMIC_COLOR))
        within { sawNewPreset.await() }
        val half = seen.filter { it.themePreset == "OCEAN" && it.dynamicColor == true }
        assertTrue(half.isEmpty(), "never the new preset with the old dynamic colour: $half")
    }

    @Test
    fun aRefusedPresetChangesNeitherTheHostsPresetNorItsDynamicColour() = runBlocking<Unit> {
        val host = RouterShellStateHost()
        val link = host.transport(Endpoint("desktop"))
        val here = client(link, dynamicColorSupported = true)
        val persistentJobs = edits.coroutineContext[Job]!!.children.toSet()
        here.section.dynamicColor!!.set(true)
        within { here.shell.state.first { it.entries[AppearanceKeys.DYNAMIC_COLOR]?.value == JsonPrimitive(true) } }

        awaitEdits(persistentJobs)
        link.cut()
        within { here.shell.state.first { !it.live } }
        here.section.themePreset!!.set(ThemePreset.OCEAN)
        awaitEdits(persistentJobs)
        assertTrue(here.section.notice!!.value != null)
        link.restore()
        within { here.shell.state.first { it.live } }

        assertEquals(setOf(AppearanceKeys.DYNAMIC_COLOR), host.entries().keys)
        assertEquals(JsonPrimitive(true), host.entries().getValue(AppearanceKeys.DYNAMIC_COLOR))
    }

    @Test
    fun awaitingEditsWaitsForTheEditCoroutineToFinish() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        edits.launch {
            started.complete(Unit)
            release.await()
        }
        started.await()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { awaitEdits() }
        assertFalse(waiting.isCompleted)
        release.complete(Unit)
        within { waiting.await() }
    }

    @Test
    fun theDynamicColourRowIsLeftOutWhereThePlatformHasNone() = runBlocking<Unit> {
        val host = RouterShellStateHost()

        val unsupported = client(host.transport(Endpoint("desktop"))).section
        val supported = client(host.transport(Endpoint("phone")), dynamicColorSupported = true).section

        assertNull(unsupported.dynamicColor)
        assertTrue(unsupported.theme != null && unsupported.themePreset != null, "theme and preset are still offered")
        assertTrue(supported.dynamicColor != null, "dynamic colour is offered where the platform has it")
    }

    @Test
    fun anEditTheHostCannotTakeIsRefusedWithTheReasonAndTheLastValueKeepsShowing() = runBlocking<Unit> {
        val host = RouterShellStateHost()
        val seed = client(host.transport(Endpoint("other")))
        seed.section.theme!!.set(AppTheme.LIGHT)
        within { seed.shell.state.first { it.entries[AppearanceKeys.THEME]?.value == JsonPrimitive("LIGHT") } }
        val link = host.transport(Endpoint("desktop"))
        val here = client(link)
        within { here.section.theme!!.current.first { it == AppTheme.LIGHT } }

        link.cut()
        within { here.shell.state.first { !it.live } }
        here.section.theme!!.set(AppTheme.DARK)

        val notice = within { here.section.notice!!.first { it != null } }!!
        assertEquals(SettingsTone.Error, notice.tone)
        assertTrue("reach your host" in notice.text, notice.text)
        assertEquals(AppTheme.LIGHT, here.section.theme!!.current.value, "still what the host holds")
        link.restore()
        within { here.shell.state.first { it.live } }
        assertEquals(JsonPrimitive("LIGHT"), host.entries().getValue(AppearanceKeys.THEME), "nothing was queued")
    }

    private class Client(val appearance: HostAppearance, val section: AppearanceSettings, val shell: SharedShellState)

    /**
     * A client of the host that is following it and has read its document, so an edit is sent at once instead of being
     * refused as "not read yet": the wait is on the client's own state, not on time.
     */
    private fun client(transport: IChannelTransport, dynamicColorSupported: Boolean = false): Client {
        val shell = SharedShellState(channelShellStateLink(MutableStateFlow(transport)))
        val appearance = SharedShellAppearance(shell)
        scope.launch { appearance.run() }
        runBlocking { within { shell.state.first { it.editable } } }
        return Client(appearance, hostAppearanceSettings(appearance, edits, dynamicColorSupported), shell)
    }

    private suspend fun awaitEdits(persistentJobs: Set<Job> = emptySet()) {
        within { edits.coroutineContext[Job]!!.children.filterNot { it in persistentJobs }.toList().joinAll() }
    }

    private suspend fun <T> within(block: suspend () -> T): T = withTimeout(TIMEOUT_MS) { block() }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
