package com.letta.mobile.data.controller.node.iroh.errors

/**
 * An admin RPC failure whose message is written for the remote client.
 *
 * The admin router answers with the message of this type only. Every other exception a handler
 * lets escape answers one fixed message, so host paths, record state and other internal detail
 * never leave the host. Raise it for deliberate, safe refusals: validation, capability and
 * not-found answers built from the caller's own request.
 *
 * It extends [IllegalArgumentException] so handlers that rethrow refusals with
 * `catch (e: IllegalArgumentException)` keep passing it through unchanged.
 */
internal class AdminRpcClientError(message: String) : IllegalArgumentException(message)
