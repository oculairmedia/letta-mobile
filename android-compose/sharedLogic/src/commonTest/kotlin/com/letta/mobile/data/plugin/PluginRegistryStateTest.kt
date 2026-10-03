package com.letta.mobile.data.plugin

import com.letta.mobile.data.plugin.PluginRegistryFixtures.NOW
import com.letta.mobile.data.plugin.PluginRegistryFixtures.SHA_A
import com.letta.mobile.data.plugin.PluginRegistryFixtures.SHA_B
import com.letta.mobile.data.plugin.PluginRegistryFixtures.SHA_C
import com.letta.mobile.data.plugin.PluginRegistryFixtures.json
import com.letta.mobile.data.plugin.PluginRegistryFixtures.manifest
import com.letta.mobile.data.plugin.PluginRegistryFixtures.version
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The registry's pure transitions (plan section 6.1): install, update with rollback, enable,
 * disable, remove, consent re-asked when an update asks for more, and packages pinned by hash.
 */
class PluginRegistryStateTest {
    private val example = PluginRegistryFixtures.example
    private val id = example.id

    private fun reduce(state: PluginRegistryState, command: PluginCommand): PluginRegistryState =
        assertIs<PluginTransition.Applied>(PluginRegistryReducer.reduce(state, command), "$command").state

    private fun refusal(state: PluginRegistryState, command: PluginCommand): PluginTransition.Refused =
        assertIs<PluginTransition.Refused>(PluginRegistryReducer.reduce(state, command), "$command")

    private fun installed(): PluginRegistryState = PluginRegistryFixtures.registryOf(example)

    private fun enabled(): PluginRegistryState = reduce(installed(), PluginCommand.Enable(id))

    private fun stage(update: PluginManifest, sha: String = SHA_B, consent: PluginConsent? = null) =
        PluginCommand.StageUpdate(update, sha, consent, NOW + 1)

    @Test
    fun anInstallRecordsTheVersionHashConsentAndUnsetSecrets() {
        val plugin = installed()[id]!!
        assertEquals("1.2.0", plugin.version)
        assertEquals(SHA_A, plugin.packageSha256)
        assertEquals(PluginState.Installed, plugin.state)
        assertEquals(mapOf("apiToken" to SecretState.UNSET), plugin.secretsStatus)
        assertEquals(PluginHealth.Unknown, plugin.health)
        assertNull(plugin.previousVersion)
    }

    @Test
    fun anInstallNeedsAHashConsentAndANewId() {
        val consent = PluginConsent.forManifest(example, NOW)
        assertEquals(PluginRefusal.BAD_HASH, refusal(PluginRegistryState(), PluginCommand.Install(example, "sha256:nothex", consent, NOW)).refusal)
        assertEquals(PluginRefusal.ALREADY_INSTALLED, refusal(installed(), PluginCommand.Install(example, SHA_A, consent, NOW)).refusal)
        val partial = consent.copy(capabilities = consent.capabilities - PluginCapability.NET_CONNECT)
        val refused = refusal(PluginRegistryState(), PluginCommand.Install(example, SHA_A, partial, NOW))
        assertEquals(PluginRefusal.CONSENT_REQUIRED, refused.refusal)
        assertEquals(setOf(PluginCapability.NET_CONNECT), refused.consentDiff?.addedCapabilities)
        assertEquals(PluginRefusal.CONSENT_REQUIRED, refusal(PluginRegistryState(), PluginCommand.Install(example, SHA_A, consent.copy(forVersion = "1.0.0"), NOW)).refusal)
    }

    @Test
    fun aSecondPluginOfferingTheSameToolIsRefused() {
        val other = manifest("/id" to json("\"other.example\""))
        val consent = PluginConsent.forManifest(other, NOW)
        val refused = refusal(installed(), PluginCommand.Install(other, SHA_B, consent, NOW))
        assertEquals(PluginRefusal.TOOL_COLLISION, refused.refusal)
        assertTrue(refused.message.contains("example_start (letta.example)"), refused.message)
    }

