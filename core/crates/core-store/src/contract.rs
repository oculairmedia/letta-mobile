//! The contract suite every [`Store`] implementation must pass.
//!
//! An implementation's test crate provides a [`StoreFixture`] factory and expands
//! [`store_contract_suite!`](crate::store_contract_suite), which writes one `#[test]` per case:
//!
//! ```ignore
//! core_store::store_contract_suite!(|| MyFixture::new());
//! ```
//!
//! Every case panics with a message naming the broken rule of `store-v1.md`.

// Every case reports a broken contract by panicking; that is the point of the module.
#![allow(clippy::missing_panics_doc)]

use std::collections::BTreeMap;

use core_proto::{AgentId, ConversationId, Epoch, NodeId};
use proptest::prelude::*;
use proptest::test_runner::{Config, TestRunner};

use crate::bindings::BindingKey;
use crate::ids::{BlobHash, DivergenceId, DocId, EntryHash, LogId, OpId};
use crate::kv::{Namespace, encode_key_path};
use crate::log::{
    DivergenceRecord, EpochClaim, FLAG_COMPACTABLE, HEADER_LEN, KIND_EPOCH_OPEN, PayloadState,
    PlacementState, SIGNATURE_LEN, SegmentState, StoredEntry, VotePromise,
};
use crate::membership::StoredOp;
use crate::registry::{CatalogDoc, DocState};
use crate::{FeedRow, MemoryStore, Store, StoreError, Txn};

/// Something that owns a store under test (and whatever the store needs alive, such as a
/// temporary directory).
pub trait StoreFixture {
    /// The store type.
    type Store: Store;
    /// The store under test.
    fn store(&self) -> &Self::Store;
}

impl StoreFixture for MemoryStore {
    type Store = Self;
    fn store(&self) -> &Self {
        self
    }
}

/// Expands to one `#[test]` per contract case, each on a fresh fixture from `$factory`.
#[macro_export]
macro_rules! store_contract_suite {
    ($factory:expr) => {
        $crate::store_contract_suite!(@cases $factory;
            kv_round_trip,
            kv_prefix_scan_is_ordered_and_namespaced,
            kv_key_path_prefix_scan,
            blob_round_trip,
            failed_write_leaves_nothing,
            panicking_write_leaves_nothing,
            read_inside_a_write_sees_the_committed_state,
            epoch_claim_commits_together,
            claim_at_or_below_the_placement_is_fenced,
            simultaneous_claims_resolve_to_the_higher_claimant,
            append_extends_the_open_segment,
            append_is_fenced_outside_the_open_segment,
            divergent_entries_are_kept_side_by_side,
            entries_are_immutable,
            prune_only_compactable_payloads,
            late_payload_attaches,
            placement_state_and_fallback,
            vote_promise_never_regresses,
            divergence_records_supersede,
            membership_ops,
            doc_registry,
            feed_index,
            bindings_are_node_local,
            kv_matches_a_model_under_aborts,
            claims_only_raise_the_placement
        );
    };
    (@cases $factory:expr; $($case:ident),*) => {
        $(
            #[test]
            fn $case() {
                $crate::contract::$case(&$factory);
            }
        )*
    };
}

// ---------------------------------------------------------------------------------------------
// Builders

/// A test node id: 32 copies of `byte`.
#[must_use]
pub const fn node(byte: u8) -> NodeId {
    NodeId::from_bytes([byte; 32])
}

/// A test log id: 32 copies of `byte`.
#[must_use]
pub const fn log(byte: u8) -> LogId {
    LogId::from_bytes([byte; 32])
}

/// A test epoch.
#[must_use]
pub const fn epoch(n: u64, claimant: u8) -> Epoch {
    Epoch {
        n,
        claimant: node(claimant),
    }
}

/// A deterministic 32-byte stand-in for a hash (the store never checks hashes).
#[must_use]
pub fn fake_hash(parts: &[u64]) -> [u8; 32] {
    let mut state = 0x9e37_79b9_7f4a_7c15_u64;
    for part in parts {
        state = splitmix(state ^ part);
    }
    let mut out = [0_u8; 32];
    for chunk in out.chunks_exact_mut(8) {
        state = splitmix(state);
        chunk.copy_from_slice(&state.to_be_bytes());
    }
    out
}

