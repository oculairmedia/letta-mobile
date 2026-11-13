package com.letta.mobile.data.secrets.host

import com.letta.mobile.data.secrets.journal.InMemorySecretRequestJournal
import com.letta.mobile.data.secrets.request.PrincipalAccess
import com.letta.mobile.data.secrets.request.PrincipalOrigin
import com.letta.mobile.data.secrets.request.RequestingAgent
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.journal.SecretRequestJournal
import com.letta.mobile.data.secrets.request.SecretRequestPrincipal
import com.letta.mobile.data.secrets.request.SecretRequestTestData
import com.letta.mobile.data.secrets.request.VaultItemName
import com.letta.mobile.data.secrets.request.VerifiedEndpointId
import com.letta.mobile.plugin.keystore.KeystoreCapability
import com.letta.mobile.plugin.keystore.KeystoreDescriptor
import com.letta.mobile.plugin.keystore.KeystoreError
import com.letta.mobile.plugin.keystore.KeystoreHealth
import com.letta.mobile.plugin.keystore.KeystoreProvider
import com.letta.mobile.plugin.keystore.KeystoreProviderId
import com.letta.mobile.plugin.keystore.KeystoreResult
import com.letta.mobile.plugin.keystore.HealthStatus
import com.letta.mobile.plugin.keystore.PutOptions
import com.letta.mobile.plugin.keystore.SecretField
import com.letta.mobile.plugin.keystore.SecretListing
import com.letta.mobile.plugin.keystore.SecretPath
import com.letta.mobile.plugin.keystore.SecretRef
import com.letta.mobile.plugin.keystore.SecretScope
import com.letta.mobile.plugin.keystore.SecretValue
import com.letta.mobile.plugin.keystore.WriteMode
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Clock
import kotlin.time.Instant

/** The test vault's id; matches [SecretRequestTestData.TARGET]. */
internal val TEST_VAULT = KeystoreProviderId("test.vault")

/** The slot [SecretRequestTestData.TARGET] names. */
internal val TARGET_REF = SecretRef(TEST_VAULT, SecretPath("team/openai"), SecretField("api_key"))

/**
 * The policy most tests run under: a shared `team` root, so the requester and the bystander can
 * both name [SecretRequestTestData.TARGET]. The per-agent default has its own tests.
 */
internal val TEAM_SCOPE = SecretRequestPolicy(itemScope = SecretItemScope.Shared(VaultItemName("team")))

/** The human principal every answer test uses. */
internal val HUMAN = SecretRequestPrincipal(
    VerifiedEndpointId("humanendpoint01"),
    PrincipalOrigin.HUMAN_PAIRED_DEVICE,
    PrincipalAccess.ADMIN_FULL,
)

/** The requesting agent and a bystander agent. */
internal val REQUESTER = RequestingAgent(SecretRequestTestData.AGENT)
internal val BYSTANDER = RequestingAgent(SecretRequestTestData.OTHER_AGENT)

/** A value holding [text]'s UTF-8 bytes. */
internal fun secretOf(text: String): SecretValue = SecretValue.ofBytes(text.encodeToByteArray())

/** A settable clock in epoch milliseconds. */
internal open class TestClock(start: Long = SecretRequestTestData.NOW.toEpochMilliseconds()) : Clock {
    open var nowMs: Long = start

    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
}

/** Distinct, deterministic id bytes; obviously not a secure source, for tests only. */
internal class CountingRandom : SecretRandomSource {
    private var counter = 0

    override fun nextBytes(size: Int): ByteArray {
        counter++
        return ByteArray(size) { index -> (counter * 31 + index).toByte() }
    }
}

/**
 * A minimal in-memory keystore for host tests, following the keystore contract's write modes,
 * plus failure switches and observation hooks. It is the vault: the values it stores are expected
 * to be here and nowhere else.
 */
