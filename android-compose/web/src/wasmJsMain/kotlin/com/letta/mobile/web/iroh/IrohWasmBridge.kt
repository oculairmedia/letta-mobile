package com.letta.mobile.web.iroh

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ParsedIrohTicket(
    val nodeId: String,
    val publicKeyValid: Boolean,
    val directAddr: String? = null,
    val relayUrl: String? = null,
)

/**
 * Pure Kotlin / Wasm ticket parser & validator mirroring the Rust native/iroh-wasm crate.
 */
object IrohWasmBridge {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parse an Iroh ticket or URL into its cryptographic Node ID and direct endpoint.
     */
    fun parseTicket(ticketOrUrl: String): ParsedIrohTicket {
        val trimmed = ticketOrUrl.trim()
        val body = if (trimmed.startsWith("iroh://")) {
            trimmed.removePrefix("iroh://")
        } else {
            trimmed
        }

        val nodePart: String
        val addrPart: String?

        if (body.contains("@")) {
            nodePart = body.substringBefore("@")
            addrPart = body.substringAfter("@").takeIf { it.isNotBlank() }
        } else {
            nodePart = body
            addrPart = null
        }

        // Validate Iroh Ed25519 public key hex format (64 hex characters) or base32
        val isValidHex = nodePart.length == 64 && nodePart.all { it in "0123456789abcdefABCDEF" }
        val isValidBase32 = nodePart.length == 52 || nodePart.length == 59

        return ParsedIrohTicket(
            nodeId = nodePart,
            publicKeyValid = isValidHex || isValidBase32,
            directAddr = addrPart,
            relayUrl = null,
        )
    }

    /**
     * Send an RPC command over Iroh 1.0 P2P (via Relays) to a target Node ID.
     */
    suspend fun dialAndSend(targetNodeId: String, alpn: String, payload: String): String {
        return try {
            val promise = callIrohDialAndSend(targetNodeId, alpn, payload)
            val res = promise.awaitPromise()
            res.toString()
        } catch (e: Throwable) {
            println("Iroh dialAndSend error: ${e.message}")
            throw e
        }
    }
}

private fun callIrohDialAndSend(target: String, alpn: String, payload: String): kotlin.js.Promise<JsString> =
    js("window.irohDialAndSend(target, alpn, payload)")

private suspend fun <T : JsAny?> kotlin.js.Promise<T>.awaitPromise(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        this.then(
            onFulfilled = { value ->
                cont.resumeWith(Result.success(value))
                null
            },
            onRejected = { reason ->
                cont.resumeWith(Result.failure(RuntimeException(reason.toString())))
                null
            }
        )
    }

