//! The in-memory [`Store`]: for tests, the simulator and the wasm client, which is not a store
//! node and keeps nothing across restarts.
//!
//! A write transaction works on a copy of the state and swaps it in on success, so a failed
//! closure leaves nothing behind. Blobs are shared between copies, so the copy costs the index
//! sizes, not the payload bytes.

use std::borrow::Cow;
use std::collections::{BTreeMap, BTreeSet};
use std::ops::Bound;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};

use core_proto::{ConversationId, Epoch, NodeId};

use crate::bindings::{BindingKey, BindingsStore};
use crate::blob::BlobStore;
use crate::error::StoreError;
use crate::feed::{FeedJournal, FeedRow};
use crate::ids::{BlobHash, DivergenceId, DocId, EntryHash, LogId, OpId};
use crate::kv::{KvEntry, KvStore, Namespace};
use crate::log::{
    DivergenceRecord, LogStore, PayloadState, Placement, Segment, StoredEntry, VotePromise,
    check_promise,
};
use crate::membership::{MembershipStore, StoredOp, check_parents};
use crate::registry::{CatalogDoc, DocRegistry, DocState};
use crate::{SCHEMA_VERSION, Store, Txn};

/// The lowest epoch, used as a range bound.
const MIN_EPOCH: Epoch = Epoch {
    n: 0,
    claimant: NodeId::from_bytes([0; 32]),
};
const MAX_EPOCH: Epoch = Epoch {
    n: u64::MAX,
    claimant: NodeId::from_bytes([0xff; 32]),
};

#[derive(Clone, Default)]
struct State {
    entries: BTreeMap<EntryHash, StoredEntry>,
    by_seq: BTreeSet<(LogId, u64, EntryHash)>,
    by_segment: BTreeSet<(LogId, Epoch, u64, EntryHash)>,
    blobs: BTreeMap<BlobHash, Arc<[u8]>>,
    segments: BTreeMap<(LogId, Epoch), Segment>,
    placements: BTreeMap<LogId, Placement>,
    divergences: BTreeMap<DivergenceId, DivergenceRecord>,
    promises: BTreeMap<LogId, VotePromise>,
    ops: BTreeMap<OpId, StoredOp>,
    docs: BTreeMap<DocId, CatalogDoc>,
    feed: BTreeMap<(ConversationId, u64), EntryHash>,
    kv: BTreeMap<(Namespace, Vec<u8>), Vec<u8>>,
}

/// A [`Store`] held entirely in memory.
///
/// Writers are serialized; a read (also one made from inside a write closure) sees the last
/// committed state, as with a WAL database.
#[derive(Default)]
pub struct MemoryStore {
    writer: Mutex<()>,
    state: Mutex<Arc<State>>,
    bindings: MemoryBindings,
}

impl MemoryStore {
    /// An empty store.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// A copy of the committed state, as a backup taken now would hold it. Later writes to either
    /// store do not reach the other. Bindings are node-local and are not copied.
    #[must_use]
    pub fn backup(&self) -> Self {
        Self {
            writer: Mutex::new(()),
            state: Mutex::new(self.snapshot()),
            bindings: MemoryBindings::default(),
        }
    }

    /// The last committed state. A panic elsewhere never leaves a half-applied state behind (a
    /// write swaps in a whole new state), so a poisoned lock is safe to take over.
    fn snapshot(&self) -> Arc<State> {
        Arc::clone(&self.state.lock().unwrap_or_else(PoisonError::into_inner))
    }
}

impl Store for MemoryStore {
    fn read<T, E: From<StoreError>>(
        &self,
        f: impl FnOnce(&dyn Txn) -> Result<T, E>,
    ) -> Result<T, E> {
        let snapshot = self.snapshot();
        let txn = MemoryTxn {
            state: Cow::Borrowed(&*snapshot),
        };
        f(&txn)
    }

