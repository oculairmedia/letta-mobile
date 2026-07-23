package com.letta.mobile.data.controller.node.iroh

/**
 * Explicit authentication policy for an Iroh server endpoint
 * (letta-mobile-d6e8g.2).
 *
 * Production constructors ([IrohNodeEndpoint], [IrohNodeConnection]) REQUIRE a
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
     * Require a bearer token (optionally combined with a peer allowlist).
     * The token must be non-blank and at least [MIN_TOKEN_LENGTH] characters —
     * rotated credentials are expected to be >= 256-bit random values.
     */
    data class BearerToken(
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
    }

    companion object {
        const val MIN_TOKEN_LENGTH = 16

        /**
         * Resolves CLI/service configuration into a policy, fail-closed:
         * no token, no allowlist, and no explicit insecure opt-in refuses to
         * produce a policy at all.
         */
        fun resolve(
            authToken: String?,
            allowedPeerIds: Set<String>,
            allowInsecureAnonymous: Boolean,
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
                allowInsecureAnonymous -> IrohAuthPolicyResolution.InsecureAccepted(
                    policy = InsecureAnonymousForTestOnly,
                    warning = "WARNING: --allow-insecure-anonymous-iroh is set. This Iroh endpoint accepts " +
                        "UNAUTHENTICATED connections with full runtime and admin access. " +
                        "Never run this mode as a release or long-lived service.",
                )
                else -> IrohAuthPolicyResolution.Refused(
                    "Refusing to start an anonymous Iroh endpoint. Configure --auth-token " +
                        "(env LETTA_IROH_AUTH_TOKEN) and/or --allowed-peer-ids " +
                        "(env LETTA_IROH_ALLOWED_PEER_IDS), or pass --allow-insecure-anonymous-iroh " +
                        "for test/dev use only.",
                )
            }
        }
    }
}

/** Outcome of resolving operator configuration into an [IrohAuthPolicy]. */
sealed interface IrohAuthPolicyResolution {
    data class Secure(val policy: IrohAuthPolicy) : IrohAuthPolicyResolution

    data class InsecureAccepted(
        val policy: IrohAuthPolicy,
        val warning: String,
    ) : IrohAuthPolicyResolution

    data class Refused(val error: String) : IrohAuthPolicyResolution
}
