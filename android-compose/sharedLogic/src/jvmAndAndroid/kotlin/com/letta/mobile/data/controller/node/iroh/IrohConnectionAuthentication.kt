package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy

/**
 * Authentication dependencies for one peer. Endpoints share the verifier across connections so redials cannot
 * reset its failure budget.
 */
class IrohConnectionAuthentication(
    /**
     * REQUIRED authentication policy. Connections only start
     * pre-authenticated when the policy explicitly says so (anonymous
     * test-only mode or an already-vetted peer allowlist) — never because a
     * parameter was omitted.
     */
    val authPolicy: IrohAuthPolicy,
    /**
     * Bearer verifier: constant-time comparison + per-NodeId rate
     * limiting. Endpoints pass ONE shared instance to every connection so
     * redialing cannot reset a peer's failure budget.
     */
    val authVerifier: IrohBearerAuthVerifier = IrohBearerAuthVerifier(authPolicy),
    /**
     * Pairing service: peers whose NodeId is already paired
     * authenticate by transport identity alone; unpaired peers may redeem a
     * one-time invite via an `invite:<secret>` auth token. Null disables
     * pairing entirely.
     */
    val pairingService: IrohPairingService? = null,
    /** Transport-authenticated peer identity used for pairing, verification and revocation. */
    val remoteEndpointId: String = "",
)
