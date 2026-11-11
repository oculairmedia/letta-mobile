package com.letta.mobile.plugin.testkit.keystore

import com.letta.mobile.plugin.keystore.KeystoreProvider
import com.letta.mobile.plugin.keystore.SecretPath
import com.letta.mobile.plugin.keystore.SecretValue
import kotlinx.coroutines.runBlocking

/**
 * What a provider module hands the keystore conformance kit: fresh providers and the few hooks the
 * kit cannot do black-box. Implement it in the module's tests; no real vault or real secret is
 * needed, the kit writes only sentinel values under [workspace].
 */
public interface KeystoreConformanceFixture {
    /** A fresh, reachable provider with nothing under [workspace]. Locked if the provider needs unlock. */
    public suspend fun newProvider(): KeystoreProvider

    /** The path prefix under which the kit may create, list and delete items. */
    public val workspace: SecretPath get() = SecretPath("conformance")

    /** Material that unlocks providers from [newProvider]; null when they do not need unlock. */
    public suspend fun unlockMaterial(): SecretValue? = null

    /**
     * Takes [provider]'s backend down (stop the server, revoke the socket) and answers true, or
     * answers false when the fixture cannot; [KeystoreRule.FAIL_CLOSED] is then skipped.
     */
    public suspend fun makeUnavailable(provider: KeystoreProvider): Boolean = false

    /** Every line the provider module wrote to its own logs during the run, scanned for values. */
    public fun capturedOutput(): List<String> = emptyList()
}

/** The keystore contract rules the kit checks; each names the contract statements it covers. */
public enum class KeystoreRule {
    /** S1, S10: the descriptor is well formed and the same on every call. */
    DESCRIPTOR,

    /** S2, S3: an undeclared capability answers `unsupported`, before any input validation. */
    CAPABILITY,

    /** R3, R4, S3, S7: malformed refs, foreign providers and fields on `delete` answer `invalid_ref`. */
    INVALID_REF,

    /** S4, V6: empty and oversized values answer `invalid_value` and are not written. */
    INVALID_VALUE,

    /** S6: a missing slot answers `not_found` naming the same ref. */
    NOT_FOUND,

    /** R5, V4, S4: written values read back exactly, slots are independent, reads are fresh copies. */
    ROUND_TRIP,

    /** S4, S5: `create`, `replace` and `upsert` keep their semantics; metadata is accepted. */
    WRITE_MODES,

    /** S7: `delete` removes every slot of an item and a second delete answers `not_found`. */
    DELETE,

    /** S8, I2: listings hold distinct, sorted, field-less names in scope, and only those. */
    LIST,

    /** S9, S2: a locking provider starts locked, refuses wrong material and relocks; others refuse unlock. */
    LOCKING,

    /** S10, S11: a reachable, unlocked provider is ready and diagnostics keep their limit. */
    HEALTH,

    /** I3, S10: with its backend down every data operation answers `unavailable`, never success. */
    FAIL_CLOSED,

    /** Expected failures are results: no operation throws. */
    NO_THROW,

    /** I1, I2, E1: no value (or its base64) in any string the kit can capture. */
    NO_LEAK,
}

/** One way a provider broke [rule], in words its author can act on; it never quotes a value. */
public data class KeystoreFinding(
    /** The broken rule. */
    public val rule: KeystoreRule,
    /** What happened. */
    public val message: String,
) {
    /** `[RULE] message`. */
    override fun toString(): String = "[$rule] $message"
}

/**
 * What [KeystoreConformance.run] found. No [findings] means the provider keeps every checked rule;
 * [skipped] rules could not be checked (a capability the provider does not declare, or a fixture
 * hook it does not implement) and are not passes.
 */
public class KeystoreConformanceReport(
    /** Every broken statement. */
    public val findings: List<KeystoreFinding>,
    /** Rules the run could not exercise. */
    public val skipped: Set<KeystoreRule>,
) {
    /** Whether no rule was broken. */
    public val passed: Boolean get() = findings.isEmpty()

    /** The rules broken, for tests that expect a particular failure. */
    public val brokenRules: Set<KeystoreRule> get() = findings.map { it.rule }.toSet()

    /** Throws an [AssertionError] listing every finding unless the provider passed. */
    public fun assertPassed() {
        if (!passed) throw AssertionError("The provider breaks the keystore v1 contract:\n" + findings.joinToString("\n") { "  $it" })
    }

    /** The findings and skipped rules, which hold names and codes only. */
    override fun toString(): String = "KeystoreConformanceReport(findings=$findings, skipped=$skipped)"
}

/**
 * The keystore v1 conformance kit. A provider module runs it from its own tests:
 *
 * ```kotlin
 * @Test fun `keeps the keystore contract`() {
 *     KeystoreConformance.run(MyProviderFixture()).assertPassed()
 * }
 * ```
 *
 * It checks every contract statement observable through the SPI (see [KeystoreRule]) against
 * providers from the fixture, with sentinel values only, then scans every string it captured for
 * those values. Host-side statements (grants, audit, process hygiene) are outside its reach.
 */
public object KeystoreConformance {
    /** Runs every rule against providers from [fixture] and reports what broke the contract. */
    public fun run(fixture: KeystoreConformanceFixture): KeystoreConformanceReport =
        runBlocking { KeystoreConformanceRun(fixture).execute() }
}
