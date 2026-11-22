package com.letta.mobile.data.shellstate.appearance

import com.letta.mobile.data.backend.SessionPlatform
import com.letta.mobile.data.clientstore.hostAppearanceOver
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.session.channelTransports
import com.letta.mobile.data.session.graph.HostAppServerClients
import com.letta.mobile.data.session.graph.HostSessionBinding
import com.letta.mobile.data.session.graph.HostSessionGraphFactory
import com.letta.mobile.data.session.graph.HostSessionGraphProvider
import com.letta.mobile.data.shellstate.AppearanceKeys
import com.letta.mobile.data.shellstate.AppearanceValues
import com.letta.mobile.data.shellstate.HostAppearance
import com.letta.mobile.data.shellstate.ShellStateEditOutcome
import com.letta.mobile.data.shellstate.channel.channelShellStateLink
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * Desktop and the web read and change the person's appearance through the same shared wiring
 * ([hostAppearanceOver] over [channelShellStateLink]): each client below is built the way its platform builds it, over a
 * channel transport that answers through the production shell-state routes behind the production capability gate
 * ([RouterShellStateHost.PairedTransport]), so what is asserted is what the host holds, not what a fake accepts.
 */
class SharedAppearanceWiringTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stop() = scope.cancel()

    @Test
    fun desktopAndWebOverOneHostShowTheSameAppearanceAndEachOthersChanges() = runBlocking {
        val host = RouterShellStateHost()
        val desktop = desktopClient(MutableStateFlow(host.transport(Endpoint("desktop"))))
        val web = webClient(MutableStateFlow(host.transport(Endpoint("browser"), IrohPeerCapabilities.CHAT_ROLE)))

        assertEquals(ShellStateEditOutcome.APPLIED, desktop.edit { setTheme("DARK") })
        within { web.values.first { it.theme == "DARK" } }
        assertEquals(ShellStateEditOutcome.APPLIED, web.edit { setThemePreset("OCEAN") })
        within { desktop.values.first { it.themePreset == "OCEAN" } }

        val held = AppearanceValues("DARK", "OCEAN")
        assertEquals(held, within { desktop.values.first { it == held } })
        assertEquals(held, within { web.values.first { it == held } })
        val onHost = mapOf(AppearanceKeys.THEME to JsonPrimitive("DARK"), AppearanceKeys.THEME_PRESET to JsonPrimitive("OCEAN"))
        assertEquals(onHost, host.entries())
        assertEquals(desktop.current, web.current, "both read the host's document on their first frame")
    }

    @Test
    fun aDesktopThatSwitchesBackendShowsTheNewHostsAppearanceNotTheOldOnes() = runBlocking {
        val first = RouterShellStateHost()
        val second = RouterShellStateHost()
        seedTheme(first, "DARK")
        seedTheme(second, "LIGHT")
        val transports = MutableStateFlow<IChannelTransport?>(first.transport(Endpoint("desktop")))
        val desktop = desktopClient(transports)
        within { desktop.values.first { it.theme == "DARK" } }

        // Another backend, whose transport has the very same session id and connection epoch as the first one had.
        transports.value = second.transport(Endpoint("desktop"))

        assertEquals("LIGHT", within { desktop.values.first { it.theme == "LIGHT" } }.theme)
    }

    @Test
    fun aWebPageWhoseSessionIsRebuiltOverAnotherHostShowsThatHostsAppearance() = runBlocking {
        val first = RouterShellStateHost()
        val second = RouterShellStateHost()
        seed(first, "SAKURA")
        seed(second, "SPRING")
        val channel = MutableStateFlow<IChannelTransport>(first.transport(Endpoint("browser"), IrohPeerCapabilities.CHAT_ROLE))
        val graphs = graphsOver(channel)
        val web = hostAppearanceOver(channelShellStateLink(graphs.channelTransports()), InMemorySecureSettingsStore())
        scope.launch { web.run() }
        within { web.values.first { it.themePreset == "SAKURA" } }

        channel.value = second.transport(Endpoint("browser"), IrohPeerCapabilities.CHAT_ROLE)
        graphs.rebuild()

        assertEquals("SPRING", within { web.values.first { it.themePreset == "SPRING" } }.themePreset)
    }

    @Test
    fun anEditTheHostCannotApplyIsRefusedAndNeverQueuedWhileTheLastValueKeepsShowing() = runBlocking {
        val host = RouterShellStateHost()
        val store = InMemorySecureSettingsStore()
        val online = host.transport(Endpoint("desktop"))
        val first = desktopClient(MutableStateFlow(online), store)
        first.edit { setTheme("DARK") }
        within { while (!store.getString(MIRROR_KEY).orEmpty().contains("DARK")) delay(POLL_MS) }

        // The same device after a restart with the host out of reach: the mirror shows, the edit is refused.
        val cut = host.transport(Endpoint("desktop")).also { it.cut() }
        val restarted = desktopClient(MutableStateFlow(cut), store)
        assertEquals("DARK", restarted.values.first().theme, "the host's last value, from the mirror")
        assertEquals("DARK", restarted.current.theme)
        assertEquals(ShellStateEditOutcome.OFFLINE, restarted.setTheme("LIGHT"))
        assertEquals("DARK", restarted.values.first().theme, "the refused edit is not shown either")

        cut.restore()
        delay(SETTLE_MS)
        assertEquals(JsonPrimitive("DARK"), host.entries().getValue(AppearanceKeys.THEME), "nothing was queued and replayed")
    }

    @Test
    fun aDeviceWithNoTransportYetRefusesEditsAndShowsTheDefaultsWithoutFailing() = runBlocking {
        val none = desktopClient(MutableStateFlow(null))

        assertEquals(AppearanceValues.NONE, none.values.first())
        assertEquals(ShellStateEditOutcome.OFFLINE, none.setTheme("DARK"))
        assertEquals(AppearanceValues.NONE, none.current)
    }

    /** Puts [theme] on [host] through a throwaway client, as another device would. */
    private suspend fun seedTheme(host: RouterShellStateHost, theme: String) {
        val applied = desktopClient(MutableStateFlow(host.transport(Endpoint("setup")))).edit { setTheme(theme) }
        assertEquals(ShellStateEditOutcome.APPLIED, applied)
    }

    /** Puts the colour [preset] on [host] through a throwaway client, as another device would. */
    private suspend fun seed(host: RouterShellStateHost, preset: String) {
        val applied = desktopClient(MutableStateFlow(host.transport(Endpoint("setup")))).edit { setThemePreset(preset) }
        assertEquals(ShellStateEditOutcome.APPLIED, applied)
    }

    /** Desktop's binding: the transport slot the shell publishes into, and the desktop's own key-value store. */
    private fun desktopClient(
        transports: MutableStateFlow<IChannelTransport?>,
        store: SecureSettingsStore = InMemorySecureSettingsStore(),
    ): HostAppearance = hostAppearanceOver(channelShellStateLink(transports), store).also { scope.launch { it.run() } }

    /** The web's binding: the live session graph's transport, rebuilt with the session. */
    private fun webClient(channel: MutableStateFlow<IChannelTransport>): HostAppearance =
        hostAppearanceOver(channelShellStateLink(graphsOver(channel).channelTransports()), InMemorySecureSettingsStore())
            .also { scope.launch { it.run() } }

    private fun graphsOver(channel: MutableStateFlow<IChannelTransport>) = HostSessionGraphProvider(
        HostSessionGraphFactory {
            HostSessionBinding(
                platform = SessionPlatform.WEB,
                config = null,
                appServerClients = HostAppServerClients { null },
                channelTransport = channel.value,
            )
        },
        SessionPlatform.WEB,
    )

    /**
     * An edit once the client has read the host's document: an edit sent while it is still being read is refused
     * (nothing is based on a document that is not read yet), so a setup edit tries again until the host answers it.
     */
    private suspend fun HostAppearance.edit(change: suspend HostAppearance.() -> ShellStateEditOutcome): ShellStateEditOutcome =
        within {
            var outcome = change()
            while (outcome == ShellStateEditOutcome.OFFLINE) {
                delay(POLL_MS)
                outcome = change()
            }
            outcome
        }

    private suspend fun <T> within(block: suspend () -> T): T = withTimeout(TIMEOUT_MS) { block() }

    private companion object {
        const val MIRROR_KEY = "shell_state_mirror_v1"
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 400L
        const val POLL_MS = 20L
    }
}
