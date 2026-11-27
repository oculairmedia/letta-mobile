//! The joiner's bounded proof push, and persistence through the store trait.

mod common;

use std::collections::BTreeSet;
use std::sync::atomic::{AtomicBool, Ordering};

use common::{DagBuilder, node_named};
use core_membership::proof::ProofError;
use core_membership::{
    Inserted, MembershipLog, OpId, PersistError, PersistentLog, PushBudget, PushError, PushLimits,
    proof_bundle,
};
use core_store::{BindingsStore, MemoryStore, Store, StoreError, StoredOp, Txn};
use serde_json::json;

const R0: &str = "00000000000000000000000000000000";

/// N1 founds the constellation, adds N2, writes unrelated ops, then adds N3 (the joiner).
fn constellation() -> DagBuilder {
    let mut b = DagBuilder::default();
    for desc in [
        json!({"op": "g", "kind": "genesis", "issuer": "N1", "caps_add": "preset:first_device", "catalog_root": R0}),
        json!({"op": "a2", "kind": "add", "issuer": "N1", "parents": ["g"], "subject": "N2", "caps_add": "preset:phone"}),
        json!({"op": "p", "kind": "policy_set", "issuer": "N1", "parents": ["a2"], "policy": {"map": [[0, {"uint": 0}]]}}),
        json!({"op": "r9", "kind": "revoke", "issuer": "N1", "parents": ["a2"], "subject": "N9"}),
        json!({"op": "a3", "kind": "add", "issuer": "N1", "parents": ["a2"], "subject": "N3", "caps_add": "preset:desktop_node"}),
    ] {
        b.add(&desc);
    }
    b
}

fn log_of(b: &DagBuilder, labels: &[&str]) -> MembershipLog {
    let mut log = MembershipLog::new(b.constellation.expect("genesis"));
    for label in labels {
        log.insert(b.by_label[*label].clone()).expect("insert");
    }
    log
}

#[test]
fn a_joiner_proves_membership_to_a_peer_that_lacks_its_add() {
    let b = constellation();
    let inviter = log_of(&b, &["g", "a2", "p", "r9", "a3"]);
    let joiner = node_named("N3");
    let bundle = proof_bundle(&inviter, &joiner, PushLimits::default()).expect("bundle");
    // The proof is the add and its causal past only: genesis, the add of N2, the add of N3.
    assert_eq!(bundle.len(), 3);

    let mut peer = log_of(&b, &["g"]);
    assert!(!peer.evaluate().view.is_member(&joiner));
    let mut budget =
        PushBudget::open(&peer.evaluate().view, joiner, PushLimits::default()).expect("open");
    for envelope in &bundle {
        budget.accept(&mut peer, envelope).expect("accepted");
    }
    assert!(budget.admitted(&peer));
    assert_eq!(budget.pusher(), joiner);
}

#[test]
fn proofs_fail_for_non_members_and_beyond_the_limits() {
    let b = constellation();
    let log = log_of(&b, &["g", "a2", "p", "r9", "a3"]);
    assert_eq!(
        proof_bundle(&log, &node_named("N9"), PushLimits::default()),
        Err(ProofError::NotMember)
    );
    let tight = PushLimits {
        max_ops: 2,
        max_bytes: 64 * 1024,
    };
    assert_eq!(
        proof_bundle(&log, &node_named("N3"), tight),
        Err(ProofError::TooLarge)
    );
    // The genesis admin's proof is the genesis alone.
    assert_eq!(
        proof_bundle(&log, &node_named("N1"), PushLimits::default())
            .expect("bundle")
            .len(),
        1
    );
}

#[test]
fn push_budgets_refuse_revoked_pushers_and_run_out() {
    let b = constellation();
    let peer = log_of(&b, &["g", "a2", "r9"]);
    assert_eq!(
        PushBudget::open(
            &peer.evaluate().view,
            node_named("N9"),
            PushLimits::default()
        )
        .err(),
        Some(PushError::Refused)
    );
    let mut peer = log_of(&b, &["g"]);
    let limits = PushLimits {
        max_ops: 1,
        max_bytes: 64 * 1024,
    };
    let mut budget =
        PushBudget::open(&peer.evaluate().view, node_named("N3"), limits).expect("open");
    let a2 = b.by_label["a2"].envelope();
    let a3 = b.by_label["a3"].envelope();
    assert!(matches!(
        budget.accept(&mut peer, &a2),
        Ok(Inserted::Stored(_))
    ));
    assert_eq!(budget.accept(&mut peer, &a3), Err(PushError::Exhausted));

    let mut budget = PushBudget::open(
        &peer.evaluate().view,
        node_named("N3"),
        PushLimits {
            max_ops: 64,
            max_bytes: 10,
        },
    )
    .expect("open");
    assert_eq!(budget.accept(&mut peer, &a3), Err(PushError::Exhausted));
}

#[test]
fn persistent_logs_store_complete_ops_and_reopen_identically() {
    let b = constellation();
    let constellation = b.constellation.expect("genesis");
    let mut log = PersistentLog::open(constellation, MemoryStore::default()).expect("opens");
    // A child before its parent waits and is not stored yet.
    assert!(matches!(
        log.insert(b.by_label["a3"].clone(), 1_000),
        Ok(Inserted::Pending { .. })
    ));
    assert!(stored(log.store()).is_empty());
    log.insert(b.by_label["g"].clone(), 1_000).expect("genesis");
    let inserted = log.insert(b.by_label["a2"].clone(), 1_000).expect("a2");
    assert_eq!(
        inserted,
        Inserted::Stored(vec![b.id("a2"), b.id("a3")]),
        "the parent completes the waiting child"
    );
    for label in ["p", "r9"] {
        log.insert(b.by_label[label].clone(), 1_000)
            .expect("insert");
    }
    assert_eq!(stored(log.store()).len(), 5);
    let evaluation = log.log().evaluate();

    let reopened = PersistentLog::open(constellation, log.into_store()).expect("reopens");
    assert_eq!(reopened.log().evaluate(), evaluation);
    assert_eq!(
        reopened.log().heads(),
        BTreeSet::from([b.id("p"), b.id("r9"), b.id("a3")])
    );
}

