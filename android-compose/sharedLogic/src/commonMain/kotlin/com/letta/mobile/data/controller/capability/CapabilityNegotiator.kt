package com.letta.mobile.data.controller.capability

/**
 * Capability negotiation layer for App Server connections.
 *
 * This negotiator determines which capabilities are available from a remote App Server
 * endpoint, defaulting to FACTORY-COMPATIBLE (baseline only) and progressively enhancing
 * only when an extended (Meridian) controller advertises extras.
 *
 * NEGOTIATION FLOW:
 * 1. Query the advertiser for the set of capabilities advertised by the endpoint
 * 2. If empty/unknown => baseline-only safe default (factory-compatible)
 * 3. If non-empty => enable the advertised extras
 *
 * SAFETY:
 * - Default-safe: unknown/absent advertisement => baseline only (all extras false)
 * - Factory endpoint => baseline only (works against ANY stock 'letta app-server')
 * - Extended endpoint => extras lit per what it advertises
 *
 * USAGE:
 * ```kotlin
 * val negotiator = CapabilityNegotiator(advertiser = myAdvertiser)
 * val capabilities = negotiator.negotiate()
 *
 * if (capabilities.has(Capability.ImageHydration)) {
 *     // Use image hydration
 * }
 * ```
 */
class CapabilityNegotiator(
    private val advertiser: CapabilityAdvertiser,
) {
    /**
     * Negotiate capabilities with the remote endpoint.
     *
     * Queries the advertiser and returns the negotiated capabilities.
     * If the advertiser returns an empty set, defaults to factory-compatible baseline.
     *
     * @return Negotiated capabilities
     */
    suspend fun negotiate(): RemoteCapabilities {
        val advertised = advertiser.advertise()

        // Empty/unknown advertisement => baseline-only safe default
        if (advertised.isEmpty()) {
            return RemoteCapabilities.FACTORY_DEFAULT
        }

        // Non-empty => parse and enable advertised extras
        return RemoteCapabilities.fromAdvertised(advertised)
    }

    companion object {
        /**
         * Create a negotiator for a factory-default endpoint (baseline only).
         */
        fun factoryDefault(): CapabilityNegotiator {
            return CapabilityNegotiator(FactoryDefaultAdvertiser())
        }

        /**
         * Create a negotiator for an extended (Meridian) endpoint with all extras.
         */
        fun extended(): CapabilityNegotiator {
            return CapabilityNegotiator(ExtendedCapabilityAdvertiser())
        }

        /**
         * Create a negotiator for an extended endpoint with specific extras.
         *
         * @param extras Set of extra capability strings to advertise
         */
        fun extended(extras: Set<String>): CapabilityNegotiator {
            return CapabilityNegotiator(ExtendedCapabilityAdvertiser(extras))
        }

        /**
         * Create a negotiator with a custom advertiser.
         *
         * @param advertiser Custom capability advertiser
         */
        fun custom(advertiser: CapabilityAdvertiser): CapabilityNegotiator {
            return CapabilityNegotiator(advertiser)
        }
    }
}
