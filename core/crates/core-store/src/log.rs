//! The agent log area: entries, segments, the placement row (the fencing mark), recorded
//! divergences and vote promises.
//!
//! The store does not hash, verify signatures or decide which segment is canonical. `core-log` and
//! `core-placement` do, and hand the store records whose indexed fields they took from verified
//! headers. The store enforces what must hold whatever the caller does: entries are immutable once
//! stored, a placement only moves to a higher epoch together with that epoch's first entry, and an
//! append only extends the current placement's open segment.

use core_proto::{AgentId, Epoch, NodeId};

use crate::blob::BlobStore;
use crate::error::StoreError;
use crate::ids::{BlobHash, DivergenceId, EntryHash, LogId};

/// Length of a log entry header.
pub const HEADER_LEN: usize = 168;
/// Length of an entry signature.
pub const SIGNATURE_LEN: usize = 64;
/// Kind of the first entry of every segment.
pub const KIND_EPOCH_OPEN: u16 = 0x0001;
/// Header flag: the payload may be pruned once superseded.
pub const FLAG_COMPACTABLE: u16 = 0x0001;

/// Whether an entry's payload is held locally.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum PayloadState {
    /// Not yet fetched.
    Missing,
    /// Held in the [`BlobStore`] under the entry's payload hash.
    Stored,
    /// Dropped by compaction. Only allowed for compactable entries.
    Pruned,
}

/// One signed log entry as stored: the header and signature verbatim, plus the header fields the
/// store indexes by.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StoredEntry {
    /// `derive_key("log entry", header)`.
    pub entry_hash: EntryHash,
    /// The agent log.
    pub log_id: LogId,
    /// The segment's epoch; its claimant signed the entry.
    pub epoch: Epoch,
    /// Agent-global height.
    pub seq: u64,
    /// Hash of the entry at `seq - 1` on this line; [`EntryHash::ZERO`] at seq 0.
    pub prev: EntryHash,
    /// Payload kind.
    pub kind: u16,
    /// Header flags.
    pub flags: u16,
    /// The 168-byte header, verbatim.
    pub header: Vec<u8>,
    /// The Ed25519 signature over `entry_hash`.
    pub signature: [u8; SIGNATURE_LEN],
    /// `blake3(payload)`.
    pub payload_hash: BlobHash,
    /// Payload length in bytes.
    pub payload_len: u64,
    /// Whether the payload is held.
    pub payload_state: PayloadState,
}

impl StoredEntry {
    /// Whether the header marks the payload compactable.
    #[must_use]
    pub const fn is_compactable(&self) -> bool {
        self.flags & FLAG_COMPACTABLE != 0
    }

    /// Whether this entry's payload counts as complete: held, or legitimately pruned.
    #[must_use]
    pub const fn payload_complete(&self) -> bool {
        matches!(self.payload_state, PayloadState::Stored)
            || (matches!(self.payload_state, PayloadState::Pruned) && self.is_compactable())
    }

    /// Checks the structural rules the store relies on.
    ///
    /// # Errors
    ///
    /// [`StoreError::Invalid`] naming the broken rule.
    pub fn check_shape(&self) -> Result<(), StoreError> {
        if self.header.len() != HEADER_LEN {
            return Err(StoreError::Invalid(format!(
                "header is {} bytes, expected {HEADER_LEN}",
                self.header.len()
            )));
        }
        if (self.seq == 0) != (self.prev == EntryHash::ZERO) {
            return Err(StoreError::Invalid(
                "prev must be zero exactly when seq is 0".into(),
            ));
        }
        if self.payload_state == PayloadState::Pruned && !self.is_compactable() {
            return Err(StoreError::Invalid(
                "only a compactable payload may be pruned".into(),
            ));
        }
        i64::try_from(self.seq).map_err(|_| StoreError::Invalid("seq exceeds i64::MAX".into()))?;
        i64::try_from(self.epoch.n)
            .map_err(|_| StoreError::Invalid("epoch n exceeds i64::MAX".into()))?;
        Ok(())
    }
}

/// A segment's classification on this replica.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum SegmentState {
    /// The current holder may still append.
    Open,
    /// Ended by an `epoch_seal`.
    Sealed,
    /// A closed segment on the canonical line.
    CanonicalAncestor,
    /// Not on the canonical line; kept and relayed for ever.
    Divergent,
    /// Failed validation; kept as evidence.
    Invalid,
    /// Held back pending more information.
    Quarantined,
}

