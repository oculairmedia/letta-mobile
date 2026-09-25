package com.letta.mobile.data.repository.modelcontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderConnectFormTest {
    private val providers = ModelControlWire.providers(ModelControlFixtures.providerList)
    private val bedrock = providers.first { it.id == "amazon-bedrock" }
    private val lmstudio = providers.first { it.id == "lmstudio" }

    @Test
    fun requiredFieldsGateSubmission() {
        val form = ProviderConnectForm(bedrock)
            .withValue("accessKey", "AKIA")
            .withValue("apiKey", "fixture-secret")

        assertFalse(form.canSubmit)
        assertEquals(listOf("region"), form.missingRequired.map { it.key })
        assertTrue(form.withValue("region", "us-east-1").canSubmit)
    }

    @Test
    fun switchingMethodKeepsOnlySharedKeys() {
        val form = ProviderConnectForm(bedrock)
            .withValue("apiKey", "fixture-secret")
            .withValue("region", "us-east-1")
            .withMethod(1)

        assertEquals("profile", form.method?.id)
        assertEquals(mapOf("region" to "us-east-1"), form.values)
    }

    @Test
    fun blankOptionalFieldsAreOmittedAndValuesTrimmed() {
        val request = ProviderConnectForm(lmstudio).withValue("baseUrl", " http://127.0.0.1:1234/v1 ").toRequest()

        assertEquals(null, request.authMethodId)
        assertEquals(mapOf("baseUrl" to "http://127.0.0.1:1234/v1"), request.fields)
    }

    @Test
    fun oauthProvidersCannotSubmitAndSecretsStayOutOfToString() {
        val oauth = providers.first { it.isOauth }
        val form = ProviderConnectForm(bedrock).withValue("apiKey", "fixture-secret")

        assertFalse(ProviderConnectForm(oauth).canSubmit)
        assertFalse(form.toString().contains("fixture-secret"))
    }
}
