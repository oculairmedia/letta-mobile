# Module :plugin-api

This module defines the Kotlin contracts and serialized messages used to integrate canvas plugins
with a Meridian host. Start with [CanvasPlugin](https://oculairmedia.github.io/letta-mobile/api/plugin-api/com.letta.mobile.plugin.api/-canvas-plugin/index.html) for lifecycle and action handling, [PluginHost](https://oculairmedia.github.io/letta-mobile/api/plugin-api/com.letta.mobile.plugin.api/-plugin-host/index.html)
for host-provided services, and [LcpMethod](https://oculairmedia.github.io/letta-mobile/api/plugin-api/com.letta.mobile.plugin.api/-lcp-method/index.html) for the corresponding wire methods. Declarations
describe the protocol surface; they do not imply that every runtime or optional capability is
enabled by the current host. The process-plugin path is implemented in `sharedLogic`, while the
in-process JAR SPI remains a contract for extension authors.

# Package com.letta.mobile.plugin.api

Plugin lifecycle, actions, element events, capability names and JSON-RPC messages share one typed
contract here, with [CanvasPlugin](https://oculairmedia.github.io/letta-mobile/api/plugin-api/com.letta.mobile.plugin.api/-canvas-plugin/index.html) as the plugin extension point and [PluginHost](https://oculairmedia.github.io/letta-mobile/api/plugin-api/com.letta.mobile.plugin.api/-plugin-host/index.html) as the host-service
boundary. Implementations must not treat declared capabilities or code loading as a security
sandbox or assume every optional host service is enabled. Use [LcpMethod](https://oculairmedia.github.io/letta-mobile/api/plugin-api/com.letta.mobile.plugin.api/-lcp-method/index.html) and the message DTOs to
adapt wire runtimes to this contract, and read the wire reference for framing, ordering and error
behavior beyond individual Kotlin declarations.

# Package com.letta.mobile.plugin.keystore

The keystore provider SPI lets a module store secrets in any backend through one contract, with
`KeystoreProvider` as the extension point, `SecretRef` naming a slot without its value and
`SecretValue` as the only carrier of secret bytes. Expected failures are `KeystoreResult.Failure`
values with stable `KeystoreErrorCode`s, and a missing, locked or unhealthy provider fails closed
rather than answering an empty value. `SecretValue` prints redacted, compares by identity, has no
serializer of its own and lends its bytes only through `revealSecretBytes`. Grants, routing and
audit are host concerns specified in `docs/reference/contracts/keystore-v1.md`, not provided here.

# Package com.letta.mobile.plugin.keystore.wire

The out-of-process keystore protocol: `KeystoreMethod` lists the `keystore.*` JSON-RPC requests a
host sends a process provider over the LCP process framing, and the message classes are their
params and results. Secret bytes travel as base64 only in the `keystore.get` result and the
`keystore.put` and `keystore.unlock` params, which a host must never log; failures are JSON-RPC
error `-32020` whose data is a serialized `KeystoreError`. This package defines shapes only and
starts no process.