/// Per-replica bookkeeping for one segment (one agent, one epoch).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Segment {
    /// The agent log.
    pub log_id: LogId,
    /// The segment's epoch.
    pub epoch: Epoch,
    /// Seq of its `epoch_open`.
    pub start_seq: u64,
    /// The fork point its `epoch_open` names; `None` for the genesis segment.
    pub parent: Option<EntryHash>,
    /// Highest seq whose chain back to `start_seq` is unbroken.
    pub head_seq: u64,
    /// Hash of the entry at `head_seq`.
    pub head_hash: EntryHash,
    /// Highest seq at or below `head_seq` with every payload complete; `None` when not even the
    /// `epoch_open` is.
    pub complete_seq: Option<u64>,
    /// Classification.
    pub state: SegmentState,
}

/// What the placement record says about the holder.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum PlacementState {
    /// The canonical segment is open: its claimant holds the agent.
    Active,
    /// The canonical segment ended in a seal naming this next epoch.
    SealedTo(Epoch),
    /// Released, or sealed to a target that never opened.
    Placeless,
    /// A higher epoch number exists but is not valid; nobody holds the agent.
    SupersededUnknown,
}

/// The placement record of one agent log, which is also its fencing high-water mark.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Placement {
    /// The agent log.
    pub log_id: LogId,
    /// The (tree) agent the log belongs to.
    pub agent_id: AgentId,
    /// The canonical segment's epoch.
    pub epoch: Epoch,
    /// Holder state.
    pub state: PlacementState,
    /// Hash of that segment's `epoch_open`.
    pub open_entry_hash: EntryHash,
}

impl Placement {
    /// The holder: the canonical epoch's claimant.
    #[must_use]
    pub const fn holder(&self) -> NodeId {
        self.epoch.claimant
    }
}

/// A divergence this replica has recorded.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DivergenceRecord {
    /// `derive_key("divergence id", ...)`; equal on every node.
    pub id: DivergenceId,
    /// The agent log.
    pub log_id: LogId,
    /// The stranded epoch.
    pub losing_epoch: Epoch,
    /// Seq of the last common entry.
    pub fork_seq: u64,
    /// Newest known seq of the losing tail.
    pub tail_head_seq: u64,
    /// The holder's `divergence` log entry, once written.
    pub recorded_entry: Option<EntryHash>,
}

/// A voter's durable promise for one log: the claim it last voted for.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct VotePromise {
    /// The agent log.
    pub log_id: LogId,
    /// The epoch voted for.
    pub epoch: Epoch,
    /// The fork point's seq.
    pub parent_seq: u64,
    /// The fork point's hash.
    pub parent_hash: EntryHash,
}

/// An epoch claim ready to commit: the `epoch_open` entry and its payload, from which the segment
/// row and the placement row are derived. It cannot be built without the entry, so the three
/// cannot be committed separately through it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EpochClaim {
    agent_id: AgentId,
    entry: StoredEntry,
    payload: Vec<u8>,
}

impl EpochClaim {
    /// Builds a claim for `agent_id` from its `epoch_open` entry and payload.
    ///
    /// # Errors
    ///
    /// [`StoreError::Invalid`] when the entry is not an `epoch_open`, is malformed, or the payload
    /// length does not match the header.
    pub fn new(
        agent_id: AgentId,
        mut entry: StoredEntry,
        payload: Vec<u8>,
    ) -> Result<Self, StoreError> {
        if entry.kind != KIND_EPOCH_OPEN {
            return Err(StoreError::Invalid(format!(
                "an epoch claim starts with an epoch_open, not kind {:#06x}",
                entry.kind
            )));
        }
        if entry.is_compactable() {
            return Err(StoreError::Invalid(
                "an epoch_open is never compactable".into(),
            ));
        }
        check_payload_len(&entry, &payload)?;
        entry.payload_state = PayloadState::Stored;
        entry.check_shape()?;
        Ok(Self {
            agent_id,
            entry,
            payload,
        })
    }

    /// The `epoch_open` entry.
    #[must_use]
    pub const fn entry(&self) -> &StoredEntry {
        &self.entry
    }

    /// The segment row this claim opens.
    #[must_use]
    pub fn segment(&self) -> Segment {
        Segment {
            log_id: self.entry.log_id,
            epoch: self.entry.epoch,
            start_seq: self.entry.seq,
            parent: (self.entry.seq > 0).then_some(self.entry.prev),
            head_seq: self.entry.seq,
            head_hash: self.entry.entry_hash,
            complete_seq: Some(self.entry.seq),
            state: SegmentState::Open,
        }
    }

