package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.node.IrohRelayConfig
import com.letta.mobile.data.controller.node.iroh.IrohEndpointBinding
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import computer.iroh.presetMinimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/*
 * The live Iroh suites dial real QUIC on this machine and nothing else: no relay, no address
 * lookup, no internet. A host binds with relays disabled and advertises its direct addresses in
 * its ticket; a test-owned endpoint binds the minimal preset (no n0 address lookup) with relays
 * disabled, and is only dialed once it reports a direct address, so a dial never falls back to a
 * lookup that needs the internet.
 */

/** The binding of a host [com.letta.mobile.data.controller.node.iroh.IrohNodeEndpoint] in a loopback suite. */
internal fun loopbackHostBinding(): IrohEndpointBinding = IrohEndpointBinding(relayConfig = IrohRelayConfig.Disabled)

/** Binds a relay-less, lookup-less endpoint answering [alpns], once it has a direct address. */
internal suspend fun bindLoopbackEndpoint(
    alpns: List<ByteArray> = emptyList(),
    secretKey: ByteArray? = null,
): Endpoint = Endpoint.bind(
    EndpointOptions(preset = presetMinimal(), relayMode = RelayMode.disabled(), alpns = alpns, secretKey = secretKey),
).also { awaitDirectAddresses(it) }

/** The direct addresses [endpoint] reports, waiting until it reports one. */
internal suspend fun awaitDirectAddresses(endpoint: Endpoint): List<String> = withTimeout(10.seconds) {
    var direct = endpoint.addr().directAddresses()
    while (direct.isEmpty()) {
        delay(50.milliseconds)
        direct = endpoint.addr().directAddresses()
    }
    direct
}
