# Iroh Relay Configuration

## Overview

Iroh uses relay servers to facilitate connections when direct connectivity is not possible (e.g., when both peers are behind NAT or firewalls). By default, iroh uses the public n0 relay infrastructure operated by the iroh project.

For privacy-focused deployments where you don't want to rely on third-party infrastructure, you can run your own relay server and configure your nodes to use it.

## Relay Configuration Options

### 1. Default (Public n0 Relays)

Suitable for development and testing. Uses the iroh project's public relay infrastructure.

```kotlin
val relayConfig = IrohRelayConfig.Default

val server = IrohNodeServer.create(
    id = "node-1",
    displayName = "My Node",
    controller = controller,
    scope = scope,
    relayConfig = relayConfig,
)
```

### 2. Custom (Self-Hosted Relay)

For production deployments where you want full control and no third-party dependencies.

```kotlin
val relayConfig = IrohRelayConfig.Custom(
    urls = listOf("https://relay.example.com:443")
)

val server = IrohNodeServer.create(
    id = "node-1",
    displayName = "My Node",
    controller = controller,
    scope = scope,
    relayConfig = relayConfig,
)
```

You can specify multiple relay URLs for redundancy:

```kotlin
val relayConfig = IrohRelayConfig.Custom(
    urls = listOf(
        "https://relay1.example.com:443",
        "https://relay2.example.com:443",
    )
)
```

### 3. Disabled (Direct-Only)

For environments where you know direct connectivity is always possible (e.g., same LAN, VPN, or public IPs).

```kotlin
val relayConfig = IrohRelayConfig.Disabled

val server = IrohNodeServer.create(
    id = "node-1",
    displayName = "My Node",
    controller = controller,
    scope = scope,
    relayConfig = relayConfig,
)
```

**Warning:** With relay disabled, nodes will only be able to connect if they can establish a direct QUIC connection. This will fail if both peers are behind NAT without successful hole-punching.

## Setting Up a Self-Hosted Relay

Full documentation: https://docs.iroh.computer/add-a-relay

### Quick Start

1. **Install the iroh CLI:**

   ```bash
   cargo install iroh
   ```

2. **Run the relay server:**

   ```bash
   # Run on a publicly accessible host with a domain name
   iroh relay --bind-addr 0.0.0.0:443 --hostname relay.example.com
   ```

   For production:
   - Use a domain with a valid TLS certificate
   - Configure firewall to allow UDP and TCP on port 443
   - Consider running behind a reverse proxy with automatic TLS (e.g., Caddy)
   - Set up systemd service for automatic restart

3. **Configure your nodes to use it:**

   ```kotlin
   val relayConfig = IrohRelayConfig.Custom(
       urls = listOf("https://relay.example.com:443")
   )
   ```

### Production Deployment Example (Docker + Caddy)

```yaml
# docker-compose.yml
services:
  relay:
    image: iroh/iroh:latest
    command: relay --bind-addr 0.0.0.0:8443 --hostname relay.example.com
    restart: unless-stopped
    networks:
      - relay-net

  caddy:
    image: caddy:latest
    ports:
      - "443:443"
      - "443:443/udp"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - caddy_data:/data
    networks:
      - relay-net

networks:
  relay-net:

volumes:
  caddy_data:
```

```
# Caddyfile
relay.example.com {
    reverse_proxy relay:8443 {
        transport http {
            versions h2c 2
        }
    }
}
```

## Verification

### Testing Relay Configuration

The relay configuration is applied when creating the iroh endpoint. You can verify it works by:

1. **Unit tests** (already included in `IrohRelayConfigTest.kt`):
   - Verify endpoint binds with each config mode
   - Confirm custom URLs are accepted
   - Test validation (e.g., empty URL list fails)

2. **Integration tests** (requires real network):
   - Deploy two nodes on different networks (different NATs)
   - Configure both to use your custom relay
   - Verify they can establish a connection

3. **Production monitoring**:
   - Monitor relay server logs for connection attempts
   - Track direct vs. relayed connection ratio
   - Set up alerts for relay server downtime

### Checking Active Relay

The `EndpointAddr` returned by `endpoint.addr()` includes the active relay URL:

```kotlin
val endpoint = IrohNodeEndpoint(...)
endpoint.create()
val addr = endpoint.addr()
// addr contains node ID + relay URL + direct addresses
```

## Security Considerations

### Privacy

- **Default relays:** Traffic is end-to-end encrypted, but connection metadata (IP addresses, timing) passes through n0's infrastructure.
- **Self-hosted relays:** You control all infrastructure, so no metadata leaks to third parties.
- **Disabled relays:** Maximum privacy, but connectivity only works with direct connections.

### Threat Model

The relay server:
- **Cannot decrypt** your traffic (end-to-end QUIC encryption)
- **Can observe** connection metadata (which node IDs are connecting, when, from which IPs)
- **Cannot modify** traffic (authenticated encryption)

For maximum privacy in NAT-traversal scenarios, run your own relay.

## Troubleshooting

### Connection fails with custom relay

1. Check relay server is reachable: `curl -v https://relay.example.com:443`
2. Verify firewall allows UDP and TCP on the relay port
3. Check relay server logs for connection attempts
4. Confirm both nodes are using the same relay URL

### Performance issues

1. Ensure relay server has sufficient bandwidth
2. Check network latency to relay server
3. Consider deploying multiple relay servers for geographic distribution
4. Monitor relay server CPU/memory usage

### Direct connections work, relay doesn't

1. Verify relay server TLS certificate is valid
2. Check that `--hostname` matches the actual domain
3. Ensure relay server is publicly accessible (not behind NAT)
4. Review relay server logs for errors