const fn splitmix(mut value: u64) -> u64 {
    value = value.wrapping_add(0x9e37_79b9_7f4a_7c15);
    value = (value ^ (value >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    value = (value ^ (value >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
    value ^ (value >> 31)
}

/// The payload a test entry carries.
#[must_use]
pub fn payload_for(log_id: &LogId, epoch: &Epoch, seq: u64, variant: u8) -> Vec<u8> {
    format!(
        "payload {} {} {} {variant}",
        log_id.as_bytes()[0],
        epoch.n,
        seq
    )
    .into_bytes()
}

/// A test entry. `variant` tells apart two entries at one position (divergence).
#[must_use]
pub fn entry(
    log_id: &LogId,
    epoch: &Epoch,
    seq: u64,
    prev: EntryHash,
    kind: u16,
    variant: u8,
) -> StoredEntry {
    let payload = payload_for(log_id, epoch, seq, variant);
    let hash = fake_hash(&[
        u64::from(log_id.as_bytes()[0]),
        epoch.n,
        u64::from(epoch.claimant.as_bytes()[0]),
        seq,
        u64::from(kind),
        u64::from(variant),
    ]);
    let mut header = Vec::with_capacity(HEADER_LEN);
    while header.len() < HEADER_LEN {
        header.extend_from_slice(&hash);
    }
    header.truncate(HEADER_LEN);
    StoredEntry {
        entry_hash: EntryHash::from_bytes(hash),
        log_id: *log_id,
        epoch: *epoch,
        seq,
        prev,
        kind,
        flags: 0,
        header,
        signature: [hash[0]; SIGNATURE_LEN],
        payload_hash: BlobHash::from_bytes(fake_hash(&[0xb10b, hash[0].into(), hash[1].into()])),
        payload_len: payload.len() as u64,
        payload_state: PayloadState::Missing,
    }
}

/// A claim opening `epoch` on top of `parent` (or genesis when `None`).
///
/// # Panics
///
/// Never for the inputs the suite uses.
#[must_use]
pub fn claim(log_id: &LogId, epoch: &Epoch, parent: Option<&StoredEntry>) -> EpochClaim {
    let (seq, prev) = parent.map_or((0, EntryHash::ZERO), |p| (p.seq + 1, p.entry_hash));
    let open = entry(log_id, epoch, seq, prev, KIND_EPOCH_OPEN, 0);
    let payload = payload_for(log_id, epoch, seq, 0);
    EpochClaim::new(agent(), open, payload).expect("a well-formed claim")
}

/// The agent every test log belongs to.
#[must_use]
pub fn agent() -> AgentId {
    AgentId::new("agent-contract")
}

/// A file-write entry following `prev`.
#[must_use]
pub fn next_entry(prev: &StoredEntry, variant: u8) -> StoredEntry {
    entry(
        &prev.log_id,
        &prev.epoch,
        prev.seq + 1,
        prev.entry_hash,
        0x0100,
        variant,
    )
}

fn append<S: Store>(store: &S, entry: &StoredEntry) {
    let payload = payload_for(&entry.log_id, &entry.epoch, entry.seq, 0);
    store
        .write(|txn| txn.append_canonical(entry, Some(&payload)))
        .expect("append");
}

fn read<S: Store, T>(store: &S, f: impl FnOnce(&dyn Txn) -> Result<T, StoreError>) -> T {
    store.read(f).expect("read")
}

const OTHER: Namespace = Namespace::new("contract.other");

// ---------------------------------------------------------------------------------------------
// KV and blobs

/// KV: put, get, replace and delete.
pub fn kv_round_trip<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    store
        .write(|txn| {
            txn.kv_put(Namespace::SAMOD, b"k", b"v1")?;
            txn.kv_put(Namespace::SAMOD, b"k", b"v2")?;
            txn.kv_put(Namespace::SAMOD, b"", b"empty key")
        })
        .expect("write");
    assert_eq!(
        read(store, |txn| txn.kv_get(Namespace::SAMOD, b"k")),
        Some(b"v2".to_vec()),
        "a put replaces"
    );
    assert_eq!(
        read(store, |txn| txn.kv_get(Namespace::SAMOD, b"")),
        Some(b"empty key".to_vec()),
        "the empty key is a key"
    );
    let deleted = store
        .write(|txn| {
            Ok::<_, StoreError>((
                txn.kv_delete(Namespace::SAMOD, b"k")?,
                txn.kv_delete(Namespace::SAMOD, b"k")?,
            ))
        })
        .expect("delete");
    assert_eq!(
        deleted,
        (true, false),
        "delete reports whether the key existed"
    );
    assert_eq!(read(store, |txn| txn.kv_get(Namespace::SAMOD, b"k")), None);
}

/// KV: scans are ordered bytewise and never cross namespaces.
pub fn kv_prefix_scan_is_ordered_and_namespaced<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    store
        .write(|txn| {
            for key in [&b"ab\xff"[..], b"ab", b"b", b"a", b"ab\x00", b"abc"] {
                txn.kv_put(Namespace::SAMOD, key, key)?;
            }
            txn.kv_put(OTHER, b"ab", b"other")
        })
        .expect("write");
    let keys: Vec<Vec<u8>> = read(store, |txn| txn.kv_scan_prefix(Namespace::SAMOD, b"ab"))
        .into_iter()
        .map(|(key, _)| key)
        .collect();
    assert_eq!(
        keys,
        vec![
            b"ab".to_vec(),
            b"ab\x00".to_vec(),
            b"abc".to_vec(),
            b"ab\xff".to_vec()
        ],
        "prefix scans are ordered bytewise"
    );
    assert_eq!(
        read(store, |txn| txn.kv_scan_prefix(Namespace::SAMOD, b"")).len(),
        6,
        "an empty prefix scans the namespace only"
    );
    assert_eq!(
        read(store, |txn| txn.kv_scan_prefix(OTHER, b"")),
        vec![(b"ab".to_vec(), b"other".to_vec())]
    );
}

/// KV: samod-shaped `load_range` over key paths returns exactly the extending paths.
pub fn kv_key_path_prefix_scan<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    let paths: [&[&str]; 4] = [
        &["doc1", "incremental", "h1"],
        &["doc1", "snapshot", "h2"],
        &["doc10", "snapshot", "h3"],
        &["doc1"],
    ];
    store
        .write(|txn| {
            for path in paths {
                txn.kv_put(Namespace::SAMOD, &encode_key_path(path), b"chunk")?;
            }
            Ok::<_, StoreError>(())
        })
        .expect("write");
    let hits = read(store, |txn| {
        txn.kv_scan_prefix(Namespace::SAMOD, &encode_key_path(&["doc1"]))
    });
    assert_eq!(
        hits.len(),
        3,
        "doc1's prefix covers doc1's paths and not doc10's"
    );
}

/// Blobs: idempotent put, length conflict, delete.
pub fn blob_round_trip<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    let hash = BlobHash::from_bytes(fake_hash(&[1]));
    let big = vec![0x5a_u8; 300_000];
    let first = store
        .write(|txn| Ok::<_, StoreError>((txn.put_blob(&hash, &big)?, txn.put_blob(&hash, &big)?)))
        .expect("put");
    assert_eq!(first, (true, false), "a second put of one blob is a no-op");
    assert_eq!(read(store, |txn| txn.blob_len(&hash)), Some(300_000));
    assert_eq!(read(store, |txn| txn.blob(&hash)), Some(big));
    let conflict = store.write(|txn| txn.put_blob(&hash, b"short"));
    assert!(
        matches!(conflict, Err(StoreError::Conflict(_))),
        "another length under one hash is a conflict"
    );
    assert!(store.write(|txn| txn.delete_blob(&hash)).expect("delete"));
    assert_eq!(read(store, |txn| txn.blob(&hash)), None);
}

