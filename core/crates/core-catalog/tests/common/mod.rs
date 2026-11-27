//! Shared helpers for the catalog-v1 fixture, gate and sync tests.

#![allow(dead_code, unreachable_pub, missing_docs)]

use std::collections::BTreeSet;
use std::path::PathBuf;

use automerge::{ActorId, AutoCommit};
use core_membership::{
    CapSet, CatalogRoot, MembershipLog, MembershipView, NodeKey, Op, OpHeader, OpPayload, Preset,
    SignedOp,
};
use core_proto::NodeId;
use core_proto::crypto::blake3_hash;
use core_store::DocId;
use core_testkit::contract_path;
use serde_json::Value as Json;

/// The environment variable that rewrites generated fixtures.
pub const REGEN: &str = "BAKEN_REGEN_FIXTURES";

pub fn regenerating() -> bool {
    std::env::var_os(REGEN).is_some()
}

pub fn key(n: u8) -> NodeKey {
    NodeKey::from_seed(&[n; 32])
}

pub fn node(n: u8) -> NodeId {
    key(n).node_id()
}

/// The fixed actor of simulated node `n`: fifteen zero bytes, then `n`.
pub fn actor(n: u8) -> ActorId {
    let mut bytes = [0_u8; 16];
    bytes[15] = n;
    ActorId::from(bytes.as_slice())
}

pub fn doc(n: u8) -> AutoCommit {
    AutoCommit::new().with_actor(actor(n))
}

/// A fixture document id: the first 16 bytes of `blake3("catalog-v1 fixture " ‖ case)`.
pub fn fixture_doc_id(case: &str) -> DocId {
    let digest = blake3_hash(format!("catalog-v1 fixture {case}").as_bytes());
    DocId::from_slice(&digest[..16]).expect("16 bytes")
}

pub fn fixture_path(relative: &str) -> PathBuf {
    contract_path(&format!("catalog-v1/{relative}"))
}

/// Writes a fixture when regenerating; otherwise asserts the file on disk equals `bytes`.
pub fn check_or_write_bytes(relative: &str, bytes: &[u8]) {
    let path = fixture_path(relative);
    if regenerating() {
        std::fs::create_dir_all(path.parent().expect("dir")).expect("mkdir");
        std::fs::write(&path, bytes).expect("write fixture");
        return;
    }
    let on_disk = std::fs::read(&path)
        .unwrap_or_else(|e| panic!("{relative}: {e}; regenerate with {REGEN}=1"));
    assert!(
        on_disk == bytes,
        "{relative} differs from the generator; regenerate with {REGEN}=1 if the change is deliberate"
    );
}

/// As [`check_or_write_bytes`] for pretty JSON with a trailing newline (line endings normalised).
pub fn check_or_write_json(relative: &str, value: &Json) {
    let text = serde_json::to_string_pretty(value).expect("json") + "\n";
    let path = fixture_path(relative);
    if regenerating() {
        std::fs::create_dir_all(path.parent().expect("dir")).expect("mkdir");
        std::fs::write(&path, &text).expect("write fixture");
        return;
    }
    let on_disk = std::fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("{relative}: {e}; regenerate with {REGEN}=1"))
        .replace("\r\n", "\n");
    assert!(
        on_disk == text,
        "{relative} differs from the generator; regenerate with {REGEN}=1 if the change is deliberate"
    );
}

pub fn read_json(relative: &str) -> Json {
    let text = std::fs::read_to_string(fixture_path(relative))
        .unwrap_or_else(|e| panic!("{relative}: {e}"));
    serde_json::from_str(&text).unwrap_or_else(|e| panic!("{relative}: {e}"))
}

/// A constellation: node 1 is the first device (genesis), then each `(n, preset)` is added by node
/// 1. `catalog_root` is the root document id.
pub struct Constellation {
    pub ops: Vec<SignedOp>,
    pub view: MembershipView,
}

impl Constellation {
    pub fn new(members: &[(u8, Preset)], catalog_root: DocId) -> Self {
        let genesis = SignedOp::sign(
            &Op {
                header: OpHeader {
                    constellation: None,
                    issuer: node(1),
                    parents: BTreeSet::new(),
                    ts_ms: 0,
                },
                payload: OpPayload::Genesis {
                    caps: Preset::FirstDevice.capabilities(),
                    name: Some("node-1".into()),
                    label: Some("fixture".into()),
                    catalog_root: CatalogRoot(*catalog_root.as_bytes()),
                },
                extensions: std::collections::BTreeMap::new(),
            },
            &key(1),
        )
        .expect("genesis signs");
        let mut log = MembershipLog::from_genesis(genesis.clone());
        let mut ops = vec![genesis];
        for (n, preset) in members {
            let parents = log.heads();
            let op = SignedOp::sign(
                &Op {
                    header: OpHeader {
                        constellation: Some(log.constellation()),
                        issuer: node(1),
                        parents,
                        ts_ms: 0,
                    },
                    payload: OpPayload::Add {
                        subject: node(*n),
                        caps: preset.capabilities(),
                        name: Some(format!("node-{n}")),
                    },
                    extensions: std::collections::BTreeMap::new(),
                },
                &key(1),
            )
            .expect("add signs");
            log.insert(op.clone()).expect("inserts");
            ops.push(op);
        }
        Self {
            view: log.evaluate().view,
            ops,
        }
    }

    pub fn id_hex(&self) -> String {
        hex::encode(self.view.constellation().as_bytes())
    }

    /// Signs and applies one more op by node 1.
    pub fn apply(&mut self, payload: OpPayload) -> SignedOp {
        let mut log = MembershipLog::from_genesis(self.ops[0].clone());
        for op in &self.ops[1..] {
            log.insert(op.clone()).expect("inserts");
        }
        let op = SignedOp::sign(
            &Op {
                header: OpHeader {
                    constellation: Some(log.constellation()),
                    issuer: node(1),
                    parents: log.heads(),
                    ts_ms: 0,
                },
                payload,
                extensions: std::collections::BTreeMap::new(),
            },
            &key(1),
        )
        .expect("signs");
        log.insert(op.clone()).expect("inserts");
        self.ops.push(op.clone());
        self.view = log.evaluate().view;
        op
    }

    pub fn revoke(&mut self, n: u8) -> SignedOp {
        self.apply(OpPayload::Revoke {
            subject: node(n),
            reason: None,
        })
    }

    pub fn grant(&mut self, n: u8, add: CapSet, remove: CapSet) -> SignedOp {
        self.apply(OpPayload::Grant {
            subject: node(n),
            caps_add: add,
            caps_remove: remove,
        })
    }
}