internal class TestKeystoreProvider(
    private val capabilities: Set<KeystoreCapability> = KeystoreCapability.entries.toSet(),
) : KeystoreProvider {
    private val slots = linkedMapOf<SecretRef, ByteArray>()
    private val written = mutableSetOf<SecretRef>()

    /** When set, every data operation fails with it. */
    var failWith: KeystoreError? = null

    /** When set, `delete` fails with it. */
    var failDeleteWith: KeystoreError? = null

    /** When true, `get` of a slot written through `put` answers its bytes with the last byte flipped. */
    var corruptReadBack = false

    /** When true, every data operation throws. */
    var throwOnCall = false

    /** When set, `put` waits for it before writing. */
    var putGate: CompletableDeferred<Unit>? = null

    /** Signalled when a `put` starts. */
    val putStarted = CompletableDeferred<Unit>()

    /** How many writes reached the store. */
    var writes = 0
        private set

    /** Every value `get` lent out; the caller must have wiped each. */
    val lent = mutableListOf<SecretValue>()

    override fun describe(): KeystoreDescriptor = KeystoreDescriptor(TEST_VAULT, capabilities, needsUnlock = false)

    override suspend fun health(): KeystoreHealth = KeystoreHealth(HealthStatus.READY)

    override suspend fun get(ref: SecretRef): KeystoreResult<SecretValue> = guard {
        val bytes = slots[ref] ?: return@guard KeystoreResult.Failure(KeystoreError.NotFound(ref))
        val corrupt = corruptReadBack && ref in written
        val answer = bytes.copyOf().also { if (corrupt) it[it.lastIndex] = (it.last() + 1).toByte() }
        KeystoreResult.Success(SecretValue.ofBytes(answer).also { lent += it })
    }

    override suspend fun put(ref: SecretRef, value: SecretValue, options: PutOptions): KeystoreResult<Unit> = guard {
        putStarted.complete(Unit)
        putGate?.await()
        val exists = ref in slots
        when {
            options.mode == WriteMode.CREATE && exists -> KeystoreResult.Failure(KeystoreError.Conflict(ref))
            options.mode == WriteMode.REPLACE && !exists -> KeystoreResult.Failure(KeystoreError.NotFound(ref))
            else -> KeystoreResult.Done.also { slots[ref] = value.revealSecretBytes { it.copyOf() }; written += ref; writes++ }
        }
    }

    override suspend fun list(scope: SecretScope): KeystoreResult<SecretListing> = guard {
        val items = slots.keys.map { it.item }.filter(scope::contains).distinct().sortedBy { it.path.value }
        KeystoreResult.Success(SecretListing(items, truncated = false))
    }

    override suspend fun delete(ref: SecretRef): KeystoreResult<Unit> = guard {
        failDeleteWith?.let { return@guard KeystoreResult.Failure(it) }
        val removed = slots.keys.filter { it.item == ref }
        removed.forEach(slots::remove)
        if (removed.isEmpty()) KeystoreResult.Failure(KeystoreError.NotFound(ref)) else KeystoreResult.Done
    }

    /** Stores [text] at [ref] directly, as if the owner had saved it earlier. */
    fun seed(ref: SecretRef, text: String) {
        slots[ref] = text.encodeToByteArray()
    }

    /** Removes the slot at [ref] directly, as if the owner had deleted it. */
    fun remove(ref: SecretRef) {
        slots.remove(ref)
    }

    /** The text stored at [ref], or null. */
    fun storedText(ref: SecretRef): String? = slots[ref]?.decodeToString()

    /** Every slot the store holds. */
    fun refs(): Set<SecretRef> = slots.keys.toSet()

    private suspend fun <T> guard(operation: suspend () -> KeystoreResult<T>): KeystoreResult<T> {
        check(!throwOnCall) { "provider exploded" }
        return failWith?.let { KeystoreResult.Failure(it) } ?: operation()
    }
}

/** A service over a test provider and clock with the given journal. */
internal class ServiceHarness(
    val journal: SecretRequestJournal = InMemorySecretRequestJournal(),
    val provider: TestKeystoreProvider = TestKeystoreProvider(),
    val clock: TestClock = TestClock(),
    random: SecretRandomSource = CountingRandom(),
) {
    val service = SecretRequestService(journal, provider, SecretRequestSettings(TEAM_SCOPE, clock, random))

    /** Creates a pending request for [agent] and answers its id. */
    suspend fun pending(agent: RequestingAgent = REQUESTER): SecretRequestId {
        val created = service.request(agent, SecretRequestTestData.request()) as SecretServiceResult.Ok
        return created.value.requestId
    }
}