    fn write<T, E: From<StoreError>>(
        &self,
        f: impl FnOnce(&mut dyn Txn) -> Result<T, E>,
    ) -> Result<T, E> {
        let _writer = self.writer.lock().unwrap_or_else(PoisonError::into_inner);
        let snapshot = self.snapshot();
        let mut txn = MemoryTxn {
            state: Cow::Borrowed(&*snapshot),
        };
        let value = f(&mut txn)?;
        if let Cow::Owned(next) = txn.state {
            *self.state.lock().unwrap_or_else(PoisonError::into_inner) = Arc::new(next);
        }
        Ok(value)
    }

    fn bindings(&self) -> &dyn BindingsStore {
        &self.bindings
    }

    fn schema_version(&self) -> u32 {
        SCHEMA_VERSION
    }
}

/// One transaction over a [`MemoryStore`].
struct MemoryTxn<'a> {
    state: Cow<'a, State>,
}

impl MemoryTxn<'_> {
    fn state_mut(&mut self) -> &mut State {
        self.state.to_mut()
    }
}

fn fits_i64(value: u64, what: &str) -> Result<(), StoreError> {
    i64::try_from(value)
        .map(|_| ())
        .map_err(|_| StoreError::Invalid(format!("{what} exceeds i64::MAX")))
}

impl BlobStore for MemoryTxn<'_> {
    fn blob(&self, hash: &BlobHash) -> Result<Option<Vec<u8>>, StoreError> {
        Ok(self.state.blobs.get(hash).map(|bytes| bytes.to_vec()))
    }

    fn blob_len(&self, hash: &BlobHash) -> Result<Option<u64>, StoreError> {
        Ok(self.state.blobs.get(hash).map(|bytes| bytes.len() as u64))
    }

    fn put_blob(&mut self, hash: &BlobHash, bytes: &[u8]) -> Result<bool, StoreError> {
        if let Some(existing) = self.state.blobs.get(hash) {
            return if existing.len() == bytes.len() {
                Ok(false)
            } else {
                Err(StoreError::Conflict(format!(
                    "blob {hash:?} is stored with another length"
                )))
            };
        }
        self.state_mut().blobs.insert(*hash, Arc::from(bytes));
        Ok(true)
    }

    fn delete_blob(&mut self, hash: &BlobHash) -> Result<bool, StoreError> {
        if !self.state.blobs.contains_key(hash) {
            return Ok(false);
        }
        Ok(self.state_mut().blobs.remove(hash).is_some())
    }
}