    /// The placement row (fencing mark) this claim sets.
    #[must_use]
    pub fn placement(&self) -> Placement {
        Placement {
            log_id: self.entry.log_id,
            agent_id: self.agent_id.clone(),
            epoch: self.entry.epoch,
            state: PlacementState::Active,
            open_entry_hash: self.entry.entry_hash,
        }
    }
}

/// The vote promise rule shared by every backend: never lower, and one fork point per epoch.
///
/// # Errors
///
/// [`StoreError::Fenced`] or [`StoreError::Conflict`].
pub fn check_promise(existing: &VotePromise, promise: &VotePromise) -> Result<(), StoreError> {
    if promise.epoch < existing.epoch {
        return Err(StoreError::Fenced {
            log_id: promise.log_id,
            canonical: Some(existing.epoch),
            max_n_seen: None,
        });
    }
    if promise.epoch == existing.epoch
        && (promise.parent_seq, promise.parent_hash) != (existing.parent_seq, existing.parent_hash)
    {
        return Err(StoreError::Conflict(
            "a second promise at one epoch names another fork point".into(),
        ));
    }
    Ok(())
}

fn check_payload_len(entry: &StoredEntry, payload: &[u8]) -> Result<(), StoreError> {
    if u64::try_from(payload.len()).ok() == Some(entry.payload_len) {
        Ok(())
    } else {
        Err(StoreError::Invalid(format!(
            "payload is {} bytes, header says {}",
            payload.len(),
            entry.payload_len
        )))
    }
}

/// The log area of a transaction.
///
/// Required methods are the backend's primitives. Provided methods are the operations domain code
/// should use; they carry the fencing rules, so every backend enforces them identically.
pub trait LogStore: BlobStore {
    /// The entry with this hash.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn entry(&self, hash: &EntryHash) -> Result<Option<StoredEntry>, StoreError>;

    /// Every stored entry of `log_id` at `seq`. More than one means divergence evidence. Ordered
    /// by entry hash.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn entries_at(&self, log_id: &LogId, seq: u64) -> Result<Vec<StoredEntry>, StoreError>;

