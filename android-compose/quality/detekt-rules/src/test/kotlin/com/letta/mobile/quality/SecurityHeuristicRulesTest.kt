package com.letta.mobile.quality

import io.gitlab.arturbosch.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SecurityHeuristicRulesTest {
    @Test
    fun `string matching and path concatenation on hosts and urls is reported`() {
        val code = """
            fun a(url: String) = url.startsWith("https://")
            fun b(config: Config) = config.baseUrl.endsWith("/")
            fun c(origin: String) = origin.lowercase().contains("localhost")
            fun d(serverHost: String) = serverHost.split(":")
            fun e(endpoint: String) = Regex("^wss?://").containsMatchIn(endpoint)
            fun f(uri: String) = "letta.com" in uri
            fun g(baseUrl: String) = "${'$'}baseUrl/v1/agents"
            fun h(baseUrl: String) = baseUrl + "/v1/agents"
        """.trimIndent()
        assertEquals(8, HostOrUrlStringMatching().lint(code).size)
    }

    @Test
    fun `component comparisons, display text and unrelated names pass`(@TempDir root: File) {
        val code = """
            fun a(url: Url) = url.protocol.name == "https"
            fun b(urls: List<String>, value: String) = urls.contains(value)
            fun c(hostname: String) = "Connected to ${'$'}hostname"
            fun d(name: String) = name.startsWith("https")
            fun e(host: String, port: Int) = "${'$'}host:${'$'}port"
        """.trimIndent()
        assertEquals(0, HostOrUrlStringMatching().lint(code).size)
        val test = """fun a(url: String) = url.startsWith("https://")"""
        assertEquals(0, HostOrUrlStringMatching().lintAt(root, "app/src/test/kotlin/A.kt", test).size)
    }

    @Test
    fun `data classes printing credentials are reported`() {
        val code = """
            data class Login(val user: String, val password: String)
            data class Session(val accessToken: String?, val refresh_token: String)
            data class Key(val apiKey: CharArray)
        """.trimIndent()
        assertEquals(4, SecretBearingDataClass().lint(code).size)
    }

    @Test
    fun `a value in a secrets package is a credential, elsewhere it is not`() {
        val secrets = "package com.letta.mobile.data.secrets\ndata class SecretValue(val value: String)\n"
        val other = "package com.letta.mobile.data.model\ndata class Label(val value: String)\n"
        assertEquals(1, SecretBearingDataClass().lint(secrets).size)
        assertEquals(0, SecretBearingDataClass().lint(other).size)
    }

    @Test
    fun `counts, pagination, references, plain classes and redacted toString pass`() {
        val code = """
            data class Usage(val maxTokens: Int, val promptTokens: Int, val tokenCount: String)
            data class Page(val nextPageToken: String)
            data class Ref(val secretName: String, val passwordHint: String)
            class Plain(val token: String)
            data class Redacted(val token: String) { override fun toString() = "Redacted(token=***)" }
        """.trimIndent()
        assertEquals(0, SecretBearingDataClass().lint(code).size)
    }
}
