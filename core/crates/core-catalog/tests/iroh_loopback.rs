//! Two Rust catalog nodes over the Iroh carrier on loopback: the byte-stream ALPN, the access hook in
//! the connection hook, and samod sync through the gate.

mod common;

use std::sync::Arc;
use std::time::Duration;

use common::{Constellation, fixture_doc_id, node};
use core_carrier::Protocols;
use core_carrier_iroh::{IrohConfig, IrohNode, node_id_of_secret};
use core_catalog::protocol::system_clock;
use core_catalog::write::Genesis;
use core_catalog::{ALPN, CatalogNode, CatalogPolicy, DocClass};
use core_membership::Preset;
use core_store::MemoryStore;

async fn start(n: u8, policy: CatalogPolicy) -> (CatalogNode<MemoryStore>, IrohNode) {
    let catalog = CatalogNode::start(
        node(n),
        Arc::new(MemoryStore::new()),
        policy,
        system_clock(),
    )
    .await;
    let protocols = Protocols::builder()
        .access(catalog.access_hook())
        .accept_stream(ALPN, catalog.handler())
        .build();
    let secret = [n; 32];
    assert_eq!(
        node_id_of_secret(&secret),
        node(n),
        "fixture keys are iroh keys"
    );
    let endpoint = IrohNode::bind(IrohConfig::loopback(secret), protocols)
        .await
        .expect("bind");
    (catalog, endpoint)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_member_finds_a_document_over_iroh_and_a_stranger_is_refused() {
    let constellation =
        Constellation::new(&[(2, Preset::DesktopNode)], fixture_doc_id("iroh root"));
    let policy = || {
        CatalogPolicy::new(
            constellation.view.clone(),
            &[],
            std::collections::BTreeMap::default(),
        )
    };
    let (a, a_endpoint) = start(1, policy()).await;
    let (b, b_endpoint) = start(2, policy()).await;
    let url = a
        .create(
            DocClass::Agent,
            &Genesis {
                constellation_id: constellation.id_hex(),
                created_at_ms: 0,
                agent_id: Some("agent-a".into()),
                name: None,
            },
        )
        .await
        .expect("create");
    b.register(DocClass::Agent, url.doc_id(), None)
        .expect("register");
    let _dialer = b
        .dial(Arc::new(b_endpoint.carrier()), a_endpoint.addr())
        .expect("dial");
    let found = tokio::time::timeout(Duration::from_secs(20), async {
        loop {
            if let Ok(Some(_)) = b.find(&url).await {
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    })
    .await;
    assert!(found.is_ok(), "b found the agent doc over iroh");
    assert_eq!(b.canonical(&url).await.expect("view")["agentId"], "agent-a");

    // A stranger is closed by the connection hook before any handler runs.
    let stranger = IrohNode::bind(IrohConfig::loopback([9; 32]), Protocols::none())
        .await
        .expect("bind");
    let refused =
        core_carrier::Carrier::dial_stream(&stranger.carrier(), &a_endpoint.addr(), ALPN).await;
    let refused_by_hook = match refused {
        Err(core_carrier::CarrierError::Closed(closure)) => {
            closure.app().is_some_and(|c| c.code == 4403)
        }
        Ok(link) => matches!(
            tokio::time::timeout(Duration::from_secs(10), link.control.closed()).await,
            Ok(closure) if closure.app().is_some_and(|c| c.code == 4403)
        ),
        Err(_) => false,
    };
    assert!(refused_by_hook, "the stranger is refused 4403");

    a.stop().await;
    b.stop().await;
    a_endpoint.shutdown().await;
    b_endpoint.shutdown().await;
    stranger.shutdown().await;
}
