package com.letta.mobile.data.secrets.wire

/**
 * The admin RPC wire of the secret request routes (`docs/reference/contracts/secret-request-v1.md`,
 * section Routes): the host capability, the peer capabilities that admit each side, the params
 * fields. A leaf: it imports nothing, so the admin RPC core can read it without an import cycle; the
 * method names equal `SecretRequestMethods` (checked by a test) and a refusal's text is spelled by
 * `SecretRefusalText`.
 */
object SecretRequestRouteWire {
    /** Host capability, advertised only when every route is registered. */
    const val CAPABILITY: String = "secret.request.v1"

    /**
     * Peer capability that admits the human side (list, get, submit, cancel) at the connection's
     * capability gate. The handler still requires a paired device holding `admin.full`.
     */
    const val ANSWER_CAPABILITY: String = "secret.request.answer"

    /**
     * Peer capability that admits the agent side (request, status) at the connection's gate. No
     * admin RPC connection is ever an agent, so the handler refuses every connection caller.
     */
    const val AGENT_CAPABILITY: String = "secret.request.agent"

    /** The agent side: create a request and poll its receipt. */
    val AGENT_METHODS: Set<String> = setOf("secret.request", "secret.request.status")

    /** The human side: list, read, answer and decline. */
    val HUMAN_METHODS: Set<String> = setOf(
        "secret.request.list",
        "secret.request.get",
        "secret.request.submit",
        "secret.request.cancel",
    )

    /** Every route, registered together or not at all. */
    val METHODS: Set<String> = AGENT_METHODS + HUMAN_METHODS

    /** Params field: the agent-authored purpose. */
    const val PURPOSE: String = "purpose"

    /** Params field: the item that will receive the secret. */
    const val VAULT_ITEM_NAME: String = "vaultItemName"

    /** Params field: the field of that item. */
    const val FIELD_NAME: String = "fieldName"

    /** Params field: the request a call names. */
    const val REQUEST_ID: String = "requestId"

    /** Params field of `secret.request.submit` only: the base64 value ([SecretValueBase64]). */
    const val VALUE: String = "value"
}
