package com.letta.mobile.cli.runtime

import com.github.ajalt.clikt.core.UsageError
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CliRestTransportTest {
    @Test
    fun `executeJsonRestRequest rejects GET bodies before sending`() {
        val client = cliHttpClient()
        try {
            assertThrows(UsageError::class.java) {
                runBlocking {
                    client.executeJsonRestRequest(
                        verb = "GET",
                        url = "https://example.invalid/v1/agents",
                        token = "token",
                        body = "{}",
                    )
                }
            }
        } finally {
            client.close()
        }
    }
}
