package com.letta.mobile.data.plugin

import com.letta.mobile.data.plugin.PluginRegistryFixtures.json
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryDelegate
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Secrets and substitution (plan sections 3.1 and 3.4): write-only values that never print,
 * `${settings.*}`/`${secrets.*}` resolved on the host only, and a scrubber that fails closed on a
 * secret or its base64 in any plugin output.
 */
class PluginSecretsTest {
    private val token = "s3cr3t-T0ken-value"
    private val secrets = PluginSecrets().with("apiToken", token)
    private val settings = json("""{"baseUrl":"https://api.example.test","quality":80}""").jsonObject
    private val logged = mutableListOf<String>()

    @BeforeTest
    fun captureLogs() {
        Telemetry.delegate = object : TelemetryDelegate {
            override fun logToLogcat(level: Telemetry.Level, tag: String, body: String, throwable: Throwable?) {
                logged += "$tag $body ${throwable?.message.orEmpty()}"
            }
            override fun isLoggable(tag: String, level: Int): Boolean = true
            override fun isTraceEnabled(): Boolean = false
            override fun beginSection(name: String) = Unit
            override fun endSection() = Unit
            override fun beginAsyncSection(name: String, cookie: Int) = Unit
            override fun endAsyncSection(name: String, cookie: Int) = Unit
        }
    }

    @AfterTest
    fun releaseLogs() {
        Telemetry.delegate = null
        assertTrue(logged.none { it.contains(token) }, "a secret reached the log: $logged")
    }

    @Test
    fun aSecretNeverPrints() {
        assertEquals("<redacted>", secrets["apiToken"].toString())
        assertFalse(secrets.toString().contains(token), secrets.toString())
        assertEquals("PluginSecrets(apiToken)", secrets.toString())
        assertFalse(secrets.scrubber().toString().contains(token))
    }

    @Test
    fun theStatusSaysSetOrUnsetForEveryDeclaredSecret() {
        val manifest = PluginRegistryFixtures.manifest("/secrets/1" to json("""{"name":"other"}"""))
        assertEquals(mapOf("apiToken" to SecretState.SET, "other" to SecretState.UNSET), secrets.with("stray", "x").status(manifest))
        assertEquals(SecretState.UNSET, secrets.without("apiToken").status(manifest).getValue("apiToken"))
    }

    @Test
    fun envAndHeadersResolveSettingsAndSecretsOnTheHost() {
        val templates = mapOf("EXAMPLE_URL" to "\${settings.baseUrl}/v1", "EXAMPLE_Q" to "\${settings.quality}", "EXAMPLE_TOKEN" to "Bearer \${secrets.apiToken}")
        val resolved = assertIs<TemplateResolution.Resolved>(PluginTemplates.resolve(templates, settings, secrets)).values
        assertEquals("https://api.example.test/v1", resolved["EXAMPLE_URL"])
        assertEquals("80", resolved["EXAMPLE_Q"])
        assertEquals("Bearer $token", resolved.reveal().getValue("EXAMPLE_TOKEN"))
        assertFalse(resolved.toString().contains(token), resolved.toString())
        assertFalse(TemplateResolution.Resolved(resolved).toString().contains(token))
    }

    @Test
    fun aReferenceWithNoValueRefusesTheWholeSetWithoutAnyValue() {
        val templates = mapOf("A" to "\${secrets.missing}", "B" to "\${settings.nope}", "C" to "\${secrets.apiToken}")
        val refused = assertIs<TemplateResolution.Refused>(PluginTemplates.resolve(templates, settings, secrets))
        assertEquals(listOf("A: \${secrets.missing} has no value", "B: \${settings.nope} has no value"), refused.problems)
        assertFalse(refused.toString().contains(token))
    }

    @Test
    fun theScanTellsReferencesFromMalformedTokens() {
        val scan = PluginTemplates.scan("\${settings.a}-\${secrets.b}-\${config.c}-\${open")
        assertEquals(listOf(TemplateRef(TemplateNamespace.SETTINGS, "a"), TemplateRef(TemplateNamespace.SECRETS, "b")), scan.refs)
        assertEquals(listOf("\${config.c}", "\${"), scan.malformed)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun theScrubberFailsClosedOnASecretOrItsBase64() {
        val scrubber = secrets.scrubber()
        val bytes = token.encodeToByteArray()
        listOf(
            "token is $token",
            "basic ${Base64.encode(bytes)}",
            "nopad ${Base64.encode(bytes).trimEnd('=')}",
            "url ${Base64.UrlSafe.encode(bytes)}",
        ).forEach { leak ->
            assertTrue(scrubber.leaks(leak), leak)
            assertEquals(SecretScrubber.REFUSED, scrubber.scrub(leak))
        }
        assertEquals("all clear", scrubber.scrub("all clear"))
        assertEquals(mapOf("a" to "fine", "b" to SecretScrubber.REFUSED), scrubber.scrub(mapOf("a" to "fine", "b" to token)))
    }

    @Test
    fun anEmptySecretScrubsNothing() {
        assertEquals("anything", SecretScrubber(listOf("")).scrub("anything"))
    }
}