// ---------------------------------------------------------------------------------------------
// Transactions

/// A closure that fails leaves no trace in any area.
pub fn failed_write_leaves_nothing<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    let log_id = log(1);
    let result: Result<(), StoreError> = store.write(|txn| {
        txn.kv_put(Namespace::SAMOD, b"k", b"v")?;
        txn.put_blob(&BlobHash::from_bytes([7; 32]), b"blob")?;
        txn.adopt_epoch(claim(&log_id, &epoch(1, 1), None))?;
        txn.put_op(&op(1, &[]))?;
        txn.register_doc(&doc(1))?;
        txn.put_feed_row(&feed_row("c", 1))?;
        Err(StoreError::Invalid("caller aborts".into()))
    });
    assert!(result.is_err());
    read(store, |txn| {
        assert_eq!(txn.kv_get(Namespace::SAMOD, b"k")?, None, "kv rolled back");
        assert_eq!(
            txn.blob(&BlobHash::from_bytes([7; 32]))?,
            None,
            "blob rolled back"
        );
        assert_eq!(txn.placement(&log_id)?, None, "placement rolled back");
        assert!(txn.segments(&log_id)?.is_empty(), "segment rolled back");
        assert!(txn.logs()?.is_empty(), "entry rolled back");
        assert!(txn.ops()?.is_empty(), "op rolled back");
        assert!(txn.catalog_docs()?.is_empty(), "registry rolled back");
        assert_eq!(
            txn.feed_head(&ConversationId::new("c"))?,
            None,
            "feed rolled back"
        );
        Ok(())
    });
}

/// A closure that panics (the closest a test gets to a crash mid-transaction) leaves no trace, and
/// the store keeps working.
pub fn panicking_write_leaves_nothing<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let _: Result<(), StoreError> = store.write(|txn| {
            txn.kv_put(Namespace::SAMOD, b"torn", b"v")?;
            txn.adopt_epoch(claim(&log(30), &epoch(1, 1), None))?;
            panic!("crash inside the transaction");
        });
    }));
    assert!(outcome.is_err());
    assert_eq!(
        read(store, |txn| txn.kv_get(Namespace::SAMOD, b"torn")),
        None
    );
    assert_eq!(read(store, |txn| txn.placement(&log(30))), None);
    store
        .write(|txn| txn.kv_put(Namespace::SAMOD, b"after", b"v"))
        .expect("the store still takes writes");
    assert!(read(store, |txn| txn.kv_get(Namespace::SAMOD, b"after")).is_some());
}

/// A read made from inside a write closure sees the last committed state, not the open
/// transaction, and does not deadlock.
pub fn read_inside_a_write_sees_the_committed_state<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    store
        .write(|txn| txn.kv_put(Namespace::SAMOD, b"k", b"old"))
        .expect("seed");
    let seen = store
        .write(|txn| {
            txn.kv_put(Namespace::SAMOD, b"k", b"new")?;
            assert_eq!(
                txn.kv_get(Namespace::SAMOD, b"k")?,
                Some(b"new".to_vec()),
                "a txn reads its own writes"
            );
            store.read(|outside| outside.kv_get(Namespace::SAMOD, b"k"))
        })
        .expect("write");
    assert_eq!(seen, Some(b"old".to_vec()));
    assert_eq!(
        read(store, |txn| txn.kv_get(Namespace::SAMOD, b"k")),
        Some(b"new".to_vec())
    );
}
// ---------------------------------------------------------------------------------------------
// Log: claims and appends

/// An epoch claim lands its entry, payload, segment and placement together.
pub fn epoch_claim_commits_together<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    let log_id = log(2);
    let genesis = claim(&log_id, &epoch(1, 1), None);
    store.commit_epoch_claim(genesis.clone()).expect("claim");
    read(store, |txn| {
        let placement = txn.placement(&log_id)?.expect("placement row");
        assert_eq!(placement.epoch, epoch(1, 1));
        assert_eq!(placement.holder(), node(1));
        assert_eq!(placement.state, PlacementState::Active);
        assert_eq!(placement.agent_id, agent());
        assert_eq!(placement.open_entry_hash, genesis.entry().entry_hash);
        let segment = txn.segment(&log_id, &epoch(1, 1))?.expect("segment row");
        assert_eq!(segment, genesis.segment());
        assert_eq!(segment.parent, None, "genesis has no fork point");
        let stored = txn.entry(&genesis.entry().entry_hash)?.expect("entry");
        assert_eq!(stored.payload_state, PayloadState::Stored);
        assert_eq!(
            txn.blob(&stored.payload_hash)?,
            Some(payload_for(&log_id, &epoch(1, 1), 0, 0)),
            "the epoch_open payload is stored"
        );
        assert_eq!(txn.max_n_seen(&log_id)?, Some(1));
        assert_eq!(txn.logs()?, vec![log_id]);
        Ok(())
    });
}