impl LogStore for MemoryTxn<'_> {
    fn entry(&self, hash: &EntryHash) -> Result<Option<StoredEntry>, StoreError> {
        Ok(self.state.entries.get(hash).cloned())
    }

    fn entries_at(&self, log_id: &LogId, seq: u64) -> Result<Vec<StoredEntry>, StoreError> {
        let low = (*log_id, seq, EntryHash::from_bytes([0; 32]));
        let high = (*log_id, seq, EntryHash::from_bytes([0xff; 32]));
        Ok(self
            .state
            .by_seq
            .range(low..=high)
            .filter_map(|(_, _, hash)| self.state.entries.get(hash).cloned())
            .collect())
    }

    fn segment_entries(
        &self,
        log_id: &LogId,
        epoch: &Epoch,
        from_seq: u64,
        limit: usize,
    ) -> Result<Vec<StoredEntry>, StoreError> {
        let low = (*log_id, *epoch, from_seq, EntryHash::from_bytes([0; 32]));
        let high = (*log_id, *epoch, u64::MAX, EntryHash::from_bytes([0xff; 32]));
        Ok(self
            .state
            .by_segment
            .range(low..=high)
            .take(limit)
            .filter_map(|(_, _, _, hash)| self.state.entries.get(hash).cloned())
            .collect())
    }

    fn payload_refs(&self, hash: &BlobHash) -> Result<u64, StoreError> {
        Ok(self
            .state
            .entries
            .values()
            .filter(|entry| {
                entry.payload_hash == *hash && entry.payload_state == PayloadState::Stored
            })
            .count() as u64)
    }

    fn max_n_seen(&self, log_id: &LogId) -> Result<Option<u64>, StoreError> {
        let low = (*log_id, MIN_EPOCH, 0, EntryHash::from_bytes([0; 32]));
        let high = (
            *log_id,
            MAX_EPOCH,
            u64::MAX,
            EntryHash::from_bytes([0xff; 32]),
        );
        Ok(self
            .state
            .by_segment
            .range(low..=high)
            .next_back()
            .map(|(_, epoch, _, _)| epoch.n))
    }

    fn logs(&self) -> Result<Vec<LogId>, StoreError> {
        let mut logs: Vec<LogId> = self.state.by_seq.iter().map(|(log, _, _)| *log).collect();
        logs.dedup();
        Ok(logs)
    }

    fn segment(&self, log_id: &LogId, epoch: &Epoch) -> Result<Option<Segment>, StoreError> {
        Ok(self.state.segments.get(&(*log_id, *epoch)).cloned())
    }

    fn segments(&self, log_id: &LogId) -> Result<Vec<Segment>, StoreError> {
        Ok(self
            .state
            .segments
            .range((*log_id, MIN_EPOCH)..=(*log_id, MAX_EPOCH))
            .map(|(_, segment)| segment.clone())
            .collect())
    }

    fn placement(&self, log_id: &LogId) -> Result<Option<Placement>, StoreError> {
        Ok(self.state.placements.get(log_id).cloned())
    }

    fn placements(&self) -> Result<Vec<Placement>, StoreError> {
        Ok(self.state.placements.values().cloned().collect())
    }

    fn divergence(&self, id: &DivergenceId) -> Result<Option<DivergenceRecord>, StoreError> {
        Ok(self.state.divergences.get(id).cloned())
    }

    fn divergences(&self, log_id: &LogId) -> Result<Vec<DivergenceRecord>, StoreError> {
        Ok(self
            .state
            .divergences
            .values()
            .filter(|record| record.log_id == *log_id)
            .cloned()
            .collect())
    }

    fn vote_promise(&self, log_id: &LogId) -> Result<Option<VotePromise>, StoreError> {
        Ok(self.state.promises.get(log_id).cloned())
    }

    fn put_entry(&mut self, entry: &StoredEntry) -> Result<bool, StoreError> {
        entry.check_shape()?;
        if let Some(existing) = self.state.entries.get(&entry.entry_hash) {
            return if existing.header == entry.header && existing.signature == entry.signature {
                Ok(false)
            } else {
                Err(StoreError::Conflict(format!(
                    "entry {:?} is stored with another header or signature",
                    entry.entry_hash
                )))
            };
        }
        let state = self.state_mut();
        state.entries.insert(entry.entry_hash, entry.clone());
        state
            .by_seq
            .insert((entry.log_id, entry.seq, entry.entry_hash));
        state
            .by_segment
            .insert((entry.log_id, entry.epoch, entry.seq, entry.entry_hash));
        Ok(true)
    }

    fn set_payload_state(
        &mut self,
        hash: &EntryHash,
        payload_state: PayloadState,
    ) -> Result<(), StoreError> {
        let Some(existing) = self.state.entries.get(hash) else {
            return Err(StoreError::NotFound(format!("entry {hash:?}")));
        };
        if payload_state == PayloadState::Pruned && !existing.is_compactable() {
            return Err(StoreError::Invalid(
                "only a compactable payload may be pruned".into(),
            ));
        }
        if let Some(entry) = self.state_mut().entries.get_mut(hash) {
            entry.payload_state = payload_state;
        }
        Ok(())
    }

    fn put_segment(&mut self, segment: &Segment) -> Result<(), StoreError> {
        fits_i64(segment.head_seq, "segment head")?;
        self.state_mut()
            .segments
            .insert((segment.log_id, segment.epoch), segment.clone());
        Ok(())
    }

    fn put_divergence(&mut self, record: &DivergenceRecord) -> Result<(), StoreError> {
        fits_i64(record.tail_head_seq, "tail head")?;
        self.state_mut()
            .divergences
            .insert(record.id, record.clone());
        Ok(())
    }

    fn set_vote_promise(&mut self, promise: &VotePromise) -> Result<(), StoreError> {
        if let Some(existing) = self.state.promises.get(&promise.log_id) {
            check_promise(existing, promise)?;
        }
        self.state_mut()
            .promises
            .insert(promise.log_id, promise.clone());
        Ok(())
    }

    fn write_placement_row(&mut self, placement: &Placement) -> Result<(), StoreError> {
        self.state_mut()
            .placements
            .insert(placement.log_id, placement.clone());
        Ok(())
    }
}