    /// Up to `limit` entries of the segment `(log_id, epoch)` with seq at or above `from_seq`,
    /// ordered by seq, then entry hash.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn segment_entries(
        &self,
        log_id: &LogId,
        epoch: &Epoch,
        from_seq: u64,
        limit: usize,
    ) -> Result<Vec<StoredEntry>, StoreError>;

    /// How many stored entries hold `hash` as a payload in state [`PayloadState::Stored`].
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn payload_refs(&self, hash: &BlobHash) -> Result<u64, StoreError>;

    /// The highest epoch number among every stored entry of the log (canonical, divergent,
    /// invalid or quarantined). Derived, so it never decreases.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn max_n_seen(&self, log_id: &LogId) -> Result<Option<u64>, StoreError>;

    /// Every log with at least one stored entry, ordered.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn logs(&self) -> Result<Vec<LogId>, StoreError>;

    /// The segment row for `(log_id, epoch)`.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn segment(&self, log_id: &LogId, epoch: &Epoch) -> Result<Option<Segment>, StoreError>;

    /// Every segment row of the log, ordered by epoch.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn segments(&self, log_id: &LogId) -> Result<Vec<Segment>, StoreError>;

    /// The log's placement row.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn placement(&self, log_id: &LogId) -> Result<Option<Placement>, StoreError>;

    /// Every placement row, ordered by log id.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn placements(&self) -> Result<Vec<Placement>, StoreError>;

    /// A recorded divergence.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn divergence(&self, id: &DivergenceId) -> Result<Option<DivergenceRecord>, StoreError>;

    /// Every divergence recorded for the log, ordered by id.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn divergences(&self, log_id: &LogId) -> Result<Vec<DivergenceRecord>, StoreError>;

    /// This node's vote promise for the log.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn vote_promise(&self, log_id: &LogId) -> Result<Option<VotePromise>, StoreError>;

    /// Stores a received entry (any segment, any classification). Returns `true` when it was new.
    /// Re-storing the same entry is a no-op that keeps the stored payload state.
    ///
    /// # Errors
    ///
    /// [`StoreError::Invalid`] for a malformed entry; [`StoreError::Conflict`] when a different
    /// header or signature is stored under the same hash.
    fn put_entry(&mut self, entry: &StoredEntry) -> Result<bool, StoreError>;

    /// Sets an entry's payload state without touching the blob.
    ///
    /// # Errors
    ///
    /// [`StoreError::NotFound`] for an unknown entry; [`StoreError::Invalid`] when pruning a
    /// payload that is not compactable.
    fn set_payload_state(
        &mut self,
        hash: &EntryHash,
        state: PayloadState,
    ) -> Result<(), StoreError>;

    /// Inserts or replaces a segment row.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn put_segment(&mut self, segment: &Segment) -> Result<(), StoreError>;

    /// Inserts or replaces a divergence record (a later record with the same id supersedes).
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn put_divergence(&mut self, record: &DivergenceRecord) -> Result<(), StoreError>;

    /// Records a vote promise. A promise never moves to a lower epoch, and a second promise at the
    /// same epoch must name the same fork point.
    ///
    /// # Errors
    ///
    /// [`StoreError::Fenced`] for a lower epoch; [`StoreError::Conflict`] for a different fork point
    /// at the same epoch.
    fn set_vote_promise(&mut self, promise: &VotePromise) -> Result<(), StoreError>;

    /// Backend primitive: writes the placement row with no checks. Domain code uses
    /// [`LogStore::adopt_epoch`], [`LogStore::set_placement_state`] and
    /// [`LogStore::fall_back_placement`] instead, which carry the fencing rules.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    #[doc(hidden)]
    fn write_placement_row(&mut self, placement: &Placement) -> Result<(), StoreError>;

    /// Adopts an epoch claim: stores its `epoch_open` and payload, opens its segment and moves the
    /// placement to it. Used by the claimant for its own claim and by replicas for a claim-valid
    /// `epoch_open` above their canonical epoch (fencing rule F2).
    ///
    /// # Errors
    ///
    /// [`StoreError::Fenced`] when the placement is already at this epoch or above;
    /// [`StoreError::Conflict`] when the placement belongs to another agent or the entry conflicts.
    fn adopt_epoch(&mut self, claim: EpochClaim) -> Result<(), StoreError> {
        let log_id = claim.entry.log_id;
        if let Some(current) = self.placement(&log_id)? {
            if claim.entry.epoch <= current.epoch {
                return Err(StoreError::Fenced {
                    log_id,
                    canonical: Some(current.epoch),
                    max_n_seen: self.max_n_seen(&log_id)?,
                });
            }
            if current.agent_id != claim.agent_id {
                return Err(StoreError::Conflict(format!(
                    "log belongs to agent {}, claim names {}",
                    current.agent_id.as_str(),
                    claim.agent_id.as_str()
                )));
            }
        }
        self.put_entry(&claim.entry)?;
        self.set_payload_state(&claim.entry.entry_hash, PayloadState::Stored)?;
        self.put_blob(&claim.entry.payload_hash, &claim.payload)?;
        self.put_segment(&claim.segment())?;
        self.write_placement_row(&claim.placement())
    }

    /// Extends the current placement's open segment by one entry: stores it (and its payload when
    /// given) and advances the segment head, and `complete_seq` when the entry is complete. Used by
    /// the holder (append path) and by replicas (fencing rule F1).
    ///
    /// # Errors
    ///
    /// [`StoreError::Fenced`] when the entry's epoch is not the placement's, the placement is not
    /// active, or a higher epoch number has been seen; [`StoreError::Invalid`] when the entry is an
    /// `epoch_open`, does not follow the segment head, or its payload does not match.
    fn append_canonical(
        &mut self,
        entry: &StoredEntry,
        payload: Option<&[u8]>,
    ) -> Result<(), StoreError> {
        let log_id = entry.log_id;
        let placement = self.placement(&log_id)?;
        let max_n_seen = self.max_n_seen(&log_id)?;
        let fenced = || StoreError::Fenced {
            log_id,
            canonical: placement.as_ref().map(|current| current.epoch),
            max_n_seen,
        };
        let Some(current) = placement.as_ref() else {
            return Err(fenced());
        };
        if current.epoch != entry.epoch
            || current.state != PlacementState::Active
            || max_n_seen.is_some_and(|n| n > entry.epoch.n)
        {
            return Err(fenced());
        }
        if entry.kind == KIND_EPOCH_OPEN {
            return Err(StoreError::Invalid(
                "an epoch_open starts a segment; use adopt_epoch".into(),
            ));
        }
        let mut segment = self.segment(&log_id, &entry.epoch)?.ok_or_else(|| {
            StoreError::Corrupt("placement names a segment that is missing".into())
        })?;
        if entry.seq != segment.head_seq + 1 || entry.prev != segment.head_hash {
            return Err(StoreError::Invalid(format!(
                "entry at seq {} does not follow the segment head at seq {}",
                entry.seq, segment.head_seq
            )));
        }
        let mut entry = entry.clone();
        if let Some(bytes) = payload {
            check_payload_len(&entry, bytes)?;
            entry.payload_state = PayloadState::Stored;
            self.put_blob(&entry.payload_hash, bytes)?;
        } else if entry.payload_state == PayloadState::Stored {
            return Err(StoreError::Invalid(
                "payload state is stored but no payload was given".into(),
            ));
        }
        self.put_entry(&entry)?;
        if segment.complete_seq == Some(segment.head_seq) && entry.payload_complete() {
            segment.complete_seq = Some(entry.seq);
        }
        segment.head_seq = entry.seq;
        segment.head_hash = entry.entry_hash;
        self.put_segment(&segment)
    }

    /// Stores a payload that arrived after its entry and marks it stored.
    ///
    /// # Errors
    ///
    /// [`StoreError::NotFound`] for an unknown entry; [`StoreError::Invalid`] when the length does
    /// not match the header.
    fn attach_payload(&mut self, hash: &EntryHash, payload: &[u8]) -> Result<(), StoreError> {
        let entry = self
            .entry(hash)?
            .ok_or_else(|| StoreError::NotFound(format!("entry {hash:?}")))?;
        check_payload_len(&entry, payload)?;
        self.put_blob(&entry.payload_hash, payload)?;
        self.set_payload_state(hash, PayloadState::Stored)
    }

    /// Prunes a compactable entry's payload, and deletes the blob once no stored entry references
    /// it. The header is kept for ever.
    ///
    /// # Errors
    ///
    /// [`StoreError::NotFound`] for an unknown entry; [`StoreError::Invalid`] when the entry is not
    /// compactable.
    fn prune_payload(&mut self, hash: &EntryHash) -> Result<(), StoreError> {
        let entry = self
            .entry(hash)?
            .ok_or_else(|| StoreError::NotFound(format!("entry {hash:?}")))?;
        self.set_payload_state(hash, PayloadState::Pruned)?;
        if self.payload_refs(&entry.payload_hash)? == 0 {
            self.delete_blob(&entry.payload_hash)?;
        }
        Ok(())
    }

    /// Changes the placement state for the current epoch (a seal, a release, superseded).
    ///
    /// # Errors
    ///
    /// [`StoreError::Fenced`] when the placement is not at `epoch`.
    fn set_placement_state(
        &mut self,
        log_id: &LogId,
        epoch: &Epoch,
        state: PlacementState,
    ) -> Result<(), StoreError> {
        match self.placement(log_id)? {
            Some(mut current) if current.epoch == *epoch => {
                current.state = state;
                self.write_placement_row(&current)
            }
            other => Err(StoreError::Fenced {
                log_id: *log_id,
                canonical: other.map(|current| current.epoch),
                max_n_seen: self.max_n_seen(log_id)?,
            }),
        }
    }

    /// Moves the placement down to an earlier, stored segment: the canonical segment fell back
    /// because its tip lost validity (a revoked claimant). The segment's `epoch_open` must be
    /// stored.
    ///
    /// # Errors
    ///
    /// [`StoreError::Invalid`] when `epoch` is not below the current placement;
    /// [`StoreError::NotFound`] when the segment or its `epoch_open` is not stored.
    fn fall_back_placement(
        &mut self,
        log_id: &LogId,
        epoch: &Epoch,
        state: PlacementState,
    ) -> Result<(), StoreError> {
        let current = self
            .placement(log_id)?
            .ok_or_else(|| StoreError::NotFound(format!("placement of {log_id:?}")))?;
        if *epoch >= current.epoch {
            return Err(StoreError::Invalid(
                "a fallback moves the placement to a lower epoch".into(),
            ));
        }
        let segment = self
            .segment(log_id, epoch)?
            .ok_or_else(|| StoreError::NotFound(format!("segment {epoch:?}")))?;
        let open = self
            .entries_at(log_id, segment.start_seq)?
            .into_iter()
            .find(|entry| entry.epoch == *epoch && entry.kind == KIND_EPOCH_OPEN)
            .ok_or_else(|| StoreError::NotFound(format!("epoch_open of {epoch:?}")))?;
        self.write_placement_row(&Placement {
            log_id: *log_id,
            agent_id: current.agent_id,
            epoch: *epoch,
            state,
            open_entry_hash: open.entry_hash,
        })
    }
}