/// A claim at or below the current placement is refused and writes nothing.
pub fn claim_at_or_below_the_placement_is_fenced<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    let log_id = log(3);
    let genesis = claim(&log_id, &epoch(1, 5), None);
    store.commit_epoch_claim(genesis.clone()).expect("genesis");
    let second = claim(&log_id, &epoch(2, 5), Some(genesis.entry()));
    store
        .commit_epoch_claim(second.clone())
        .expect("higher claim");
    for stale in [
        claim(&log_id, &epoch(2, 5), Some(genesis.entry())),
        claim(&log_id, &epoch(2, 4), Some(genesis.entry())),
        claim(&log_id, &epoch(1, 9), Some(genesis.entry())),
    ] {
        let result = store.commit_epoch_claim(stale.clone());
        assert!(
            matches!(result, Err(StoreError::Fenced { canonical: Some(e), .. }) if e == epoch(2, 5)),
            "a claim at or below the placement is fenced: {result:?}"
        );
        if stale.entry().entry_hash != second.entry().entry_hash {
            assert_eq!(
                read(store, |txn| txn.entry(&stale.entry().entry_hash)),
                None,
                "a fenced claim leaves no entry"
            );
        }
    }
    assert_eq!(
        read(store, |txn| txn.placement(&log_id)).map(|p| p.epoch),
        Some(epoch(2, 5))
    );
}

/// Two claims with equal n: the higher claimant wins on every replica, whatever the order.
pub fn simultaneous_claims_resolve_to_the_higher_claimant<F: StoreFixture>(
    factory: &impl Fn() -> F,
) {
    for order in [[1_u8, 2], [2, 1]] {
        let fixture = factory();
        let store = fixture.store();
        let log_id = log(4);
        let genesis = claim(&log_id, &epoch(1, 9), None);
        store.commit_epoch_claim(genesis.clone()).expect("genesis");
        for claimant in order {
            let competing = claim(&log_id, &epoch(2, claimant), Some(genesis.entry()));
            let result = store.commit_epoch_claim(competing.clone());
            if result.is_err() {
                // The loser is still stored, as a divergent segment, by the caller.
                store
                    .write(|txn| txn.put_entry(competing.entry()))
                    .expect("store the losing claim");
            }
        }
        read(store, |txn| {
            assert_eq!(
                txn.placement(&log_id)?.map(|p| p.epoch),
                Some(epoch(2, 2)),
                "the higher claimant wins for order {order:?}"
            );
            assert_eq!(
                txn.entries_at(&log_id, 1)?.len(),
                2,
                "both claims are stored"
            );
            Ok(())
        });
    }
}

/// Appends extend the open segment and advance head and complete_seq.
pub fn append_extends_the_open_segment<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    let log_id = log(5);
    let genesis = claim(&log_id, &epoch(1, 1), None);
    store.commit_epoch_claim(genesis.clone()).expect("genesis");
    let first = next_entry(genesis.entry(), 0);
    append(store, &first);
    let second = next_entry(&first, 0);
    // The second arrives without its payload: head moves, complete_seq does not.
    store
        .write(|txn| txn.append_canonical(&second, None))
        .expect("append without payload");
    let segment = read(store, |txn| txn.segment(&log_id, &epoch(1, 1))).expect("segment");
    assert_eq!(segment.head_seq, 2);
    assert_eq!(segment.head_hash, second.entry_hash);
    assert_eq!(
        segment.complete_seq,
        Some(1),
        "a missing payload holds complete_seq back"
    );
    let entries = read(store, |txn| {
        txn.segment_entries(&log_id, &epoch(1, 1), 1, 10)
    });
    assert_eq!(
        entries.iter().map(|e| e.seq).collect::<Vec<_>>(),
        vec![1, 2],
        "segment entries come back in seq order from from_seq"
    );
    assert_eq!(
        read(store, |txn| txn.segment_entries(
            &log_id,
            &epoch(1, 1),
            0,
            1
        ))
        .len(),
        1,
        "limit applies"
    );
}

/// Appends outside the open segment of the current placement are refused (fencing F4).
pub fn append_is_fenced_outside_the_open_segment<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    let log_id = log(6);
    let no_placement = store.write(|txn| {
        txn.append_canonical(
            &entry(
                &log_id,
                &epoch(1, 1),
                1,
                EntryHash::from_bytes([1; 32]),
                0x100,
                0,
            ),
            None,
        )
    });
    assert!(
        matches!(no_placement, Err(StoreError::Fenced { .. })),
        "no placement, no append"
    );

    let genesis = claim(&log_id, &epoch(1, 1), None);
    store.commit_epoch_claim(genesis.clone()).expect("genesis");
    let first = next_entry(genesis.entry(), 0);

    let gap = entry(&log_id, &epoch(1, 1), 2, first.entry_hash, 0x100, 0);
    assert!(
        matches!(
            store.write(|txn| txn.append_canonical(&gap, None)),
            Err(StoreError::Invalid(_))
        ),
        "an append must follow the head"
    );
    let open_again = entry(
        &log_id,
        &epoch(1, 1),
        1,
        genesis.entry().entry_hash,
        KIND_EPOCH_OPEN,
        0,
    );
    assert!(
        matches!(
            store.write(|txn| txn.append_canonical(&open_again, None)),
            Err(StoreError::Invalid(_))
        ),
        "an epoch_open cannot be appended"
    );

    // A higher epoch number is seen (stored as a received entry): the holder must stop.
    let higher = entry(
        &log_id,
        &epoch(2, 7),
        1,
        genesis.entry().entry_hash,
        KIND_EPOCH_OPEN,
        0,
    );
    store
        .write(|txn| txn.put_entry(&higher))
        .expect("store a higher claim");
    assert!(
        matches!(
            store.write(|txn| txn.append_canonical(&first, None)),
            Err(StoreError::Fenced {
                max_n_seen: Some(2),
                ..
            })
        ),
        "seeing a higher n fences the old holder"
    );

    // A sealed placement takes no appends either.
    let other = log(7);
    let genesis = claim(&other, &epoch(1, 1), None);
    store.commit_epoch_claim(genesis.clone()).expect("genesis");
    store
        .write(|txn| {
            txn.set_placement_state(&other, &epoch(1, 1), PlacementState::SealedTo(epoch(2, 2)))
        })
        .expect("seal");
    assert!(
        matches!(
            store.write(|txn| txn.append_canonical(&next_entry(genesis.entry(), 0), None)),
            Err(StoreError::Fenced { .. })
        ),
        "a sealed placement takes no appends"
    );
}

