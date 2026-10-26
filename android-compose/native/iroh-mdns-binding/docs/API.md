# Native API amendment before client activation

The source patch extends the **same** UniFFI generation used for Kotlin and
native libraries. No application code imports this API yet.

| Rust / generated Kotlin surface | Semantics |
| --- | --- |
| `EndpointOptions.mdns_mode: Option<MdnsMode>` / `mdnsMode` | `None` or `Off` leaves the selected preset unchanged. No mDNS socket is opened. |
| `MdnsMode::AnnounceHost` | Adds standard DNS-SD peer lookup with announcement and lookup. A listening native host opts in explicitly. |
| `MdnsMode::LookupClient` | Adds the same lookup format without self-advertisement. A native client opts in only for explicit searches. |
| `Endpoint::start_mdns_scan(duration_ms: u32)` / `startMdnsScan` | Starts one browse of `_irohv1._udp.local.` for 1–30,000 ms, at most 64 candidate IDs. A new scan cancels the previous scan on this endpoint. Off and closed endpoints reject it. |
| `MdnsSubscription::next()` / `next` | Yields `Discovered { addr }` and, on normal bounded completion, `Expired { id }` events. On supersession or close, buffered events are discarded and the old stream yields `None`; consumers clear that scan's candidates then. |
| `MdnsSubscription::close()` / Kotlin `shutdown` | Invalidates and drains the event stream, then aborts and joins the browse task. Dropping the handle also invalidates and aborts. |
| `Endpoint::close()` / Kotlin `shutdown` | Invalidates and drains the active scan, aborts it, and drops its browse server before closing Iroh. |

The peer-lookup dependency uses the existing `_irohv1._udp.local.` DNS-SD
service, z-base-32 endpoint-ID instance label, SRV/A/AAAA direct address, and
its `aN=` binary TXT address extras. The binding does not define or negotiate a
new wire protocol. The event stream filters malformed IDs and unusable IPs,
deduplicates candidates, and carries no relay URL or authorization token.

The published peer-lookup crate only resolves a **known** endpoint ID. Bounded
peer enumeration uses the licensed `hick` DNS-SD browse API over the same
service name, with a separately owned handle that can be canceled. The
endpoint's Iroh address lookup still uses peer-lookup for direct dials, in
addition to any existing N0 DNS lookup selected by its preset.

Before S2 or application activation, review the generated Kotlin API from CI,
the Linux/Windows/Android ABI proof, real LAN and blocked-multicast behavior,
and app-level scoped pairing tests. No discovered address may enter the trusted
host roster or call privileged APIs merely because it appeared on mDNS.