impl KvStore for MemoryTxn<'_> {
    fn kv_get(&self, namespace: Namespace, key: &[u8]) -> Result<Option<Vec<u8>>, StoreError> {
        Ok(self.state.kv.get(&(namespace, key.to_vec())).cloned())
    }

    fn kv_scan_prefix(
        &self,
        namespace: Namespace,
        prefix: &[u8],
    ) -> Result<Vec<KvEntry>, StoreError> {
        let start = (namespace, prefix.to_vec());
        Ok(self
            .state
            .kv
            .range((Bound::Included(start), Bound::Unbounded))
            .take_while(|((space, key), _)| *space == namespace && key.starts_with(prefix))
            .map(|((_, key), value)| (key.clone(), value.clone()))
            .collect())
    }

    fn kv_put(&mut self, namespace: Namespace, key: &[u8], value: &[u8]) -> Result<(), StoreError> {
        self.state_mut()
            .kv
            .insert((namespace, key.to_vec()), value.to_vec());
        Ok(())
    }

    fn kv_delete(&mut self, namespace: Namespace, key: &[u8]) -> Result<bool, StoreError> {
        let key = (namespace, key.to_vec());
        if !self.state.kv.contains_key(&key) {
            return Ok(false);
        }
        Ok(self.state_mut().kv.remove(&key).is_some())
    }
}

impl MembershipStore for MemoryTxn<'_> {
    fn op(&self, op_id: &OpId) -> Result<Option<StoredOp>, StoreError> {
        Ok(self.state.ops.get(op_id).cloned())
    }

    fn ops(&self) -> Result<Vec<StoredOp>, StoreError> {
        Ok(self.state.ops.values().cloned().collect())
    }

    fn op_heads(&self) -> Result<Vec<OpId>, StoreError> {
        let parents: BTreeSet<OpId> = self
            .state
            .ops
            .values()
            .flat_map(|op| op.parents.iter().copied())
            .collect();
        Ok(self
            .state
            .ops
            .keys()
            .filter(|id| !parents.contains(id))
            .copied()
            .collect())
    }

    fn put_op(&mut self, op: &StoredOp) -> Result<bool, StoreError> {
        check_parents(op)?;
        fits_i64(op.received_at_ms, "received_at_ms")?;
        if let Some(existing) = self.state.ops.get(&op.op_id) {
            return if existing.envelope == op.envelope {
                Ok(false)
            } else {
                Err(StoreError::Conflict(format!(
                    "op {:?} is stored with another envelope",
                    op.op_id
                )))
            };
        }
        self.state_mut().ops.insert(op.op_id, op.clone());
        Ok(true)
    }
}

impl DocRegistry for MemoryTxn<'_> {
    fn catalog_doc(&self, doc_id: &DocId) -> Result<Option<CatalogDoc>, StoreError> {
        Ok(self.state.docs.get(doc_id).cloned())
    }

    fn catalog_docs(&self) -> Result<Vec<CatalogDoc>, StoreError> {
        Ok(self.state.docs.values().cloned().collect())
    }

    fn register_doc(&mut self, doc: &CatalogDoc) -> Result<(), StoreError> {
        fits_i64(doc.registered_at_ms, "registered_at_ms")?;
        self.state_mut().docs.insert(doc.doc_id, doc.clone());
        Ok(())
    }

    fn set_doc_state(&mut self, doc_id: &DocId, doc_state: DocState) -> Result<(), StoreError> {
        if !self.state.docs.contains_key(doc_id) {
            return Err(StoreError::NotFound(format!("catalog doc {doc_id:?}")));
        }
        if let Some(doc) = self.state_mut().docs.get_mut(doc_id) {
            doc.state = doc_state;
        }
        Ok(())
    }

    fn remove_doc(&mut self, doc_id: &DocId) -> Result<bool, StoreError> {
        if !self.state.docs.contains_key(doc_id) {
            return Ok(false);
        }
        Ok(self.state_mut().docs.remove(doc_id).is_some())
    }
}