/// Two different entries at one position are both stored (divergence evidence).
pub fn divergent_entries_are_kept_side_by_side<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    let log_id = log(8);
    let genesis = claim(&log_id, &epoch(1, 1), None);
    store.commit_epoch_claim(genesis.clone()).expect("genesis");
    let a = next_entry(genesis.entry(), 1);
    let b = next_entry(genesis.entry(), 2);
    store
        .write(|txn| {
            txn.put_entry(&a)?;
            txn.put_entry(&b)
        })
        .expect("store both");
    let mut expected = vec![a.entry_hash, b.entry_hash];
    expected.sort();
    let at = read(store, |txn| txn.entries_at(&log_id, 1));
    assert_eq!(
        at.iter().map(|e| e.entry_hash).collect::<Vec<_>>(),
        expected,
        "entries at one seq come back ordered by hash"
    );
    // The caller records b's line as a divergent segment of a losing epoch; rows come back by epoch.
    let losing = crate::log::Segment {
        log_id,
        epoch: epoch(1, 0),
        start_seq: 1,
        parent: Some(genesis.entry().entry_hash),
        head_seq: 1,
        head_hash: b.entry_hash,
        complete_seq: None,
        state: SegmentState::Divergent,
    };
    store
        .write(|txn| txn.put_segment(&losing))
        .expect("segment");
    let segments = read(store, |txn| txn.segments(&log_id));
    assert_eq!(
        segments.iter().map(|s| s.epoch).collect::<Vec<_>>(),
        vec![epoch(1, 0), epoch(1, 1)],
        "segments come back ordered by epoch"
    );
    assert_eq!(segments[0], losing);
}

/// Stored entries never change: same hash with another header is a conflict, the same entry again
/// is a no-op.
pub fn entries_are_immutable<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    let original = entry(
        &log(9),
        &epoch(1, 1),
        0,
        EntryHash::ZERO,
        KIND_EPOCH_OPEN,
        0,
    );
    assert!(store.write(|txn| txn.put_entry(&original)).expect("put"));
    assert!(
        !store.write(|txn| txn.put_entry(&original)).expect("again"),
        "re-put is a no-op"
    );
    let mut forged = original.clone();
    forged.header[0] ^= 1;
    assert!(matches!(
        store.write(|txn| txn.put_entry(&forged)),
        Err(StoreError::Conflict(_))
    ));
    let mut short = original.clone();
    short.header.truncate(100);
    short.entry_hash = EntryHash::from_bytes([0xee; 32]);
    assert!(matches!(
        store.write(|txn| txn.put_entry(&short)),
        Err(StoreError::Invalid(_))
    ));
    let mut bad_prev = original;
    bad_prev.entry_hash = EntryHash::from_bytes([0xef; 32]);
    bad_prev.prev = EntryHash::from_bytes([1; 32]);
    assert!(
        matches!(
            store.write(|txn| txn.put_entry(&bad_prev)),
            Err(StoreError::Invalid(_))
        ),
        "seq 0 names the zero prev"
    );
}

/// Only compactable payloads are pruned, and a blob shared by another stored entry survives.
pub fn prune_only_compactable_payloads<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    let log_id = log(10);
    let genesis = claim(&log_id, &epoch(1, 1), None);
    store.commit_epoch_claim(genesis.clone()).expect("genesis");
    assert!(
        matches!(
            store.write(|txn| txn.prune_payload(&genesis.entry().entry_hash)),
            Err(StoreError::Invalid(_))
        ),
        "a non-compactable payload is never pruned"
    );
    let mut feed = next_entry(genesis.entry(), 0);
    feed.kind = 0x0300;
    feed.flags = FLAG_COMPACTABLE;
    let mut twin = entry(
        &log_id,
        &epoch(1, 1),
        1,
        genesis.entry().entry_hash,
        0x0300,
        9,
    );
    twin.flags = FLAG_COMPACTABLE;
    twin.payload_hash = feed.payload_hash;
    twin.payload_len = feed.payload_len;
    let payload = payload_for(&log_id, &epoch(1, 1), 1, 0);
    store
        .write(|txn| {
            txn.append_canonical(&feed, Some(&payload))?;
            txn.put_entry(&twin)?;
            txn.attach_payload(&twin.entry_hash, &payload)
        })
        .expect("two entries share a payload");
    store
        .write(|txn| txn.prune_payload(&feed.entry_hash))
        .expect("prune");
    read(store, |txn| {
        assert_eq!(
            txn.entry(&feed.entry_hash)?.map(|e| e.payload_state),
            Some(PayloadState::Pruned)
        );
        assert!(
            txn.blob(&feed.payload_hash)?.is_some(),
            "still referenced by the twin"
        );
        Ok(())
    });
    store
        .write(|txn| txn.prune_payload(&twin.entry_hash))
        .expect("prune twin");
    assert_eq!(
        read(store, |txn| txn.blob(&feed.payload_hash)),
        None,
        "unreferenced blob is gone"
    );
    assert!(
        read(store, |txn| txn.entry(&feed.entry_hash)).is_some(),
        "headers are kept for ever"
    );
}

