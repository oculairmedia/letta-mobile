//! Requires multicast-capable CI networking; never run against a user device.
use std::{sync::Arc, time::Duration};

use iroh_ffi::{Endpoint, EndpointOptions, MdnsEvent, MdnsMode, RelayMode, preset_minimal};
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
    let candidate = timeout(Duration::from_secs(12), async {
        loop {
            match scan.next().await {
                Some(MdnsEvent::Discovered { addr }) if addr.id() == host.id() => break addr,
                Some(_) => continue,
                None => panic!("bounded scan ended before host was discovered"),
            }
        }
    })
    .await
    .unwrap();
    assert!(candidate.relay_url().is_none());
    assert!(!candidate.direct_addresses().is_empty());

    // Merely discovering a peer must not initiate a connection or authorize it.
    assert!(
        timeout(Duration::from_millis(250), host.accept_next())
            .await
            .is_err()
    );
    let host_accept = host.clone();
    let accept = tokio::spawn(async move {
        let incoming = host_accept.accept_next().await.unwrap();
        incoming.accept().await.unwrap().connect().await.unwrap()
    });
    let conn = timeout(Duration::from_secs(5), client.connect(&candidate, ALPN))
        .await
        .unwrap()
        .unwrap();
    let inbound = timeout(Duration::from_secs(5), accept)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(conn.remote_id(), host.id());
    for _ in 0..20 {
        if conn
            .paths()
            .iter()
            .any(|path| path.is_selected && path.is_ip && !path.is_relay)
        {
            break;
        }
        sleep(Duration::from_millis(50)).await;
    }
    assert!(
        conn.paths()
            .iter()
            .any(|path| path.is_selected && path.is_ip && !path.is_relay)
    );
    conn.close(0, b"done").unwrap();
    inbound.close(0, b"done").unwrap();
    scan.close().await;
    client.close().await.unwrap();
    host.close().await.unwrap();
}

#[tokio::test]
async fn native_mdns_lifecycle_and_default_preserved() {
    let endpoint = Endpoint::bind(options(MdnsMode::LookupClient))
        .await
        .unwrap();
    assert!(endpoint.start_mdns_scan(0).await.is_err());
    assert!(endpoint.start_mdns_scan(30_001).await.is_err());
    let first = endpoint.start_mdns_scan(3_000).await.unwrap();
    let second = endpoint.start_mdns_scan(1).await.unwrap();
    assert!(
        timeout(Duration::from_secs(1), first.next())
            .await
            .unwrap()
            .is_none()
    );
    assert!(
        timeout(Duration::from_secs(1), second.next())
            .await
            .unwrap()
            .is_none()
    );
    second.close().await;
    endpoint.close().await.unwrap();
    assert!(endpoint.start_mdns_scan(1).await.is_err());

    // The default record leaves the N0 preset's DNS/relay path untouched.
    // Relay/DNS connection fallback needs its own service-backed CI probe.
    assert!(EndpointOptions::default().mdns_mode.is_none());
}
