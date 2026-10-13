package com.letta.mobile.data.health

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.appserver.AppServerIdentity
import com.letta.mobile.data.transport.appserver.AppServerProbeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** letta-mobile-ckmub.11: the picker's online dots come from an App Server probe, never Letta REST. */
class ServerHealthTrackerTest {
    private val probed = mutableListOf<Pair<String, String?>>()
    private var answer: AppServerProbeResult = AppServerProbeResult.Ok(AppServerIdentity("local", "0.33.6", 1))
    private val probe = ServerHealthProbe { url, token ->
        probed += url to token
        answer
    }

    private fun remote(id: String, url: String, token: String? = null) =
        LettaConfig(id = id, mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = url, accessToken = token)

    @Test
    fun anAppServerThatAnswersIsOnlineAndOneThatDoesNotIsOffline() = runTest {
        assertEquals(ServerHealthState.ONLINE, probe.probe(remote("a", "wss://host.example/ws", "tkn")))
        assertEquals(listOf<Pair<String, String?>>("wss://host.example/ws" to "tkn"), probed)

        answer = AppServerProbeResult.Unavailable("connection refused")
        assertEquals(ServerHealthState.OFFLINE, probe.probe(remote("a", "ws://127.0.0.1:4500")))
    }

    @Test
    fun aRejectedTokenOrAnIncompatibleServerStillAnswers() = runTest {
        answer = AppServerProbeResult.Authentication("HTTP 401")
        assertEquals(ServerHealthState.ONLINE, probe.probe(remote("a", "http://h:1")))
        answer = AppServerProbeResult.Incompatible("HTTP 404", endpointMissing = true)
        assertEquals(ServerHealthState.ONLINE, probe.probe(remote("a", "http://h:1")))
    }

    @Test
    fun theOnDeviceRuntimeAndIrohHostsAreNeverDialled() = runTest {
        assertEquals(ServerHealthState.ONLINE, probe.probe(LettaConfig(id = "l", mode = LettaConfig.Mode.LOCAL, serverUrl = "")))
        assertEquals(ServerHealthState.ONLINE, probe.probe(remote("i", "iroh://abc123")))
        assertEquals(ServerHealthState.ONLINE, probe.probe(remote("i", "https://iroh://abc123")))
        assertEquals(emptyList<Pair<String, String?>>(), probed)
    }

    @Test
    fun anAddressThatIsNotAnAppServerUrlIsOffline() = runTest {
        assertEquals(ServerHealthState.OFFLINE, probe.probe(remote("x", "ftp://host")))
        assertEquals(emptyList<Pair<String, String?>>(), probed)
    }

    @Test
    fun theTrackerProbesEachConfigAndReprobesOnlyWhenTheSetChanges() = runTest {
        val configs = MutableStateFlow(listOf(remote("a", "ws://a:1"), remote("b", "ws://b:1")))
        val reported = mutableListOf<String>()
        val tracker = ServerHealthTracker(configs, probe, backgroundScope, listener = { config, _ -> reported += config.id })
        runCurrent()
        assertEquals(mapOf("a" to ServerHealthState.ONLINE, "b" to ServerHealthState.ONLINE), tracker.states.value)
        assertEquals(2, probed.size)

        // A rename keeps the ids: no new round trip.
        configs.value = listOf(remote("a", "ws://a:1").copy(serverUrl = "ws://a:1/"), remote("b", "ws://b:1"))
        runCurrent()
        assertEquals(2, probed.size)

        // A removed config drops its dot; an explicit refresh probes again.
        answer = AppServerProbeResult.Unavailable("down")
        configs.value = listOf(remote("b", "ws://b:1"))
        runCurrent()
        assertEquals(mapOf("b" to ServerHealthState.OFFLINE), tracker.states.value)
        tracker.refreshAll()
        assertEquals(4, probed.size)
        assertEquals(listOf("a", "b", "b", "b"), reported.sorted())
    }
}