/// A payload that arrives after its entry attaches, with its length checked.
pub fn late_payload_attaches<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    let received = entry(
        &log(11),
        &epoch(1, 1),
        0,
        EntryHash::ZERO,
        KIND_EPOCH_OPEN,
        0,
    );
    store
        .write(|txn| txn.put_entry(&received))
        .expect("header first");
    assert!(matches!(
        store.write(|txn| txn.attach_payload(&received.entry_hash, b"wrong length")),
        Err(StoreError::Invalid(_))
    ));
    let payload = payload_for(&log(11), &epoch(1, 1), 0, 0);
    store
        .write(|txn| txn.attach_payload(&received.entry_hash, &payload))
        .expect("attach");
    assert_eq!(
        read(store, |txn| txn.entry(&received.entry_hash)).map(|e| e.payload_state),
        Some(PayloadState::Stored)
    );
    assert!(matches!(
        store.write(|txn| txn.attach_payload(&EntryHash::from_bytes([3; 32]), b"")),
        Err(StoreError::NotFound(_))
    ));
}

/// Placement state changes only at the current epoch; a fallback needs a stored lower segment.
pub fn placement_state_and_fallback<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    let log_id = log(12);
    let genesis = claim(&log_id, &epoch(1, 1), None);
    store.commit_epoch_claim(genesis.clone()).expect("genesis");
    let second = claim(&log_id, &epoch(2, 2), Some(genesis.entry()));
    store.commit_epoch_claim(second).expect("second");
    assert!(
        matches!(
            store.write(|txn| txn.set_placement_state(
                &log_id,
                &epoch(1, 1),
                PlacementState::Placeless
            )),
            Err(StoreError::Fenced { .. })
        ),
        "state changes only at the current epoch"
    );
    store
        .write(|txn| {
            txn.set_placement_state(&log_id, &epoch(2, 2), PlacementState::SupersededUnknown)
        })
        .expect("state");
    assert!(matches!(
        store.write(|txn| txn.fall_back_placement(&log_id, &epoch(3, 3), PlacementState::Active)),
        Err(StoreError::Invalid(_))
    ));
    assert!(matches!(
        store.write(|txn| txn.fall_back_placement(&log_id, &epoch(1, 9), PlacementState::Active)),
        Err(StoreError::NotFound(_))
    ));
    store
        .write(|txn| {
            let mut old = txn.segment(&log_id, &epoch(1, 1))?.expect("segment");
            old.state = SegmentState::Open;
            txn.put_segment(&old)?;
            txn.fall_back_placement(&log_id, &epoch(1, 1), PlacementState::Active)
        })
        .expect("fallback");
    let placement = read(store, |txn| txn.placement(&log_id)).expect("placement");
    assert_eq!(placement.epoch, epoch(1, 1));
    assert_eq!(placement.open_entry_hash, genesis.entry().entry_hash);
    assert_eq!(
        read(store, |txn| txn.max_n_seen(&log_id)),
        Some(2),
        "max_n_seen never falls"
    );
    assert_eq!(read(store, |txn| txn.placements()).len(), 1);
}

/// Vote promises never move down, and one epoch has one fork point.
pub fn vote_promise_never_regresses<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    let promise = VotePromise {
        log_id: log(13),
        epoch: epoch(3, 1),
        parent_seq: 10,
        parent_hash: EntryHash::from_bytes([1; 32]),
    };
    store
        .write(|txn| txn.set_vote_promise(&promise))
        .expect("promise");
    store
        .write(|txn| txn.set_vote_promise(&promise))
        .expect("same promise again");
    let lower = VotePromise {
        epoch: epoch(2, 9),
        ..promise.clone()
    };
    assert!(matches!(
        store.write(|txn| txn.set_vote_promise(&lower)),
        Err(StoreError::Fenced { .. })
    ));
    let other_parent = VotePromise {
        parent_seq: 11,
        ..promise.clone()
    };
    assert!(matches!(
        store.write(|txn| txn.set_vote_promise(&other_parent)),
        Err(StoreError::Conflict(_))
    ));
    let higher = VotePromise {
        epoch: epoch(4, 1),
        parent_seq: 12,
        ..promise
    };
    store
        .write(|txn| txn.set_vote_promise(&higher))
        .expect("higher");
    assert_eq!(read(store, |txn| txn.vote_promise(&log(13))), Some(higher));
}

/// A divergence record with the same id supersedes the earlier one.
pub fn divergence_records_supersede<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    let mut record = DivergenceRecord {
        id: DivergenceId::from_bytes([4; 32]),
        log_id: log(14),
        losing_epoch: epoch(2, 1),
        fork_seq: 5,
        tail_head_seq: 7,
        recorded_entry: None,
    };
    store
        .write(|txn| txn.put_divergence(&record))
        .expect("record");
    record.tail_head_seq = 9;
    record.recorded_entry = Some(EntryHash::from_bytes([5; 32]));
    store
        .write(|txn| txn.put_divergence(&record))
        .expect("supersede");
    assert_eq!(
        read(store, |txn| txn.divergence(&record.id)),
        Some(record.clone())
    );
    assert_eq!(read(store, |txn| txn.divergences(&log(14))), vec![record]);
    assert!(read(store, |txn| txn.divergences(&log(15))).is_empty());
}

// ---------------------------------------------------------------------------------------------
// Membership, registry, feed, bindings

