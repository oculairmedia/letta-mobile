//! Requires multicast-capable CI networking; never run against a user device.
use std::{sync::Arc, time::Duration};

use iroh_ffi::{
    preset_minimal, Connection, Endpoint, EndpointAddr, EndpointOptions, MdnsEvent, MdnsMode,
    MdnsSubscription, PathSnapshot, RelayMode,
};
use tokio::time::{sleep, timeout};

const ALPN: &[u8] = b"iroh-ffi/mdns-proof/1";

fn options(mode: MdnsMode) -> EndpointOptions {
    EndpointOptions {
        preset: Some(preset_minimal()),
        relay_mode: Some(Arc::new(RelayMode::disabled())),
        mdns_mode: Some(mode),
        ..Default::default()
    }
}

async fn discover_host(scan: &MdnsSubscription, host: &Endpoint) -> EndpointAddr {
    timeout(Duration::from_secs(12), async {
        loop {
            match scan.next().await {
                Some(MdnsEvent::Discovered { addr }) if addr.id() == host.id() => {
                    return (*addr).clone();
                }
                Some(_) => continue,
                None => panic!("bounded scan ended before host was discovered"),
            }
        }
    })
    .await
    .unwrap()
}

fn is_selected_direct_ip(path: &PathSnapshot) -> bool {
    path.is_selected && path.is_ip && !path.is_relay
}

async fn assert_direct_path(conn: &Connection) {
    for _ in 0..20 {
        if conn.paths().iter().any(is_selected_direct_ip) {
            return;
        }
        sleep(Duration::from_millis(50)).await;
    }
    panic!("connection never selected a direct IP path");
}

#[tokio::test]
async fn native_mdns_compatibility_direct_path_and_untrusted_candidate() {
    let host = Endpoint::bind(EndpointOptions {
        alpns: Some(vec![ALPN.to_vec()]),
        ..options(MdnsMode::AnnounceHost)
    })
    .await
    .unwrap();
    let client = Endpoint::bind(options(MdnsMode::LookupClient))
        .await
        .unwrap();
    let scan = client.start_mdns_scan(10_000).await.unwrap();
    let candidate = discover_host(&scan, &host).await;
    assert!(candidate.relay_url().is_none());
    assert!(!candidate.direct_addresses().is_empty());

    // Merely discovering a peer must not initiate a connection or authorize it.
    assert!(timeout(Duration::from_millis(250), host.accept_next())
        .await
        .is_err());

    // Negative control: with relays off and no lookup, an ID-only address
    // cannot dial. This catches ambient/manual address injection false passes.
    let id_only = EndpointAddr::new(host.id().as_ref(), None, vec![]);
    assert!(id_only.direct_addresses().is_empty());
    assert!(id_only.relay_url().is_none());
    let without_lookup = Endpoint::bind(options(MdnsMode::Off)).await.unwrap();
    let unregistered = timeout(
        Duration::from_secs(2),
        without_lookup.connect(&id_only, ALPN),
    )
    .await;
    assert!(!matches!(unregistered, Ok(Ok(_))));
    without_lookup.close().await.unwrap();

    let host_accept = host.clone();
    let accept = tokio::spawn(async move {
        let incoming = host_accept.accept_next().await.unwrap();
        incoming.accept().await.unwrap().connect().await.unwrap()
    });
    // This dial supplies only the host ID. Removing the client's
    // builder.address_lookup registration makes this assertion fail.
    let conn = timeout(Duration::from_secs(5), client.connect(&id_only, ALPN))
        .await
        .unwrap()
        .unwrap();
    let inbound = timeout(Duration::from_secs(5), accept)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(conn.remote_id(), host.id());
    assert_direct_path(&conn).await;
    conn.close(0, b"done").unwrap();
    inbound.close(0, b"done").unwrap();
    scan.close().await;
    client.close().await.unwrap();
    host.close().await.unwrap();
}

#[tokio::test]
async fn native_mdns_lifecycle_and_default_preserved() {
    let host = Endpoint::bind(options(MdnsMode::AnnounceHost))
        .await
        .unwrap();
    let endpoint = Endpoint::bind(options(MdnsMode::LookupClient))
        .await
        .unwrap();
    assert!(endpoint.start_mdns_scan(0).await.is_err());
    assert!(endpoint.start_mdns_scan(30_001).await.is_err());
    let first = endpoint.start_mdns_scan(10_000).await.unwrap();
    discover_host(&first, &host).await;
    let second = endpoint.start_mdns_scan(10_000).await.unwrap();
    // The first stream may already hold Expired or further Discovered events.
    // Supersession must drain them before the new scan can deliver anything.
    assert!(timeout(Duration::from_secs(1), first.next())
        .await
        .unwrap()
        .is_none());
    discover_host(&second, &host).await;
    second.close().await;
    assert!(timeout(Duration::from_secs(1), second.next())
        .await
        .unwrap()
        .is_none());

    let third = endpoint.start_mdns_scan(10_000).await.unwrap();
    discover_host(&third, &host).await;
    endpoint.close().await.unwrap();
    assert!(timeout(Duration::from_secs(1), third.next())
        .await
        .unwrap()
        .is_none());
    assert!(endpoint.start_mdns_scan(1).await.is_err());
    host.close().await.unwrap();

    // The default record leaves the N0 preset's DNS/relay path untouched.
    // Relay/DNS connection fallback needs its own service-backed CI probe.
    assert!(EndpointOptions::default().mdns_mode.is_none());
}

#[tokio::test]
async fn native_mdns_bounded_scan_ends_normally() {
    let endpoint = Endpoint::bind(options(MdnsMode::LookupClient))
        .await
        .unwrap();
    let scan = endpoint.start_mdns_scan(1).await.unwrap();
    timeout(Duration::from_secs(1), async {
        while scan.next().await.is_some() {}
    })
    .await
    .unwrap();
    endpoint.close().await.unwrap();
}
