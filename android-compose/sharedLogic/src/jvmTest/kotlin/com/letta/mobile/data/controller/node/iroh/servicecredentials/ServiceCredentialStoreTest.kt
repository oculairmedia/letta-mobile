package com.letta.mobile.data.controller.node.iroh.servicecredentials

import com.letta.mobile.data.controller.node.iroh.hostWireJson
import com.letta.mobile.data.rpcwire.SecretWriteValue
import com.letta.mobile.data.servicecredentials.ServiceCredentialErrorCode
import com.letta.mobile.data.servicecredentials.ServiceCredentialList
import com.letta.mobile.data.servicecredentials.ServiceCredentialSetCommand
import com.letta.mobile.data.servicecredentials.ServiceId
import com.letta.mobile.data.servicecredentials.ServiceSecretChange
import com.letta.mobile.data.servicecredentials.ServiceTestState
import com.letta.mobile.data.servicecredentials.ServiceTestStatus
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ServiceCredentialStoreTest {
    private val serviceId = ServiceId("example-service")
    private val adapter = object : ServiceAdapter {
        override val descriptor = ServiceDescriptor(serviceId, "Example", "A test service", null, baseUrlEditable = true, secretLabel = "Token")

        override suspend fun test(target: ServiceTarget): ServiceCredentialErrorCode? = null
    }

    private fun store(
        persistence: ServiceCredentialPersistence = InMemoryServiceCredentialPersistence(),
        secrets: ServiceSecretStore = InMemoryServiceSecretStore(),
    ) = ServiceCredentialStore(listOf(adapter), persistence, secrets)

    private fun write(url: String, secret: ServiceSecretChange = ServiceSecretChange.Keep, revision: String? = null) =
        ServiceCredentialSetCommand(serviceId, url, secret, expectedRevision = revision)

    private fun replace(value: String = SECRET) = ServiceSecretChange.Replace(SecretWriteValue(value))

    @Test
    fun aSecretNeverFollowsTheAddressToANewOrigin() = runTest {
        val store = store()
        store.write(write("https://one.example.invalid", replace()))
        val moved = store.write(write("https://two.example.invalid"))
        assertFalse(moved.hasSecret)
        val refused = assertFailsWith<ServiceRefusal> { store.snapshot(serviceId) }
        assertEquals(ServiceCredentialErrorCode.NOT_CONFIGURED, refused.code)
    }

    @Test
    fun aWriteOnTheSameOriginKeepsTheSecretAndResetsTheTest() = runTest {
        val store = store()
        val first = store.write(write("https://one.example.invalid", replace()))
        store.recordTest(serviceId, 1, ServiceTestStatus(ServiceTestState.PASSED, 1L))
        val second = store.write(write("https://one.example.invalid/v2", revision = first.revision))
        assertTrue(second.hasSecret)
        assertEquals(ServiceTestState.UNKNOWN, second.lastTest.state)
        assertEquals(SECRET, store.snapshot(serviceId).target.secret)
    }

    @Test
    fun aSecretTravelsOnlyOverHttpsOrToLoopback() = runTest {
        val store = store()
        val refused = assertFailsWith<ServiceRefusal> { store.write(write("http://lan-host.example.invalid", replace())) }
        assertEquals(ServiceCredentialErrorCode.INSECURE_TRANSPORT, refused.code)
        assertTrue(store.write(write("http://127.0.0.1:9000", replace())).hasSecret)
        assertTrue(store.write(write("http://localhost:9000", replace())).hasSecret)
    }

    @Test
    fun addressesWithCredentialsOrQueriesAreRefused() = runTest {
        val store = store()
        listOf("https://user:pw@one.example.invalid", "https://one.example.invalid/?key=x", "ftp://one.example.invalid").forEach { url ->
            assertEquals(ServiceCredentialErrorCode.INVALID_REQUEST, assertFailsWith<ServiceRefusal> { store.write(write(url, replace())) }.code)
        }
    }

    @Test
    fun aTestIsRecordedOnlyAgainstTheRevisionItTested() = runTest {
        val store = store()
        store.write(write("https://one.example.invalid", replace()))
        store.write(write("https://one.example.invalid"))
        val status = store.recordTest(serviceId, 1, ServiceTestStatus(ServiceTestState.PASSED, 1L))
        assertEquals(ServiceTestState.UNKNOWN, status.lastTest.state)
    }

    @Test
    fun aFailedSettingsWriteRestoresTheEarlierSecret() = runTest {
        val secrets = InMemoryServiceSecretStore()
        val persistence = FailingPersistence()
        val store = store(persistence, secrets)
        store.write(write("https://one.example.invalid", replace("earlier")))
        persistence.failNext = true
        assertFailsWith<IllegalStateException> { store.write(write("https://one.example.invalid", replace("later"))) }
        assertEquals("earlier", store.snapshot(serviceId).target.secret)
    }

    @Test
    fun theListNeverCarriesTheSecret() = runTest {
        val store = store()
        store.write(write("https://one.example.invalid", replace()))
        val wire = hostWireJson(ServiceCredentialList.serializer(), ServiceCredentialList(services = store.list())).toString()
        assertFalse(SECRET in wire)
        assertTrue("\"has_secret\":true" in wire)
    }

    @Test
    fun removalDeletesTheSecretAndKeepsCountingRevisions() = runTest {
        val store = store()
        val written = store.write(write("https://one.example.invalid", replace()))
        val removed = store.remove(serviceId, written.revision)
        assertFalse(removed.configured)
        assertFalse(removed.hasSecret)
        assertEquals("rev-3", store.write(write("https://one.example.invalid")).revision)
    }

    @Test
    fun theSecretFileHoldsOnlySecretsAndTheSettingsFileNone() = runTest {
        val dir = createTempDirectory("service-credentials")
        val settings = dir.resolve("service-credentials.json")
        val secretFile = dir.resolve("service-credential-secrets")
        val store = store(FileServiceCredentialPersistence(settings), FileServiceSecretStore(secretFile))
        store.write(write("https://one.example.invalid", replace()))
        assertFalse(SECRET in settings.readText())
        assertTrue(SECRET in secretFile.readText())
        store.remove(serviceId, null)
        assertFalse(secretFile.toFile().exists())
    }

    private class FailingPersistence : ServiceCredentialPersistence {
        private val inner = InMemoryServiceCredentialPersistence()
        var failNext = false

        override fun read() = inner.read()

        override fun write(state: ServiceCredentialState) {
            check(!failNext) { "disk full" }
            inner.write(state)
        }
    }

    private companion object {
        const val SECRET = "placeholder-secret-not-real"
    }
}