fn op(byte: u8, parents: &[u8]) -> StoredOp {
    StoredOp {
        op_id: OpId::from_bytes([byte; 32]),
        envelope: vec![byte; 40],
        issuer: node(1),
        kind: u8::from(!parents.is_empty()),
        parents: parents.iter().map(|p| OpId::from_bytes([*p; 32])).collect(),
        received_at_ms: 1_700_000_000_000,
    }
}

/// Membership ops: idempotent, immutable, parents sorted, heads computed.
pub fn membership_ops<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    store
        .write(|txn| {
            assert!(txn.put_op(&op(1, &[]))?);
            assert!(txn.put_op(&op(2, &[1]))?);
            assert!(txn.put_op(&op(3, &[1]))?);
            assert!(!txn.put_op(&op(3, &[1]))?, "re-put is a no-op");
            Ok::<_, StoreError>(())
        })
        .expect("ops");
    let ids = |ops: Vec<OpId>| {
        ops.into_iter()
            .map(|id| id.as_bytes()[0])
            .collect::<Vec<_>>()
    };
    assert_eq!(ids(read(store, |txn| txn.op_heads())), vec![2, 3]);
    store
        .write(|txn| txn.put_op(&op(4, &[2, 3])))
        .expect("merge op");
    assert_eq!(ids(read(store, |txn| txn.op_heads())), vec![4]);
    assert_eq!(
        read(store, |txn| txn.op(&OpId::from_bytes([4; 32]))),
        Some(op(4, &[2, 3]))
    );
    assert_eq!(read(store, |txn| txn.ops()).len(), 4);
    let mut forged = op(2, &[1]);
    forged.envelope[0] ^= 1;
    assert!(matches!(
        store.write(|txn| txn.put_op(&forged)),
        Err(StoreError::Conflict(_))
    ));
    assert!(matches!(
        store.write(|txn| txn.put_op(&op(5, &[3, 2]))),
        Err(StoreError::Invalid(_))
    ));
}

fn doc(byte: u8) -> CatalogDoc {
    CatalogDoc {
        doc_id: DocId::from_bytes([byte; 16]),
        class: "agent".into(),
        agent_id: Some(AgentId::new(format!("agent-{byte}"))),
        state: DocState::Live,
        registered_at_ms: 1_700_000_000_000,
    }
}

/// The doc registry: upsert, state changes, removal.
pub fn doc_registry<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    let mut constellation = doc(2);
    constellation.class = "constellation".into();
    constellation.agent_id = None;
    store
        .write(|txn| {
            txn.register_doc(&doc(1))?;
            txn.register_doc(&constellation)
        })
        .expect("register");
    store
        .write(|txn| txn.set_doc_state(&DocId::from_bytes([1; 16]), DocState::Superseded))
        .expect("state");
    let docs = read(store, |txn| txn.catalog_docs());
    assert_eq!(docs.len(), 2);
    assert_eq!(docs[0].state, DocState::Superseded);
    assert_eq!(docs[1], constellation);
    assert!(matches!(
        store.write(|txn| txn.set_doc_state(&DocId::from_bytes([9; 16]), DocState::Retired)),
        Err(StoreError::NotFound(_))
    ));
    assert!(
        store
            .write(|txn| txn.remove_doc(&DocId::from_bytes([1; 16])))
            .expect("remove")
    );
    assert_eq!(
        read(store, |txn| txn.catalog_doc(&DocId::from_bytes([1; 16]))),
        None
    );
}

fn feed_row(conversation: &str, seq: u64) -> FeedRow {
    FeedRow {
        conversation_id: ConversationId::new(conversation),
        feed_seq: seq,
        entry_hash: EntryHash::from_bytes(fake_hash(&[seq])),
    }
}

/// The feed index: paging, head, retention and truncation, per conversation.
pub fn feed_index<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    store
        .write(|txn| {
            for seq in 1..=6 {
                txn.put_feed_row(&feed_row("c1", seq))?;
            }
            txn.put_feed_row(&feed_row("c2", 100))
        })
        .expect("rows");
    let c1 = ConversationId::new("c1");
    let page = read(store, |txn| txn.feed_rows(&c1, Some(2), 3));
    assert_eq!(
        page.iter().map(|r| r.feed_seq).collect::<Vec<_>>(),
        vec![3, 4, 5]
    );
    assert_eq!(read(store, |txn| txn.feed_rows(&c1, None, 100)).len(), 6);
    assert_eq!(read(store, |txn| txn.feed_head(&c1)), Some(6));
    assert!(
        store
            .write(|txn| txn.delete_feed_row(&c1, 1))
            .expect("delete")
    );
    assert_eq!(
        store
            .write(|txn| txn.truncate_feed_after(&c1, 4))
            .expect("truncate"),
        2
    );
    assert_eq!(
        read(store, |txn| txn.feed_rows(&c1, None, 100))
            .iter()
            .map(|r| r.feed_seq)
            .collect::<Vec<_>>(),
        vec![2, 3, 4]
    );
    assert_eq!(
        read(store, |txn| txn.feed_head(&ConversationId::new("c2"))),
        Some(100)
    );
    let mut replaced = feed_row("c1", 4);
    replaced.entry_hash = EntryHash::from_bytes([0xaa; 32]);
    store
        .write(|txn| txn.put_feed_row(&replaced))
        .expect("replace");
    assert_eq!(
        read(store, |txn| txn.feed_rows(&c1, Some(3), 1)),
        vec![replaced]
    );
}