    @Test
    fun enableNeedsRequiredSettingsAndSecretsThenTheLifecycleRuns() {
        val bare = reduce(PluginRegistryState(), PluginCommand.Install(example, SHA_A, PluginConsent.forManifest(example, NOW), NOW))
        assertEquals(PluginRefusal.NOT_READY, refusal(bare, PluginCommand.Enable(id)).refusal)
        val configured = reduce(bare, PluginCommand.SettingsSet(id, PluginSettingsSummary(setOf("baseUrl"))))
        val on = reduce(configured, PluginCommand.Enable(id))
        assertEquals(PluginState.Enabled, on[id]!!.state)
        val active = reduce(on, PluginCommand.Activated(id))
        assertEquals(PluginState.Active, active[id]!!.state)
        assertEquals(PluginState.Enabled, reduce(active, PluginCommand.Stopped(id))[id]!!.state)
        val faulted = reduce(active, PluginCommand.Fault(id, "crashed 3 times"))
        assertEquals(PluginState.Faulted("crashed 3 times"), faulted[id]!!.state)
        assertEquals("crashed 3 times", faulted[id]!!.lastFault)
        assertEquals(PluginState.Enabled, reduce(faulted, PluginCommand.Enable(id))[id]!!.state)
        assertEquals(PluginState.Installed, reduce(faulted, PluginCommand.Disable(id))[id]!!.state)
    }

    @Test
    fun aRequiredSecretMustBeSetBeforeEnabling() {
        val needsSecret = manifest("/secrets/0/required" to json("true"))
        val state = PluginRegistryFixtures.registryOf(needsSecret)
        assertEquals(PluginRefusal.NOT_READY, refusal(state, PluginCommand.Enable(id)).refusal)
        val withSecret = reduce(state, PluginCommand.SecretsSet(id, mapOf("apiToken" to SecretState.SET, "undeclared" to SecretState.SET)))
        assertEquals(mapOf("apiToken" to SecretState.SET), withSecret[id]!!.secretsStatus)
        assertEquals(PluginState.Enabled, reduce(withSecret, PluginCommand.Enable(id))[id]!!.state)
    }

    @Test
    fun aDriverEventForAPluginThatIsOffIsRefused() {
        assertEquals(PluginRefusal.INVALID_STATE, refusal(installed(), PluginCommand.Activated(id)).refusal)
        assertEquals(PluginRefusal.INVALID_STATE, refusal(installed(), PluginCommand.Fault(id, "x")).refusal)
        assertEquals(PluginRefusal.UNKNOWN_PLUGIN, refusal(installed(), PluginCommand.Enable("no.such")).refusal)
    }

    @Test
    fun anUpdateThatAsksForNothingNewKeepsConsentAndResumes() {
        val staged = reduce(reduce(enabled(), PluginCommand.Activated(id)), stage(version("1.3.0")))
        val updating = staged[id]!!
        assertEquals(PluginState.Updating, updating.state)
        assertEquals("1.2.0", updating.version)
        assertEquals("1.3.0", updating.pendingUpdate?.record?.manifest?.version)
        assertTrue(updating.advertised, "an update from an enabled plugin keeps its tools advertised")
        assertEquals(PluginRefusal.UPDATE_IN_PROGRESS, refusal(staged, PluginCommand.Disable(id)).refusal)
        assertEquals(PluginRefusal.UPDATE_IN_PROGRESS, refusal(staged, stage(version("1.4.0"), SHA_C)).refusal)

        val done = reduce(staged, PluginCommand.CompleteUpdate(id))[id]!!
        assertEquals("1.3.0", done.version)
        assertEquals(SHA_B, done.packageSha256)
        assertEquals("1.3.0", done.consent.forVersion)
        assertEquals(PluginState.Enabled, done.state)
        assertEquals("1.2.0", done.previousVersion?.manifest?.version)
        assertNull(done.pendingUpdate)
    }

    @Test
    fun anUpdateThatAddsACapabilityNeedsFreshConsent() {
        val grows = version("1.3.0", "/capabilities/5" to json("\"ui:openLink\""))
        val refused = refusal(enabled(), stage(grows))
        assertEquals(PluginRefusal.CONSENT_REQUIRED, refused.refusal)
        assertEquals(setOf(PluginCapability.UI_OPEN_LINK), refused.consentDiff?.addedCapabilities)
        val stale = PluginConsent.forManifest(example, NOW)
        assertEquals(PluginRefusal.CONSENT_REQUIRED, refusal(enabled(), stage(grows, consent = stale)).refusal)
        val fresh = PluginConsent.forManifest(grows, NOW + 1)
        val staged = reduce(enabled(), stage(grows, consent = fresh))
        val done = reduce(staged, PluginCommand.CompleteUpdate(id))[id]!!
        assertEquals(fresh, done.consent)
    }

