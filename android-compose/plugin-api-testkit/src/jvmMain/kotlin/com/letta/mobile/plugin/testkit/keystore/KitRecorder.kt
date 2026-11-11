package com.letta.mobile.plugin.testkit.keystore

import com.letta.mobile.plugin.keystore.KeystoreError
import com.letta.mobile.plugin.keystore.KeystoreErrorCode
import com.letta.mobile.plugin.keystore.KeystoreResult
import com.letta.mobile.plugin.keystore.ProviderDiagnostic
import com.letta.mobile.plugin.keystore.SecretValue
import com.letta.mobile.plugin.keystore.errorOrNull
import com.letta.mobile.plugin.keystore.valueOrNull
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/** Where a captured string came from, for findings. */
@JvmInline
internal value class Origin(val label: String)

/** A tag that makes each issued sentinel value distinct. */
@JvmInline
internal value class SentinelTag(val name: String)

/**
 * The run's bookkeeping: findings, skipped rules, every string the kit captured (scanned for values
 * at the end) and every sentinel value it issued. Calls go through [call] so a throw becomes a
 * [KeystoreRule.NO_THROW] finding and every answer is captured.
 */
internal class KitRecorder(private val fixture: KeystoreConformanceFixture) {
    private val findings = mutableListOf<KeystoreFinding>()
    private val skipped = linkedSetOf<KeystoreRule>()
    private val captured = mutableListOf<Pair<Origin, String>>()
    private val issued = mutableListOf<String>()
    private val token = Random.nextLong().toULong().toString(RADIX)

    fun text(tag: SentinelTag): String = "lks-conformance-$token-${tag.name}"

    fun secret(tag: SentinelTag): SecretValue {
        val text = text(tag)
        if (text !in issued) issued += text
        return SecretValue.ofBytes(text.encodeToByteArray())
    }

    fun fail(rule: KeystoreRule, message: String) {
        findings += KeystoreFinding(rule, message)
    }

    fun skip(rule: KeystoreRule) {
        skipped += rule
    }

    fun capture(origin: Origin, text: String) {
        captured += origin to text
    }

    /** Remembers a value the kit did not issue (the fixture's unlock material) so the leak scan looks for it too. */
    fun watch(value: SecretValue) {
        val text = value.revealSecretBytes { it.decodeToString() }
        if (text.length >= MIN_WATCHED_LENGTH) issued += text
    }

    suspend fun <T> attempt(label: Origin, block: suspend () -> T): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Exception) {
            fail(KeystoreRule.NO_THROW, "${label.label} threw ${thrown::class.simpleName}")
            capture(Origin("the exception ${label.label} threw"), thrown.stackTraceToString())
            null
        }

    suspend fun <T> call(label: Origin, block: suspend () -> KeystoreResult<T>): KeystoreResult<T>? =
        attempt(label, block)?.also { captureResult(label, it) }

    suspend fun <T> expectSuccess(rule: KeystoreRule, label: Origin, block: suspend () -> KeystoreResult<T>): T? {
        val result = call(label, block) ?: return null
        val error = result.errorOrNull()
        if (error != null) fail(rule, "${label.label} answered ${error.wireCode()} instead of success")
        return result.valueOrNull()
    }

    suspend fun expectError(rule: KeystoreRule, label: Origin, expected: KeystoreError, block: suspend () -> KeystoreResult<*>) {
        val result = call(label, block) ?: return
        discard(result)
        if (result.errorOrNull() != expected) fail(rule, "${label.label} answered ${outcome(result)} instead of $expected")
    }

    suspend fun expectCode(rule: KeystoreRule, label: Origin, expected: KeystoreErrorCode, block: suspend () -> KeystoreResult<*>) {
        val result = call(label, block) ?: return
        discard(result)
        if (result.errorOrNull()?.code != expected) fail(rule, "${label.label} answered ${outcome(result)} instead of ${expected.wire()}")
    }

    fun checkDiagnostic(origin: Origin, diagnostic: ProviderDiagnostic?) {
        if (diagnostic == null) return
        capture(origin, diagnostic.value)
        if (!diagnostic.isWellFormed) fail(KeystoreRule.HEALTH, "${origin.label} is longer than the diagnostic limit")
    }

    fun report(): KeystoreConformanceReport {
        scanForValues()
        return KeystoreConformanceReport(findings.toList(), skipped - brokenRules())
    }

    private fun brokenRules(): Set<KeystoreRule> = findings.map { it.rule }.toSet()

    private fun captureResult(label: Origin, result: KeystoreResult<*>) {
        capture(Origin("the answer to ${label.label}"), result.toString())
        val error = result.errorOrNull() ?: return
        capture(Origin("the error message of ${label.label}"), error.message)
        checkDiagnostic(Origin("the diagnostic of ${label.label}"), error.diagnostic())
    }

    private fun scanForValues() {
        val texts = captured + fixture.capturedOutput().map { Origin("the provider's captured output") to it }
        val needles = issued.flatMap { listOf(it, Base64.getEncoder().encodeToString(it.encodeToByteArray())) }
        texts.filter { (_, text) -> needles.any { it in text } }
            .map { (origin, _) -> origin.label }
            .distinct()
            .forEach { fail(KeystoreRule.NO_LEAK, "a secret value appears in $it") }
    }

    private companion object {
        const val RADIX = 16
        const val MIN_WATCHED_LENGTH = 6
    }
}

/** The wire spelling of a code. */
internal fun KeystoreErrorCode.wire(): String = name.lowercase()

private fun KeystoreError.wireCode(): String = code.wire()

private fun outcome(result: KeystoreResult<*>): String = result.errorOrNull()?.toString() ?: "success"

/** Wipes a value the kit did not expect to receive, so an unexpected success leaves nothing behind. */
private fun discard(result: KeystoreResult<*>) {
    (result.valueOrNull() as? SecretValue)?.wipe()
}

private fun KeystoreError.diagnostic(): ProviderDiagnostic? = when (this) {
    is KeystoreError.Unavailable -> detail
    is KeystoreError.Internal -> detail
    else -> null
}