/// Bindings: typed keys, durable single writes, invisible to the replicated areas.
pub fn bindings_are_node_local<F: StoreFixture>(factory: &impl Fn() -> F) {
    let fixture = factory();
    let store = fixture.store();
    let bindings = store.bindings();
    let profile = BindingKey::ModelProfile("profile/default".into());
    let workspace = BindingKey::WorkspacePath("ws-1".into());
    bindings.set_binding(&profile, "handle-a").expect("set");
    bindings.set_binding(&profile, "handle-b").expect("replace");
    bindings
        .set_binding(&workspace, "C:\\work\\ws-1")
        .expect("set");
    bindings
        .set_binding(&BindingKey::HooksDir, "/home/me/.hooks")
        .expect("set");
    assert_eq!(
        bindings.binding(&profile).expect("get"),
        Some("handle-b".into())
    );
    assert_eq!(
        bindings.bindings().expect("list"),
        vec![
            (BindingKey::HooksDir, "/home/me/.hooks".into()),
            (profile.clone(), "handle-b".into()),
            (workspace, "C:\\work\\ws-1".into()),
        ]
    );
    assert!(bindings.remove_binding(&profile).expect("remove"));
    assert!(!bindings.remove_binding(&profile).expect("remove again"));
    assert!(matches!(
        bindings.set_binding(&BindingKey::ModelProfile(String::new()), "x"),
        Err(StoreError::Invalid(_))
    ));
    // Nothing a binding write does is visible to a replicated transaction.
    read(store, |txn| {
        assert!(txn.kv_scan_prefix(Namespace::SAMOD, b"")?.is_empty());
        assert!(txn.logs()?.is_empty());
        Ok(())
    });
}

// ---------------------------------------------------------------------------------------------
// Properties

#[derive(Clone, Debug)]
enum KvOp {
    Put(bool, Vec<u8>, Vec<u8>),
    Delete(bool, Vec<u8>),
}

fn kv_op() -> impl Strategy<Value = KvOp> {
    let key = proptest::collection::vec(0_u8..4, 0..3);
    prop_oneof![
        (
            any::<bool>(),
            key.clone(),
            proptest::collection::vec(any::<u8>(), 0..4)
        )
            .prop_map(|(space, key, value)| KvOp::Put(space, key, value)),
        (any::<bool>(), key).prop_map(|(space, key)| KvOp::Delete(space, key)),
    ]
}

const fn space(other: bool) -> Namespace {
    if other { OTHER } else { Namespace::SAMOD }
}

/// Property: after any sequence of committed and aborted transactions, KV equals a model that
/// applied only the committed ones.
pub fn kv_matches_a_model_under_aborts<F: StoreFixture>(factory: &impl Fn() -> F) {
    let strategy = proptest::collection::vec(
        (proptest::collection::vec(kv_op(), 0..6), any::<bool>()),
        0..8,
    );
    let mut runner = TestRunner::new(Config {
        cases: 32,
        ..Config::default()
    });
    runner
        .run(&strategy, |transactions| {
            let fixture = factory();
            let store = fixture.store();
            let mut model: BTreeMap<(bool, Vec<u8>), Vec<u8>> = BTreeMap::new();
            for (ops, commit) in transactions {
                let result: Result<(), StoreError> = store.write(|txn| {
                    for op in &ops {
                        match op {
                            KvOp::Put(other, key, value) => {
                                txn.kv_put(space(*other), key, value)?;
                            }
                            KvOp::Delete(other, key) => {
                                txn.kv_delete(space(*other), key)?;
                            }
                        }
                    }
                    if commit {
                        Ok(())
                    } else {
                        Err(StoreError::Invalid("abort".into()))
                    }
                });
                prop_assert_eq!(result.is_ok(), commit);
                if commit {
                    for op in ops {
                        match op {
                            KvOp::Put(other, key, value) => {
                                model.insert((other, key), value);
                            }
                            KvOp::Delete(other, key) => {
                                model.remove(&(other, key));
                            }
                        }
                    }
                }
            }
            for other in [false, true] {
                let stored = store
                    .read(|txn| txn.kv_scan_prefix(space(other), b""))
                    .expect("scan");
                let expected: Vec<(Vec<u8>, Vec<u8>)> = model
                    .iter()
                    .filter(|((o, _), _)| *o == other)
                    .map(|((_, key), value)| (key.clone(), value.clone()))
                    .collect();
                prop_assert_eq!(stored, expected);
            }
            Ok(())
        })
        .expect("kv matches the model");
}

/// Property: for any order of claims, the placement ends at the highest epoch offered, and every
/// fenced claim left nothing behind.
pub fn claims_only_raise_the_placement<F: StoreFixture>(factory: &impl Fn() -> F) {
    let strategy = proptest::collection::vec((1_u64..5, 1_u8..4), 1..8);
    let mut runner = TestRunner::new(Config {
        cases: 32,
        ..Config::default()
    });
    runner
        .run(&strategy, |epochs| {
            let fixture = factory();
            let store = fixture.store();
            let log_id = log(20);
            let genesis = claim(&log_id, &epoch(0, 0), None);
            store.commit_epoch_claim(genesis.clone()).expect("genesis");
            let mut highest = epoch(0, 0);
            for (n, claimant) in epochs {
                let offered = epoch(n, claimant);
                let result =
                    store.commit_epoch_claim(claim(&log_id, &offered, Some(genesis.entry())));
                prop_assert_eq!(result.is_ok(), offered > highest);
                highest = highest.max(offered);
                let placement = store.read(|txn| txn.placement(&log_id)).expect("read");
                prop_assert_eq!(placement.map(|p| p.epoch), Some(highest));
            }
            let stored = store.read(|txn| txn.entries_at(&log_id, 1)).expect("read");
            prop_assert!(stored.iter().all(|e| e.epoch <= highest));
            let segments = store.read(|txn| txn.segments(&log_id)).expect("read");
            prop_assert_eq!(
                segments.len(),
                stored.len() + 1,
                "one segment per adopted claim"
            );
            Ok(())
        })
        .expect("claims only raise the placement");
}