    @Test
    fun anUpdateThatAddsAnOriginOrChangesTheRuntimeNeedsFreshConsent() {
        val origin = version("1.3.0", "/net/connect/2" to json("\"https://more.example.test\""))
        assertEquals(setOf("https://more.example.test"), refusal(enabled(), stage(origin)).consentDiff?.addedOrigins)
        val runtime = version("1.3.0", "/runtime" to json("""{"kind":"service","url":"wss://h.example.test/lcp"}"""))
        assertEquals(true, refusal(enabled(), stage(runtime)).consentDiff?.runtimeChanged)
    }

    @Test
    fun versionsArePinnedToTheirPackage() {
        assertEquals(PluginRefusal.NOT_NEWER, refusal(enabled(), stage(example, SHA_B)).refusal)
        assertEquals(PluginRefusal.NOT_NEWER, refusal(enabled(), stage(version("1.1.9"))).refusal)
        assertEquals(PluginRefusal.NOT_NEWER, refusal(enabled(), stage(version("1.2.0-rc.1"))).refusal)
        assertEquals(PluginRefusal.BAD_HASH, refusal(enabled(), stage(version("1.3.0"), "ABC")).refusal)
    }

    @Test
    fun aFailedUpdateRollsBackToTheInstalledVersion() {
        val staged = reduce(enabled(), stage(version("1.3.0")))
        val failed = reduce(staged, PluginCommand.FailUpdate(id, "jar does not load"))[id]!!
        assertEquals("1.2.0", failed.version)
        assertEquals(SHA_A, failed.packageSha256)
        assertEquals(PluginState.Enabled, failed.state)
        assertNull(failed.pendingUpdate)
        assertEquals("update to 1.3.0 failed: jar does not load", failed.lastFault)
        assertEquals(PluginRefusal.NO_UPDATE_PENDING, refusal(enabled(), PluginCommand.CompleteUpdate(id)).refusal)
    }

    @Test
    fun aCompletedUpdateCanBeRolledBackOnce() {
        val updated = reduce(reduce(enabled(), stage(version("1.3.0", "/secrets/1" to json("""{"name":"extra"}""")))), PluginCommand.CompleteUpdate(id))
        assertEquals(mapOf("apiToken" to SecretState.UNSET, "extra" to SecretState.UNSET), updated[id]!!.secretsStatus)
        val back = reduce(updated, PluginCommand.Rollback(id))[id]!!
        assertEquals("1.2.0", back.version)
        assertEquals(SHA_A, back.packageSha256)
        assertEquals(mapOf("apiToken" to SecretState.UNSET), back.secretsStatus)
        assertEquals(PluginState.Enabled, back.state)
        assertEquals(PluginRefusal.NO_PREVIOUS_VERSION, refusal(reduce(updated, PluginCommand.Rollback(id)), PluginCommand.Rollback(id)).refusal)
    }

    @Test
    fun anUpdateThatNeedsANewRequiredSecretComesBackInstalled() {
        val needs = version("1.3.0", "/secrets/1" to json("""{"name":"extra","required":true}"""))
        val done = reduce(reduce(enabled(), stage(needs)), PluginCommand.CompleteUpdate(id))[id]!!
        assertEquals(PluginState.Installed, done.state)
    }

    @Test
    fun consentIsRecordedOnlyWhenItCoversTheVersion() {
        val consent = PluginConsent.forManifest(example, NOW + 5)
        assertEquals(NOW + 5, reduce(installed(), PluginCommand.RecordConsent(id, consent))[id]!!.consent.grantedAtEpochMs)
        assertEquals(PluginRefusal.CONSENT_REQUIRED, refusal(installed(), PluginCommand.RecordConsent(id, consent.copy(origins = emptySet()))).refusal)
    }

    @Test
    fun removeDropsThePluginAndHealthIsRecorded() {
        val healthy = reduce(enabled(), PluginCommand.HealthReported(id, PluginHealth.Degraded("slow")))
        assertEquals(PluginHealth.Degraded("slow"), healthy[id]!!.health)
        assertTrue(reduce(healthy, PluginCommand.Remove(id)).plugins.isEmpty())
    }

    @Test
    fun theRegistryRoundTripsThroughPluginsJson() {
        val state = reduce(enabled(), stage(version("1.3.0")))
        val text = state.encode()
        assertEquals(state, PluginRegistryState.decode(text))
        assertTrue(text.contains("\"kind\": \"updating\"") || text.contains("\"kind\":\"updating\""), text)
    }
}