impl FeedJournal for MemoryTxn<'_> {
    fn feed_rows(
        &self,
        conversation_id: &ConversationId,
        after: Option<u64>,
        limit: usize,
    ) -> Result<Vec<FeedRow>, StoreError> {
        let start = match after {
            Some(seq) => Bound::Excluded((conversation_id.clone(), seq)),
            None => Bound::Included((conversation_id.clone(), 0)),
        };
        let end = Bound::Included((conversation_id.clone(), u64::MAX));
        Ok(self
            .state
            .feed
            .range((start, end))
            .take(limit)
            .map(|((conversation, seq), hash)| FeedRow {
                conversation_id: conversation.clone(),
                feed_seq: *seq,
                entry_hash: *hash,
            })
            .collect())
    }

    fn feed_head(&self, conversation_id: &ConversationId) -> Result<Option<u64>, StoreError> {
        Ok(self
            .state
            .feed
            .range((conversation_id.clone(), 0)..=(conversation_id.clone(), u64::MAX))
            .next_back()
            .map(|((_, seq), _)| *seq))
    }

    fn put_feed_row(&mut self, row: &FeedRow) -> Result<(), StoreError> {
        fits_i64(row.feed_seq, "feed seq")?;
        self.state_mut()
            .feed
            .insert((row.conversation_id.clone(), row.feed_seq), row.entry_hash);
        Ok(())
    }

    fn delete_feed_row(
        &mut self,
        conversation_id: &ConversationId,
        feed_seq: u64,
    ) -> Result<bool, StoreError> {
        let key = (conversation_id.clone(), feed_seq);
        if !self.state.feed.contains_key(&key) {
            return Ok(false);
        }
        Ok(self.state_mut().feed.remove(&key).is_some())
    }

    fn truncate_feed_after(
        &mut self,
        conversation_id: &ConversationId,
        feed_seq: u64,
    ) -> Result<u64, StoreError> {
        let doomed: Vec<(ConversationId, u64)> = self
            .state
            .feed
            .range((
                Bound::Excluded((conversation_id.clone(), feed_seq)),
                Bound::Included((conversation_id.clone(), u64::MAX)),
            ))
            .map(|(key, _)| key.clone())
            .collect();
        if doomed.is_empty() {
            return Ok(0);
        }
        let state = self.state_mut();
        for key in &doomed {
            state.feed.remove(key);
        }
        Ok(doomed.len() as u64)
    }
}

/// In-memory node-local bindings.
#[derive(Default)]
struct MemoryBindings {
    values: Mutex<BTreeMap<String, String>>,
}

impl MemoryBindings {
    fn lock(&self) -> MutexGuard<'_, BTreeMap<String, String>> {
        self.values.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

impl BindingsStore for MemoryBindings {
    fn binding(&self, key: &BindingKey) -> Result<Option<String>, StoreError> {
        Ok(self.lock().get(&key.encode()?).cloned())
    }

    fn set_binding(&self, key: &BindingKey, value: &str) -> Result<(), StoreError> {
        self.lock().insert(key.encode()?, value.to_string());
        Ok(())
    }

    fn remove_binding(&self, key: &BindingKey) -> Result<bool, StoreError> {
        Ok(self.lock().remove(&key.encode()?).is_some())
    }

    fn bindings(&self) -> Result<Vec<(BindingKey, String)>, StoreError> {
        self.lock()
            .iter()
            .map(|(key, value)| Ok((BindingKey::decode(key)?, value.clone())))
            .collect()
    }
}
