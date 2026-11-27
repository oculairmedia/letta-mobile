//! Rust-to-JVM interop: a Rust catalog node dials a Kotlin node's `AutomergeIrohRepoProtocol`
//! (`org.automerge:automerge` 0.0.9: automerge 0.8.0, samod-core 0.9.0) over Iroh, through the
//! access gate, and syncs one of its notebooks.
//!
//! Skipped unless these are set (how to start the Kotlin side is in
//! `docs/reference/contracts/catalog-v1/catalog-v1.md`, "Running the JVM interop test"):
//!
//! - `BAKEN_KOTLIN_AUTOMERGE_NODE`: the Kotlin node as `<64-hex endpoint id>@<ip:port>`;
//! - `BAKEN_KOTLIN_NOTEBOOK_URL`: an `automerge:<bs58check>` notebook the Kotlin node holds;
//! - `BAKEN_RUST_SECRET_HEX`: the Rust endpoint's 32-byte secret key, hex. Its endpoint id must be in
//!   the Kotlin node's `--notebook-peer-ids`; the test prints it.

use std::collections::BTreeMap;
use std::sync::Arc;
use std::time::Duration;

use core_carrier::{NodeAddr, Protocols};
use core_carrier_iroh::{IrohConfig, IrohNode, node_id_of_secret};
use core_catalog::protocol::system_clock;
use core_catalog::{ALPN, CatalogNode, CatalogPolicy, DocClass, DocUrl, NotebookGrant};
use core_membership::{
    CatalogRoot, MembershipLog, NodeKey, Op, OpHeader, OpPayload, Preset, SignedOp,
};
use core_proto::NodeId;
use core_store::MemoryStore;

const NODE_ENV: &str = "BAKEN_KOTLIN_AUTOMERGE_NODE";
const DOC_ENV: &str = "BAKEN_KOTLIN_NOTEBOOK_URL";
const SECRET_ENV: &str = "BAKEN_RUST_SECRET_HEX";

struct Target {
    addr: NodeAddr,
    notebook: DocUrl,
    secret: [u8; 32],
}

fn target() -> Option<Target> {
    let node = std::env::var(NODE_ENV).ok()?;
    let notebook = std::env::var(DOC_ENV).ok()?;
    let secret = std::env::var(SECRET_ENV).ok()?;
    let (id, addr) = node.split_once('@').expect("<hex>@<ip:port>");
    let id: NodeId = id.parse().expect("a 64-hex endpoint id");
    Some(Target {
        addr: NodeAddr::new(id).with_direct(addr.parse().expect("an ip:port")),
        notebook: notebook.parse().expect("an automerge URL"),
        secret: hex::decode(secret)
            .expect("hex")
            .try_into()
            .expect("32 bytes"),
    })
}

/// A one-node constellation of a throwaway key: the Kotlin peer is a guest with an edit grant on
/// the notebook, which is all the gate needs to serve it.
fn policy(kotlin: NodeId, notebook: &DocUrl) -> CatalogPolicy {
    let key = NodeKey::from_seed(&[7; 32]);
    let genesis = SignedOp::sign(
        &Op {
            header: OpHeader {
                constellation: None,
                issuer: key.node_id(),
                parents: std::collections::BTreeSet::new(),
                ts_ms: 0,
            },
            payload: OpPayload::Genesis {
                caps: Preset::FirstDevice.capabilities(),
                name: None,
                label: None,
                catalog_root: CatalogRoot([0; 16]),
            },
            extensions: BTreeMap::new(),
        },
        &key,
    )
    .expect("signs");
    let view = MembershipLog::from_genesis(genesis).evaluate().view;
    let registry = vec![core_store::CatalogDoc {
        doc_id: notebook.doc_id(),
        class: DocClass::Notebook.as_str().to_owned(),
        agent_id: None,
        state: core_store::DocState::Live,
        registered_at_ms: 0,
    }];
    let grants = BTreeMap::from([(
        kotlin,
        BTreeMap::from([(
            notebook.doc_id(),
            NotebookGrant {
                may_read: true,
                may_edit: true,
            },
        )]),
    )]);
    CatalogPolicy::new(view, &registry, grants)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_rust_catalog_node_syncs_a_notebook_from_the_kotlin_repo_protocol() {
    let Some(target) = target() else {
        eprintln!(
            "skipped: set {NODE_ENV}, {DOC_ENV} and {SECRET_ENV} to run against a Kotlin node"
        );
        return;
    };
    let ours = node_id_of_secret(&target.secret);
    eprintln!("rust endpoint id (add to --notebook-peer-ids): {ours}");
    let store = Arc::new(MemoryStore::new());
    let catalog = CatalogNode::start(
        ours,
        store,
        policy(target.addr.id, &target.notebook),
        system_clock(),
    )
    .await;
    let protocols = Protocols::builder()
        .access(catalog.access_hook())
        .accept_stream(ALPN, catalog.handler())
        .build();
    let endpoint = IrohNode::bind(IrohConfig::loopback(target.secret), protocols)
        .await
        .expect("bind");
    let _dialer = catalog
        .dial(Arc::new(endpoint.carrier()), target.addr.clone())
        .expect("dial");
    let handle = tokio::time::timeout(Duration::from_secs(30), catalog.find(&target.notebook))
        .await
        .expect("find resolves")
        .expect("repository running")
        .expect("the Kotlin node serves the notebook");
    drop(handle);
    let root = catalog
        .canonical(&target.notebook)
        .await
        .expect("canonical");
    assert_eq!(root["schema"], "notebook/1", "{root}");
    catalog.stop().await;
    endpoint.shutdown().await;
}
