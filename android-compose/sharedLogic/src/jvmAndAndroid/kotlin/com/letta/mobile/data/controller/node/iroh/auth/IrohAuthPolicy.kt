package com.letta.mobile.data.controller.node.iroh.auth

/**
 * Explicit authentication policy for an Iroh server endpoint.
 *
 * Production constructors ([com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint],
 * [com.letta.mobile.data.controller.node.iroh.IrohNodeConnection]) REQUIRE a
 * policy — there is no default. Anonymous/open operation is only reachable
 * through the loudly named [InsecureAnonymousForTestOnly] value, so a caller
 * can never fail open by forgetting a parameter.
 */
sealed interface IrohAuthPolicy {
    /** Bearer token every peer must present in its `auth` frame, or null. */
    val requiredBearerToken: String?

    /** NodeIds allowed to connect; empty means no peer-identity restriction. */
    val allowedPeerIds: Set<String>

    /**
     * Whether a connection from [remoteId] is authenticated before it sends an
     * auth frame. Only the explicit anonymous test mode
     * and an operator-vetted static allowlist pre-authenticate; a bearer token,
     * pairing, or anything else must be proven in the auth frame. This replaces
     * the old "no token configured => authenticated" shortcut, which became a
     * fail-open once unknown peers could connect to redeem invites.
     */
    fun preAuthenticates(remoteId: String): Boolean

    /**
     * Require a bearer token (optionally combined with a peer allowlist).
     * The token must be non-blank and at least [MIN_TOKEN_LENGTH] characters —
     * rotated credentials are expected to be >= 256-bit random values.
     */
    data class BearerToken(
        /** Secret bootstrap credential required in the peer's auth frame; never include it in logs. */
        val token: String,
        override val allowedPeerIds: Set<String> = emptySet(),
    ) : IrohAuthPolicy {
        init {
            require(token.isNotBlank()) { "IrohAuthPolicy.BearerToken requires a non-blank token" }
            require(token.length >= MIN_TOKEN_LENGTH) {
                "IrohAuthPolicy.BearerToken requires a token of at least $MIN_TOKEN_LENGTH characters; " +
                    "generate a fresh >=256-bit random credential"
            }
        }

        override val requiredBearerToken: String get() = token

        override fun preAuthenticates(remoteId: String): Boolean = false

        /** Never leak the credential through logs/toString. */
        override fun toString(): String =
            "BearerToken(token=<redacted>, allowedPeerIds=${allowedPeerIds.size})"
    }

    /** Restrict connections to an explicit NodeId allowlist without a token. */
    data class PeerAllowlist(
        override val allowedPeerIds: Set<String>,
    ) : IrohAuthPolicy {
        init {
            require(allowedPeerIds.isNotEmpty()) {
                "IrohAuthPolicy.PeerAllowlist requires at least one peer id; " +
                    "use InsecureAnonymousForTestOnly if you really want an open endpoint"
            }
        }

        override val requiredBearerToken: String? get() = null

        override fun preAuthenticates(remoteId: String): Boolean = remoteId in allowedPeerIds
    }

    /**
     * Fail-closed pairing-only endpoint: no bootstrap
     * credential is accepted at all. A peer authenticates only by a NodeId that
     * is present in the paired-peer store, or by redeeming a one-time invite in
     * its auth frame. Knowing the dial address grants nothing.
     */
    data object PairedPeersOnly : IrohAuthPolicy {
        override val requiredBearerToken: String? get() = null
        override val allowedPeerIds: Set<String> get() = emptySet()

        override fun preAuthenticates(remoteId: String): Boolean = false
    }

    /**
     * TEST/DEV ONLY: anonymous, open endpoint. Every peer that can dial the
     * ticket gets full runtime and admin access. Never use in a release build
     * or long-running service; the serve CLI only selects this behind the
     * explicit `--allow-insecure-anonymous-iroh` flag and warns on every start.
     */
    data object InsecureAnonymousForTestOnly : IrohAuthPolicy {
        override val requiredBearerToken: String? get() = null
        override val allowedPeerIds: Set<String> get() = emptySet()

        override fun preAuthenticates(remoteId: String): Boolean = true
    }

    /** Validates operator configuration and selects an explicit endpoint authentication policy. */
    companion object {
        /** Minimum accepted bearer credential length, measured in characters. */
        const val MIN_TOKEN_LENGTH = 16

        /**
         * Resolves CLI/service configuration into a policy, fail-closed:
         * no token, no allowlist, no pairing store, and no explicit insecure
         * opt-in refuses to produce a policy at all. A configured pairing store
         * on its own is a secure configuration ([PairedPeersOnly]): the bearer
         * token is an optional bootstrap allowance, not a requirement.
         */
        fun resolve(
            authToken: String?,
            allowedPeerIds: Set<String>,
            allowInsecureAnonymous: Boolean,
            pairingEnabled: Boolean = false,
        ): IrohAuthPolicyResolution {
            val token = authToken?.takeIf { it.isNotBlank() }
            return when {
                token != null -> {
                    if (token.length < MIN_TOKEN_LENGTH) {
                        IrohAuthPolicyResolution.Refused(
                            "Configured Iroh auth token is shorter than $MIN_TOKEN_LENGTH characters. " +
                                "Generate a fresh >=256-bit random credential (e.g. openssl rand -hex 32).",
                        )
                    } else {
                        IrohAuthPolicyResolution.Secure(BearerToken(token, allowedPeerIds))
                    }
                }
                allowedPeerIds.isNotEmpty() ->
                    IrohAuthPolicyResolution.Secure(PeerAllowlist(allowedPeerIds))
                pairingEnabled -> IrohAuthPolicyResolution.Secure(PairedPeersOnly)
                allowInsecureAnonymous -> IrohAuthPolicyResolution.InsecureAccepted(
                    policy = InsecureAnonymousForTestOnly,
                    warning = "WARNING: --allow-insecure-anonymous-iroh is set. This Iroh endpoint accepts " +
                        "UNAUTHENTICATED connections with full runtime and admin access. " +
                        "Never run this mode as a release or long-lived service.",
                )
                else -> IrohAuthPolicyResolution.Refused(
                    "Refusing to start an anonymous Iroh endpoint. Configure --pairing-store-file " +
                        "(env LETTA_IROH_PAIRING_STORE), --auth-token (env LETTA_IROH_AUTH_TOKEN) " +
                        "and/or --allowed-peer-ids (env LETTA_IROH_ALLOWED_PEER_IDS), or pass " +
                        "--allow-insecure-anonymous-iroh for test/dev use only.",
                )
            }
        }
    }
}

/** Outcome of resolving operator configuration into an [IrohAuthPolicy]. */
sealed interface IrohAuthPolicyResolution {
    /**
     * Configuration resolved to bearer, allowlist or pairing-only access without anonymous opt-in.
     * @property policy The policy hosts must enforce on every accepted App Server connection.
     */
    data class Secure(val policy: IrohAuthPolicy) : IrohAuthPolicyResolution

    /** Explicit anonymous test mode; hosts must show [warning] before starting the endpoint. */
    data class InsecureAccepted(
        /** Anonymous policy selected only through the operator's insecure opt-in. */
        val policy: IrohAuthPolicy,
        /** Startup warning explaining that this endpoint grants unauthenticated access. */
        val warning: String,
    ) : IrohAuthPolicyResolution

    /**
     * Unsafe or invalid configuration for which the host must refuse to start an endpoint.
     * @property error Operator-facing explanation of the missing or invalid authentication settings.
     */
    data class Refused(val error: String) : IrohAuthPolicyResolution
}
