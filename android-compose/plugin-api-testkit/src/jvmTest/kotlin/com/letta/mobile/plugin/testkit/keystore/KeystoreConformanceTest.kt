package com.letta.mobile.plugin.testkit.keystore

import com.letta.mobile.plugin.keystore.KeystoreCapability
import com.letta.mobile.plugin.keystore.KeystoreProvider
import com.letta.mobile.plugin.keystore.SecretValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The kit passes the in-memory provider in every shape and fails each broken one on exactly the rule it breaks. */
class KeystoreConformanceTest {
    private val material = SecretValue.ofBytes("LKS-SENTINEL-unlock-material".encodeToByteArray())

    private fun fixture(create: (SecretValue?) -> InMemoryKeystoreProvider) = InMemoryFixture(material = material, create = create)

    private fun assertBreaks(rule: KeystoreRule, fixture: KeystoreConformanceFixture) {
        val report = KeystoreConformance.run(fixture)
        assertEquals(setOf(rule), report.brokenRules, report.toString())
        assertNoValueIn(report)
    }

    private fun assertBreaks(rule: KeystoreRule, provider: () -> InMemoryKeystoreProvider) =
        assertBreaks(rule, fixture { provider() })

    private fun assertNoValueIn(report: KeystoreConformanceReport) {
        assertFalse("lks-conformance" in report.toString(), "a finding quotes a value: $report")
        assertFalse("LKS-SENTINEL" in report.toString(), "a finding quotes the unlock material: $report")
    }

    @Test
    fun `the in-memory provider keeps the contract with every capability`() {
        val report = KeystoreConformance.run(InMemoryFixture())
        report.assertPassed()
        assertEquals(setOf(KeystoreRule.CAPABILITY), report.skipped, "nothing to refuse when every capability is declared")
    }

    @Test
    fun `a locking provider keeps the contract`() {
        val report = KeystoreConformance.run(fixture { InMemoryKeystoreProvider(needsUnlock = true, material = it) })
        report.assertPassed()
        assertEquals(setOf(KeystoreRule.CAPABILITY), report.skipped)
    }

    @Test
    fun `a read-only provider passes with the write rules skipped, not passed`() {
        val readOnly = setOf(KeystoreCapability.READ, KeystoreCapability.LIST)
        val report = KeystoreConformance.run(fixture { InMemoryKeystoreProvider(readOnly) })
        report.assertPassed()
        assertEquals(
            setOf(KeystoreRule.INVALID_VALUE, KeystoreRule.ROUND_TRIP, KeystoreRule.WRITE_MODES, KeystoreRule.DELETE),
            report.skipped,
        )
    }

    @Test
    fun `a fixture that cannot take the backend down skips fail-closed`() {
        val fixture = object : KeystoreConformanceFixture {
            override suspend fun newProvider(): KeystoreProvider = InMemoryKeystoreProvider()
        }
        val report = KeystoreConformance.run(fixture)
        report.assertPassed()
        assertEquals(setOf(KeystoreRule.CAPABILITY, KeystoreRule.FAIL_CLOSED), report.skipped)
    }

    @Test
    fun `a report that did not pass fails its assertion with every finding`() {
        val report = KeystoreConformance.run(fixture { FailsOpen() })
        val error = assertFailsWith<AssertionError> { report.assertPassed() }
        assertTrue("[FAIL_CLOSED]" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `fail closed - success while the backend is down`() = assertBreaks(KeystoreRule.FAIL_CLOSED) { FailsOpen() }

    @Test
    fun `capability - writing without the capability`() = assertBreaks(KeystoreRule.CAPABILITY) { IgnoresCapabilities() }

    @Test
    fun `invalid ref - serving malformed refs`() = assertBreaks(KeystoreRule.INVALID_REF) { AcceptsMalformedRefs() }

    @Test
    fun `invalid value - storing empty and oversized values`() = assertBreaks(KeystoreRule.INVALID_VALUE) { AcceptsInvalidValues() }

    @Test
    fun `no throw - throwing for a missing slot`() = assertBreaks(KeystoreRule.NO_THROW) { ThrowsOnMissing() }

    @Test
    fun `round trip - handing out the stored instance`() = assertBreaks(KeystoreRule.ROUND_TRIP) { SharesInstances() }

    @Test
    fun `list - listing fields instead of items`() = assertBreaks(KeystoreRule.LIST) { ListsFields() }

    @Test
    fun `locking - a locking provider that starts open`() = assertBreaks(KeystoreRule.LOCKING, fixture { StartsUnlocked(material) })

    @Test
    fun `locking - a locking provider without fixture material`() =
        assertBreaks(KeystoreRule.LOCKING, InMemoryFixture { InMemoryKeystoreProvider(needsUnlock = true, material = material) })

    @Test
    fun `no leak - a value in a diagnostic`() = assertBreaks(KeystoreRule.NO_LEAK) { LeaksInDiagnostic() }

    @Test
    fun `no leak - a value in the provider's toString`() = assertBreaks(KeystoreRule.NO_LEAK) { LeaksInToString() }

    @Test
    fun `no leak - a value in the provider's own log`() {
        val log = mutableListOf<String>()
        assertBreaks(KeystoreRule.NO_LEAK, InMemoryFixture(output = log) { LogsValues(log) })
    }
}