#[test]
fn a_corrupt_store_is_reported() {
    let b = constellation();
    let store = MemoryStore::default();
    let mut envelope = b.by_label["g"].envelope();
    let last = envelope.len() - 1;
    envelope[last] ^= 1;
    put_raw(&store, OpId::from_bytes([0; 32]), envelope);
    assert!(matches!(
        PersistentLog::open(b.constellation.expect("genesis"), store),
        Err(PersistError::Corrupt(_))
    ));
}

fn stored(store: &MemoryStore) -> Vec<StoredOp> {
    store.read(|txn| txn.ops()).expect("read ops")
}

fn put_raw(store: &MemoryStore, op_id: OpId, envelope: Vec<u8>) {
    store
        .write(|txn| {
            txn.put_op(&StoredOp {
                op_id: op_id.into(),
                envelope,
                issuer: node_named("N1"),
                kind: 0,
                parents: Vec::new(),
                received_at_ms: 0,
            })
            .map(drop)
        })
        .expect("put");
}

#[test]
fn ops_are_stored_under_their_own_id() {
    let b = constellation();
    let store = MemoryStore::default();
    put_raw(
        &store,
        OpId::from_bytes([9; 32]),
        b.by_label["g"].envelope(),
    );
    assert!(matches!(
        PersistentLog::open(b.constellation.expect("genesis"), store),
        Err(PersistError::IdMismatch { .. })
    ));
}

/// A [`MemoryStore`] whose writes fail while `failing` is set.
#[derive(Default)]
struct FlakyStore {
    inner: MemoryStore,
    failing: AtomicBool,
}

impl Store for FlakyStore {
    fn read<T, E: From<StoreError>>(
        &self,
        f: impl FnOnce(&dyn Txn) -> Result<T, E>,
    ) -> Result<T, E> {
        self.inner.read(f)
    }

    fn write<T, E: From<StoreError>>(
        &self,
        f: impl FnOnce(&mut dyn Txn) -> Result<T, E>,
    ) -> Result<T, E> {
        if self.failing.load(Ordering::SeqCst) {
            return Err(StoreError::Invalid("injected write failure".into()).into());
        }
        self.inner.write(f)
    }

    fn bindings(&self) -> &dyn BindingsStore {
        self.inner.bindings()
    }

    fn schema_version(&self) -> u32 {
        self.inner.schema_version()
    }
}

#[test]
fn a_failed_write_leaves_the_log_unchanged_so_a_retry_stores_the_op() {
    let b = constellation();
    let constellation = b.constellation.expect("genesis");
    let mut log = PersistentLog::open(constellation, FlakyStore::default()).expect("opens");
    log.insert(b.by_label["g"].clone(), 1_000).expect("genesis");
    // a3 waits for a2; a2's write fails, so neither joins and a3 waits again.
    assert!(matches!(
        log.insert(b.by_label["a3"].clone(), 1_000),
        Ok(Inserted::Pending { .. })
    ));
    log.store().failing.store(true, Ordering::SeqCst);
    assert!(matches!(
        log.insert(b.by_label["a2"].clone(), 1_000),
        Err(PersistError::Store(_))
    ));
    assert!(!log.log().contains(&b.id("a2")));
    assert!(!log.log().contains(&b.id("a3")));
    assert_eq!(log.log().pending().count(), 1, "a3 waits again");

    log.store().failing.store(false, Ordering::SeqCst);
    assert_eq!(
        log.insert(b.by_label["a2"].clone(), 1_000).expect("retry"),
        Inserted::Stored(vec![b.id("a2"), b.id("a3")]),
        "the retry is not a duplicate and completes a3 again"
    );
    let reopened = PersistentLog::open(constellation, log.into_store()).expect("reopens");
    assert_eq!(reopened.log().len(), 3);
    assert_eq!(reopened.log().pending().count(), 0);
}

#[test]
fn a_dag_larger_than_the_pending_bound_reopens_whatever_the_row_order() {
    // A chain longer than twice the pending bound: rows come back in op-id order, so most of the
    // chain loads before its ancestors and waits at once.
    let links = 2 * core_membership::log::DEFAULT_PENDING_LIMIT + 100;
    let mut b = DagBuilder::default();
    b.add(&json!({"op": "g", "kind": "genesis", "issuer": "N1", "caps_add": "preset:first_device", "catalog_root": R0}));
    let mut parent = "g".to_owned();
    for n in 0..links {
        let label = format!("p{n}");
        b.add(&json!({"op": label, "kind": "policy_set", "issuer": "N1", "parents": [parent], "ts_ms": n, "policy": {"map": []}}));
        parent = label;
    }
    let constellation = b.constellation.expect("genesis");
    let mut log = PersistentLog::open(constellation, MemoryStore::default()).expect("opens");
    for op in b.ops() {
        assert!(matches!(log.insert(op, 1_000), Ok(Inserted::Stored(_))));
    }
    let reopened = PersistentLog::open(constellation, log.into_store()).expect("reopens");
    assert_eq!(reopened.log().len(), links + 1);
    assert_eq!(reopened.log().heads(), BTreeSet::from([b.id(&parent)]));
}
