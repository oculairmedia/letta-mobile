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
