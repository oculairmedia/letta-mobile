# Pinned native mDNS binding prerequisite (cuewx.1)

This is an isolated source patch and reproducible CI artifact proof for
`computer.iroh:iroh:1.1.0`. It does not change the application's dependency,
activate discovery in a client, publish Maven coordinates, or grant pairing
trust. The host/client opt-in API is recorded in [docs/API.md](docs/API.md).

## Source and license pins

[The pin manifest](source-pin/manifest.json) records the Maven 1.1.0 POM,
JVM JAR, source JAR, and Android AAR SHA-256 values, upstream
`n0-computer/iroh-ffi` tag `v1.1.0` at commit
`5e451092dba0c1a09ee83ff6e5be37b1152a5c58`, Rust Iroh `1.0.2`, and
the Apache-2.0 `iroh-mdns-peer-lookup` source commit
`e1213d4a7ce16b9a4b7dda81dc835349289f2a9c`. The Rust lockfile in the
patch pins registry checksums. Preparation clones that exact peer source,
applies [a three-call compatibility patch](patch/iroh-mdns-peer-lookup-rust-1.91.patch)
replacing unstable `slice::as_array` with stable array conversions, and uses
the verified local copy for Rust 1.91.1. The manifest hashes the patch and
copied crate files. Upstream iroh-ffi retains its MIT and Apache-2.0
licenses. `hick` 0.2.0 is MIT OR Apache-2.0.

The published peer-lookup 0.1.0 and 0.1.2 archives have a `LICENSE` file
containing only `TBD (open to suggestions)`. The source commit above declares
`license = "Apache-2.0"`; the patch uses that exact Git revision. Do not replace
it with a crates.io release without checking its license and wire format.

## Prepare and proof

```bash
bash scripts/iroh-mdns-binding/prepare.sh
```

This checks both patch SHAs, clones the exact upstream and peer revisions into
ignored preparation space, applies the patches, verifies `Cargo.lock`, copied
source hashes and resolved licenses, and checks that Rust Iroh remains `1.0.2`. It performs no
native build. The dedicated [workflow](../../../.github/workflows/iroh-mdns-binding.yml)
performs the native two-endpoint test, generates Kotlin from the same patched
Rust library, builds Linux/Windows JARs and the four-ABI Android AAR, and
compares generated Kotlin and lockfile hashes across jobs. JVM tests exercise
the UniFFI bridge on Linux and Windows; an Android emulator smoke exercises the
new API against the packaged native ABI.

All CI artifacts are review proof only. The application continues consuming
Maven 1.1.0 and its existing N0 DNS/relay behavior.

## Acceptance evidence and limits

- [`tests/native_mdns.rs`](tests/native_mdns.rs) starts two native endpoints using the
  same standard DNS-SD peer-lookup format, disables relays, discovers a host,
  then dials using only its endpoint ID and asserts a selected direct IP path.
  An Off-mode negative control cannot make that ID-only dial. The test also
  checks that discovery alone creates no connection, that superseding or closing
  a scan invalidates its stream after discovery, and that invalid durations and
  post-close scans are rejected. A crate unit test queues a second candidate
  and checks that cancellation drains it.
- The test runs on one multicast-capable CI host. A two-device LAN run and
  blocked-multicast relay/DNS connection proof remain separate platform-matrix
  work. The default option path is checked to leave N0 selection untouched;
  no relay service is started by this test.
- An `Expired` event means a bounded scan ended normally and its candidates
  must be cleared. Supersession, subscription close, endpoint close, or handle
  drop invalidates and drains the old stream immediately. Consumers also clear
  candidates when `next()` returns `None`. Expiration does not assert that a
  remote DNS-SD TTL elapsed or the remote peer went offline.
- A discovered address is an untrusted transport hint. Iroh authenticates the
  dialed endpoint key at the QUIC layer; authorization still requires the
  existing scoped pairing and confirmation flow in the application. This patch
  contains no privileged API or trust mutation. The native test cannot prove
  app-level permission behavior; that remains a production activation gate.
- Packet rate and battery impact have not been measured. The CI test measures
  bounded scan lifetime, and makes no packet-rate or battery claim.
- Linux x86-64, Windows x86-64, and Android ABIs are configured for CI.
  Peer lookup 0.1.2 currently uses IPv4 DNS-SD because of an upstream IPv6
  issue. IPv6-only LAN behavior, Linux ARM64, macOS ARM64, and real-device
  Android multicast behavior remain
  outside this first proof matrix. CI must pass before any parity claim.
