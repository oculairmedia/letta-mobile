//! The agent log state machine (C1 §3, fencing F1-F5 of §5.4): append as holder, verify and accept
//! as replica, segments per epoch, claims with their fork point, the canonical line, divergence
//! detection.
//!
//! [`AgentLog`] holds no state of its own: everything lives in the [`LogStore`] it is handed, so
//! the driver decides the transaction boundaries (one transaction per received frame, or per
//! holder batch). It reads no clock: timestamps are passed in.

use std::collections::{BTreeMap, BTreeSet};

use core_proto::crypto::NodeKey;
use core_proto::{AgentId, Epoch};
use core_store::{
    DivergenceRecord, EntryHash, EpochClaim, LogId, LogStore, PayloadState, Placement,
    PlacementState, Segment, SegmentState, StoreError, StoredEntry,
};
use thiserror::Error;

use crate::EPOCH_N_MAX_STEP;
use crate::divergence::{Divergence, DivergenceInputs, DivergenceProjection};
use crate::hashes::{log_id, payload_hash};
use crate::header::{EntryHeader, HeaderError, SignedEntry};
use crate::judge::{ClaimContext, ClaimJudge};
use crate::payload::{EpochOpen, EpochSeal, Payload, Reason, kind};
use crate::value::{Pos, ShapeError};

/// How a received entry's payload arrived.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Incoming {
    /// The bytes, inline.
    Inline(Vec<u8>),
    /// To be fetched by hash.
    ByHash,
    /// Pruned at the sender. Accepted only for compactable entries.
    Pruned,
}

/// What [`AgentLog::receive`] did.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Received {
    /// Stored as new. `simple` means it only extended the active canonical segment, so no
    /// re-evaluation is needed.
    Stored {
        /// Extends the active canonical segment's head with nothing else at its seq.
        simple: bool,
    },
    /// Already stored; nothing changed.
    Duplicate,
    /// Already stored; its payload arrived now.
    PayloadAttached,
    /// Its parent is not stored yet; buffer it keyed by `missing`.
    Pending {
        /// The parent hash it waits for.
        missing: EntryHash,
    },
}

/// Why an operation on the log failed.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
pub enum LogError {
    /// The header or signature is invalid.
    #[error("header: {0}")]
    Header(#[from] HeaderError),
    /// A payload is not a valid encoding of its kind.
    #[error("payload: {0}")]
    Payload(#[from] ShapeError),
    /// The payload does not match the header's hash or length.
    #[error("payload does not match its header")]
    PayloadMismatch,
    /// The entry belongs to another log.
    #[error("entry belongs to another log")]
    WrongLog,
    /// The entry does not follow its parent (C1 §3.2 rules 3 and 4).
    #[error("chain mismatch: {0}")]
    ChainMismatch(String),
    /// This node may not write (F4): not the holder, not active, or a higher epoch is known.
    #[error("not the holder: {0}")]
    NotHolder(String),
    /// A claim this node made is not valid.
    #[error("claim rejected: {0}")]
    ClaimRejected(String),
    /// The store refused.
    #[error("store: {0}")]
    Store(#[from] StoreError),
}

impl LogError {
    /// The `baken/log/1` error code for this failure.
    #[must_use]
    pub const fn wire_code(&self) -> &'static str {
        match self {
            Self::Header(HeaderError::BadSignature | HeaderError::WrongSigner) => "bad_signature",
            Self::Header(_) | Self::Payload(_) | Self::PayloadMismatch => "bad_encoding",
            Self::WrongLog | Self::ChainMismatch(_) => "chain_mismatch",
            Self::NotHolder(_) | Self::ClaimRejected(_) => "forbidden",
            Self::Store(_) => "store",
        }
    }
}

/// The result of evaluating a log: its canonical segment, placement and divergences.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Evaluation {
    /// The canonical epoch, if any segment is valid.
    pub canonical: Option<Epoch>,
    /// The canonical tip.
    pub tip: Option<Pos>,
    /// The placement row after evaluation.
    pub placement: Option<Placement>,
    /// Whether the placement row changed.
    pub placement_changed: bool,
    /// Every divergence relative to the canonical line.
    pub divergences: Vec<Divergence>,
    /// Divergences recorded for the first time, or whose tail grew.
    pub changed_divergences: Vec<DivergenceInputs>,
}

/// A read-only analysis of one log (see [`AgentLog::analyze`]).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Analysis {
    /// The agent, from the genesis payload.
    pub agent_id: Option<AgentId>,
    /// The canonical epoch.
    pub canonical: Option<Epoch>,
    /// The canonical line, indexed by seq.
    pub line: Vec<StoredEntry>,
    /// The derived placement state.
    pub placement_state: Option<PlacementState>,
    /// Every segment row with its derived state.
    pub segments: Vec<Segment>,
    /// Every divergence relative to the canonical line.
    pub divergences: Vec<Divergence>,
}

/// One agent's log, over a store.
pub struct AgentLog<'j, J: ClaimJudge + ?Sized> {
    log_id: LogId,
    judge: &'j J,
}

impl<'j, J: ClaimJudge + ?Sized> AgentLog<'j, J> {
    /// The log with this id.
    pub const fn new(log_id: LogId, judge: &'j J) -> Self {
        Self { log_id, judge }
    }

    /// The log of `agent`.
    pub fn for_agent(agent: &AgentId, judge: &'j J) -> Self {
        Self::new(log_id(agent), judge)
    }

    /// The log id.
    #[must_use]
    pub const fn log_id(&self) -> LogId {
        self.log_id
    }

    // ----- holder side -----

    /// Creates the agent's log: the genesis `epoch_open` at seq 0 in epoch `(1, key)`, committed
    /// with its segment and placement.
    ///
    /// # Errors
    ///
    /// [`LogError::NotHolder`] when the log already exists; [`LogError::ClaimRejected`] when the
    /// payload is not a genesis for this log's agent.
    pub fn genesis<S: LogStore + ?Sized>(
        &self,
        store: &mut S,
        key: &NodeKey,
        mut open: EpochOpen,
        ts_ms: u64,
    ) -> Result<SignedEntry, LogError> {
        let agent = open
            .agent_id
            .clone()
            .ok_or_else(|| LogError::ClaimRejected("genesis needs agent_id".into()))?;
        if open.reason != Reason::Genesis || log_id(&agent) != self.log_id {
            return Err(LogError::ClaimRejected("not a genesis for this log".into()));
        }
        if store.placement(&self.log_id)?.is_some()
            || !store.entries_at(&self.log_id, 0)?.is_empty()
        {
            return Err(LogError::NotHolder("the log already exists".into()));
        }
        open.agent_id = Some(agent.clone());
        let payload = Payload::EpochOpen(open).encode();
        let entry = self.sign(
            key,
            Epoch {
                n: 1,
                claimant: key.node_id(),
            },
            Pos {
                seq: 0,
                hash: EntryHash::ZERO,
            },
            kind::EPOCH_OPEN,
            0,
            &payload,
            ts_ms,
        )?;
        let claim = EpochClaim::new(agent, entry.to_stored(PayloadState::Stored), payload)?;
        store.adopt_epoch(claim)?;
        Ok(entry)
    }

    /// Appends one entry as holder (C1 §3.7), after the F4 gate: this node is the canonical
    /// epoch's claimant, the placement is active, and no higher epoch number is known.
    /// `epoch_open` goes through [`AgentLog::claim`]; an `epoch_seal` also moves the placement to
    /// `sealed_to` or `placeless`.
    ///
    /// # Errors
    ///
    /// [`LogError::NotHolder`] when the gate fails.
    pub fn append<S: LogStore + ?Sized>(
        &self,
        store: &mut S,
        key: &NodeKey,
        payload: &Payload,
        ts_ms: u64,
    ) -> Result<SignedEntry, LogError> {
        if payload.kind() == kind::EPOCH_OPEN {
            return Err(LogError::NotHolder(
                "an epoch_open is written through claim".into(),
            ));
        }
        let placement = self.gate(store, key)?;
        let segment = store
            .segment(&self.log_id, &placement.epoch)?
            .ok_or_else(|| StoreError::Corrupt("placement names a missing segment".into()))?;
        let bytes = payload.encode();
        let entry = self.sign(
            key,
            placement.epoch,
            Pos {
                seq: segment.head_seq,
                hash: segment.head_hash,
            },
            payload.kind(),
            payload.default_flags(),
            &bytes,
            ts_ms,
        )?;
        store.append_canonical(&entry.to_stored(PayloadState::Missing), Some(&bytes))?;
        if let Payload::EpochSeal(seal) = payload {
            store.set_placement_state(
                &self.log_id,
                &placement.epoch,
                seal.next_epoch
                    .map_or(PlacementState::Placeless, PlacementState::SealedTo),
            )?;
        }
        Ok(entry)
    }

    /// Seals the holder's segment: hands the agent to `next`, or releases it when `None`.
    ///
    /// # Errors
    ///
    /// As [`AgentLog::append`].
    pub fn seal<S: LogStore + ?Sized>(
        &self,
        store: &mut S,
        key: &NodeKey,
        next: Option<Epoch>,
        checkpoint: Pos,
        ts_ms: u64,
    ) -> Result<SignedEntry, LogError> {
        self.append(
            store,
            key,
            &Payload::EpochSeal(EpochSeal {
                next_epoch: next,
                checkpoint,
            }),
            ts_ms,
        )
    }

    /// Claims a new epoch `(max_n_seen + 1, key)` whose `epoch_open` names `parent` as its fork
    /// point. The claim must be valid (structure plus the judge); it then becomes canonical in the
    /// same transaction (F2) and the old line past `parent` is reclassified as divergent.
    ///
    /// # Errors
    ///
    /// [`LogError::ClaimRejected`] when the parent is unknown, the step is too large, or the claim
    /// is not valid or does not become canonical.
    pub fn claim<S: LogStore + ?Sized>(
        &self,
        store: &mut S,
        key: &NodeKey,
        open: &EpochOpen,
        parent: Pos,
        ts_ms: u64,
    ) -> Result<(SignedEntry, Evaluation), LogError> {
        let parent_entry = store
            .entry(&parent.hash)?
            .filter(|e| e.seq == parent.seq && e.log_id == self.log_id)
            .ok_or_else(|| LogError::ClaimRejected("unknown parent".into()))?;
        let parent_payload = load_payload(store, &parent_entry)?;
        // A claim over a seal (other than the move the seal names) goes above the sealed-to epoch,
        // so a late open by the seal's target is a divergent tail rather than a tie on `n`.
        let sealed_to = match &parent_payload {
            Some(Payload::EpochSeal(seal)) if open.reason != Reason::Move => {
                seal.next_epoch.map_or(0, |next| next.n)
            }
            _ => 0,
        };
        let max_n = store
            .max_n_seen(&self.log_id)?
            .unwrap_or(0)
            .max(parent_entry.epoch.n)
            .max(sealed_to);
        let epoch = Epoch {
            n: max_n + 1,
            claimant: key.node_id(),
        };
        if epoch.n > parent_entry.epoch.n + EPOCH_N_MAX_STEP {
            return Err(LogError::ClaimRejected("epoch step too large".into()));
        }
        let payload = Payload::EpochOpen(open.clone());
        let bytes = payload.encode();
        let entry = self.sign(key, epoch, parent, kind::EPOCH_OPEN, 0, &bytes, ts_ms)?;
        let context = ClaimContext {
            log_id: self.log_id,
            open: &entry,
            payload: open,
            parent: Some(&parent_entry),
            parent_payload: parent_payload.as_ref(),
        };
        if !self.judge.claim_valid(&context) {
            return Err(LogError::ClaimRejected(
                "the judge refused the claim".into(),
            ));
        }
        self.receive(store, &entry, &Incoming::Inline(bytes))?;
        let evaluation = self.evaluate(store)?;
        if evaluation.canonical != Some(epoch) {
            return Err(LogError::ClaimRejected(
                "the claim did not become canonical".into(),
            ));
        }
        Ok((entry, evaluation))
    }

    /// Appends the holder's `divergence` entry for `divergence` and records it.
    ///
    /// # Errors
    ///
    /// As [`AgentLog::append`].
    pub fn record_divergence<S: LogStore + ?Sized>(
        &self,
        store: &mut S,
        key: &NodeKey,
        divergence: &Divergence,
        projection: &DivergenceProjection,
        ts_ms: u64,
    ) -> Result<SignedEntry, LogError> {
        let payload = Payload::Divergence(Box::new(
            divergence
                .inputs
                .to_payload(divergence.tail_head, projection),
        ));
        let entry = self.append(store, key, &payload, ts_ms)?;
        let id = divergence.inputs.id();
        let mut record = store.divergence(&id)?.unwrap_or(DivergenceRecord {
            id,
            log_id: self.log_id,
            losing_epoch: divergence.inputs.stranded_epoch,
            fork_seq: divergence.inputs.fork.seq,
            tail_head_seq: divergence.tail_head.seq,
            recorded_entry: None,
        });
        record.recorded_entry = Some(entry.hash());
        store.put_divergence(&record)?;
        Ok(entry)
    }

    fn gate<S: LogStore + ?Sized>(&self, store: &S, key: &NodeKey) -> Result<Placement, LogError> {
        let placement = store
            .placement(&self.log_id)?
            .ok_or_else(|| LogError::NotHolder("no placement".into()))?;
        if placement.holder() != key.node_id() {
            return Err(LogError::NotHolder("another node holds the agent".into()));
        }
        if placement.state != PlacementState::Active {
            return Err(LogError::NotHolder(format!(
                "placement is {:?}",
                placement.state
            )));
        }
        if store.max_n_seen(&self.log_id)? != Some(placement.epoch.n) {
            return Err(LogError::NotHolder("a higher epoch is known".into()));
        }
        Ok(placement)
    }

    #[allow(clippy::too_many_arguments)]
    fn sign(
        &self,
        key: &NodeKey,
        epoch: Epoch,
        prev: Pos,
        kind: u16,
        flags: u16,
        payload: &[u8],
        ts_ms: u64,
    ) -> Result<SignedEntry, LogError> {
        let header = EntryHeader {
            log_id: self.log_id,
            epoch,
            seq: if prev.hash == EntryHash::ZERO {
                0
            } else {
                prev.seq + 1
            },
            prev: prev.hash,
            kind,
            flags,
            ts_ms,
            payload_len: payload.len() as u64,
            payload_hash: payload_hash(payload),
        };
        Ok(SignedEntry::sign(header, key)?)
    }

    // ----- replica side -----

    /// Verifies and stores one entry (any segment, any classification), idempotently. The entry's
    /// signature was verified when it was decoded into a [`SignedEntry`]. Call
    /// [`AgentLog::evaluate`] afterwards unless every result was `Stored { simple: true }`, a
    /// duplicate, or pending.
    ///
    /// # Errors
    ///
    /// [`LogError`] for an entry that breaks entry validity (C1 §3.2); it is not stored.
    pub fn receive<S: LogStore + ?Sized>(
        &self,
        store: &mut S,
        entry: &SignedEntry,
        incoming: &Incoming,
    ) -> Result<Received, LogError> {
        let header = *entry.header();
        if header.log_id != self.log_id {
            return Err(LogError::WrongLog);
        }
        let (state, bytes) = match incoming {
            Incoming::Inline(bytes) => {
                if bytes.len() as u64 != header.payload_len
                    || payload_hash(bytes) != header.payload_hash
                {
                    return Err(LogError::PayloadMismatch);
                }
                (PayloadState::Stored, Some(bytes.as_slice()))
            }
            Incoming::Pruned if header.is_compactable() => (PayloadState::Pruned, None),
            Incoming::Pruned | Incoming::ByHash => (PayloadState::Missing, None),
        };
        if let Some(existing) = store.entry(&entry.hash())? {
            if existing.payload_state == PayloadState::Missing && state != PayloadState::Missing {
                match bytes {
                    Some(bytes) => {
                        check_payload(&header, bytes)?;
                        store.attach_payload(&entry.hash(), bytes)?;
                    }
                    None => store.set_payload_state(&entry.hash(), state)?,
                }
                self.recompute_segment(store, &header.epoch)?;
                return Ok(Received::PayloadAttached);
            }
            return Ok(Received::Duplicate);
        }
        if let Some(bytes) = bytes {
            check_payload(&header, bytes)?;
        } else if header.seq == 0 && header.kind != kind::EPOCH_OPEN {
            return Err(LogError::ChainMismatch(
                "seq 0 must be an epoch_open".into(),
            ));
        }
        if header.seq > 0 {
            let Some(parent) = store.entry(&header.prev)? else {
                return Ok(Received::Pending {
                    missing: header.prev,
                });
            };
            if parent.log_id != self.log_id || parent.seq + 1 != header.seq {
                return Err(LogError::ChainMismatch(
                    "prev is not the entry at seq - 1 of this log".into(),
                ));
            }
            if (header.kind == kind::EPOCH_OPEN) == (parent.epoch == header.epoch) {
                return Err(LogError::ChainMismatch(
                    "only an epoch_open crosses epochs, and it must".into(),
                ));
            }
        }
        if let Some(bytes) = bytes {
            store.put_blob(&header.payload_hash, bytes)?;
        }
        let stored = entry.to_stored(state);
        store.put_entry(&stored)?;
        if header.kind == kind::EPOCH_OPEN {
            // One row per epoch. A claimant that opened one epoch twice (from two fork points)
            // keeps the open with the lower entry hash as the segment's start, whatever the
            // arrival order; the other open's entries are off the segment.
            let replaces = match store.segment(&self.log_id, &header.epoch)? {
                None => true,
                Some(row) => find_open(store, &row)?.is_none_or(|open| {
                    // `find_open` already sees this entry when it shares the row's start seq.
                    open.entry_hash == entry.hash() || entry.hash() < open.entry_hash
                }),
            };
            if replaces {
                store.put_segment(&Segment {
                    log_id: self.log_id,
                    epoch: header.epoch,
                    start_seq: header.seq,
                    parent: (header.seq > 0).then_some(header.prev),
                    head_seq: header.seq,
                    head_hash: entry.hash(),
                    complete_seq: stored.payload_complete().then_some(header.seq),
                    state: SegmentState::Quarantined,
                })?;
                self.recompute_segment(store, &header.epoch)?;
            }
            return Ok(Received::Stored { simple: false });
        }
        let segment = self.refresh_segment(store, &stored)?;
        let simple = header.kind != kind::EPOCH_SEAL
            && segment.is_some_and(|s| s.head_hash == entry.hash())
            && store.entries_at(&self.log_id, header.seq)?.len() == 1
            && store
                .placement(&self.log_id)?
                .is_some_and(|p| p.epoch == header.epoch && p.state == PlacementState::Active)
            && store.max_n_seen(&self.log_id)? == Some(header.epoch.n);
        Ok(Received::Stored { simple })
    }

    /// Advances the segment of a newly stored entry: incrementally when it extends the head,
    /// otherwise by a full walk (an equivocation sibling may change the head).
    fn refresh_segment<S: LogStore + ?Sized>(
        &self,
        store: &mut S,
        entry: &StoredEntry,
    ) -> Result<Option<Segment>, LogError> {
        let Some(mut segment) = store.segment(&self.log_id, &entry.epoch)? else {
            return Ok(None);
        };
        if entry.seq != segment.head_seq + 1 || entry.prev != segment.head_hash {
            return self.recompute_segment(store, &entry.epoch);
        }
        loop {
            let next = store
                .entries_at(&self.log_id, segment.head_seq + 1)?
                .into_iter()
                .filter(|e| {
                    e.epoch == segment.epoch
                        && e.prev == segment.head_hash
                        && e.kind != kind::EPOCH_OPEN
                })
                .min_by_key(|e| e.entry_hash);
            let Some(next) = next else { break };
            if segment.complete_seq == Some(segment.head_seq) && next.payload_complete() {
                segment.complete_seq = Some(next.seq);
            }
            segment.head_seq = next.seq;
            segment.head_hash = next.entry_hash;
        }
        store.put_segment(&segment)?;
        Ok(Some(segment))
    }

    /// Recomputes a segment's head and `complete_seq` from its `epoch_open`: at each seq the
    /// child with the lowest entry hash stays in the segment (C1 §3.8 equivocation rule).
    fn recompute_segment<S: LogStore + ?Sized>(
        &self,
        store: &mut S,
        epoch: &Epoch,
    ) -> Result<Option<Segment>, LogError> {
        let Some(mut segment) = store.segment(&self.log_id, epoch)? else {
            return Ok(None);
        };
        let Some(open) = find_open(store, &segment)? else {
            return Ok(Some(segment));
        };
        let mut head = open;
        let mut complete = head.payload_complete().then_some(head.seq);
        loop {
            let next = store
                .entries_at(&self.log_id, head.seq + 1)?
                .into_iter()
                .filter(|e| {
                    e.epoch == *epoch && e.prev == head.entry_hash && e.kind != kind::EPOCH_OPEN
                })
                .min_by_key(|e| e.entry_hash);
            let Some(next) = next else { break };
            if complete == Some(head.seq) && next.payload_complete() {
                complete = Some(next.seq);
            }
            head = next;
        }
        segment.head_seq = head.seq;
        segment.head_hash = head.entry_hash;
        segment.complete_seq = complete;
        store.put_segment(&segment)?;
        Ok(Some(segment))
    }

    // ----- evaluation -----

    /// Analyzes the log without writing: validity and ratification of every segment (C1 §5.3),
    /// the canonical segment and line, the placement state, segment classification and every
    /// divergence relative to the canonical line (C1 §3.8). A pure function of the stored entries
    /// and the judge.
    ///
    /// # Errors
    ///
    /// A store error.
    #[allow(clippy::too_many_lines)]
    pub fn analyze<S: LogStore + ?Sized>(&self, store: &S) -> Result<Analysis, LogError> {
        struct Info {
            row: Segment,
            open: StoredEntry,
            payload: Option<EpochOpen>,
            parent_epoch: Option<Epoch>,
            line_ok: bool,
            claim_ok: bool,
            valid: bool,
        }
        let mut infos: BTreeMap<Epoch, Info> = BTreeMap::new();
        for row in store.segments(&self.log_id)? {
            let Some(open) = find_open(store, &row)? else {
                continue;
            };
            let payload = match load_payload(store, &open)? {
                Some(Payload::EpochOpen(payload)) => Some(payload),
                _ => None,
            };
            let parent = match row.parent {
                Some(hash) => store.entry(&hash)?,
                None => None,
            };
            let structural = match &parent {
                None => row.start_seq == 0 && open.epoch.n == 1,
                Some(p) => open.epoch.n > p.epoch.n && open.epoch.n <= p.epoch.n + EPOCH_N_MAX_STEP,
            };
            let parent_ok = parent
                .as_ref()
                .is_none_or(|p| infos.get(&p.epoch).is_some_and(|i| i.line_ok));
            let line_ok = structural && parent_ok;
            let claim_ok = line_ok
                && match &payload {
                    Some(open_payload) => {
                        let signed = SignedEntry::from_stored(&open)?;
                        let parent_payload = match &parent {
                            Some(p) => load_payload(store, p)?,
                            None => None,
                        };
                        self.judge.claim_valid(&ClaimContext {
                            log_id: self.log_id,
                            open: &signed,
                            payload: open_payload,
                            parent: parent.as_ref(),
                            parent_payload: parent_payload.as_ref(),
                        })
                    }
                    None => false,
                };
            infos.insert(
                row.epoch,
                Info {
                    parent_epoch: parent.as_ref().map(|p| p.epoch),
                    row,
                    open,
                    payload,
                    line_ok,
                    claim_ok,
                    valid: false,
                },
            );
        }
        let mut ratified = BTreeSet::new();
        for (epoch, info) in infos.iter_mut().rev() {
            info.valid = info.line_ok && (info.claim_ok || ratified.contains(epoch));
            if info.valid
                && let Some(parent) = info.parent_epoch
            {
                ratified.insert(parent);
            }
        }
        let mut analysis = Analysis::default();
        let Some((&canonical, canonical_info)) = infos.iter().rev().find(|(_, i)| i.valid) else {
            analysis.segments = infos.into_values().map(|i| i.row).collect();
            return Ok(analysis);
        };
        // The canonical line, from the canonical segment's head back to genesis.
        let mut line = Vec::new();
        let mut cursor = store.entry(&canonical_info.row.head_hash)?;
        while let Some(entry) = cursor {
            let prev = entry.prev;
            let at_genesis = entry.seq == 0;
            line.push(entry);
            if at_genesis {
                break;
            }
            cursor = store.entry(&prev)?;
        }
        line.reverse();
        if line.first().is_none_or(|e| e.seq != 0) {
            return Err(StoreError::Corrupt("canonical line does not reach genesis".into()).into());
        }
        let agent_id = match load_payload(store, &line[0])? {
            Some(Payload::EpochOpen(open)) => open.agent_id,
            _ => None,
        };
        let head = line.last().cloned().unwrap_or_else(|| line[0].clone());
        let max_n = store.max_n_seen(&self.log_id)?.unwrap_or(0);
        let placement_state = if max_n > canonical.n {
            PlacementState::SupersededUnknown
        } else if head.kind == kind::EPOCH_SEAL {
            match load_payload(store, &head)? {
                Some(Payload::EpochSeal(EpochSeal {
                    next_epoch: Some(next),
                    ..
                })) => PlacementState::SealedTo(next),
                _ => PlacementState::Placeless,
            }
        } else {
            PlacementState::Active
        };
        let on_line: BTreeSet<EntryHash> = line.iter().map(|e| e.entry_hash).collect();
        for (epoch, info) in &infos {
            let mut row = info.row.clone();
            row.state = if *epoch == canonical {
                if head.kind == kind::EPOCH_SEAL {
                    SegmentState::Sealed
                } else {
                    SegmentState::Open
                }
            } else if on_line.contains(&info.open.entry_hash) {
                SegmentState::CanonicalAncestor
            } else if !info.line_ok {
                SegmentState::Invalid
            } else if info.payload.is_none() {
                SegmentState::Quarantined
            } else {
                SegmentState::Divergent
            };
            analysis.segments.push(row);
        }
        analysis.divergences = self.divergences(
            store,
            &infos.keys().copied().collect::<Vec<_>>(),
            &line,
            &on_line,
            agent_id.as_ref(),
        )?;
        analysis.agent_id = agent_id;
        analysis.canonical = Some(canonical);
        analysis.placement_state = Some(placement_state);
        analysis.line = line;
        Ok(analysis)
    }

    fn divergences<S: LogStore + ?Sized>(
        &self,
        store: &S,
        epochs: &[Epoch],
        line: &[StoredEntry],
        on_line: &BTreeSet<EntryHash>,
        agent_id: Option<&AgentId>,
    ) -> Result<Vec<Divergence>, LogError> {
        let Some(agent_id) = agent_id else {
            return Ok(Vec::new());
        };
        let mut off: BTreeMap<EntryHash, StoredEntry> = BTreeMap::new();
        for epoch in epochs {
            for entry in store.segment_entries(&self.log_id, epoch, 0, usize::MAX)? {
                if !on_line.contains(&entry.entry_hash) {
                    off.insert(entry.entry_hash, entry);
                }
            }
        }
        // The root of each off-line entry: its first ancestor whose parent is on the line.
        let mut root_of: BTreeMap<EntryHash, Option<EntryHash>> = BTreeMap::new();
        for hash in off.keys().copied().collect::<Vec<_>>() {
            let mut path = Vec::new();
            let mut cursor = hash;
            let root = loop {
                if let Some(known) = root_of.get(&cursor) {
                    break *known;
                }
                let Some(entry) = off.get(&cursor) else {
                    break None;
                };
                path.push(cursor);
                if entry.seq > 0 && on_line.contains(&entry.prev) {
                    break Some(cursor);
                }
                if entry.seq == 0 {
                    break None;
                }
                cursor = entry.prev;
            };
            for step in path {
                root_of.insert(step, root);
            }
        }
        let mut by_id: BTreeMap<core_store::DivergenceId, Divergence> = BTreeMap::new();
        let mut tails: BTreeMap<EntryHash, Vec<&StoredEntry>> = BTreeMap::new();
        for (hash, root) in &root_of {
            if let Some(root) = root {
                tails.entry(*root).or_default().push(&off[hash]);
            }
        }
        for (root_hash, members) in tails {
            let root = &off[&root_hash];
            let fork_seq = root.seq - 1;
            let (Some(fork), Some(winner)) = (
                line.get(usize::try_from(fork_seq).unwrap_or(usize::MAX)),
                line.get(usize::try_from(root.seq).unwrap_or(usize::MAX)),
            ) else {
                continue;
            };
            let mut competing = [root.entry_hash, winner.entry_hash];
            competing.sort();
            let inputs = DivergenceInputs {
                log_id: self.log_id,
                agent_id: agent_id.clone(),
                fork_epoch: fork.epoch,
                fork: Pos {
                    seq: fork.seq,
                    hash: fork.entry_hash,
                },
                competing,
                stranded_epoch: root.epoch,
                winning_epoch: winner.epoch,
                equivocation: root.epoch == winner.epoch,
            };
            let mut tail: Vec<(u64, EntryHash)> =
                members.iter().map(|e| (e.seq, e.entry_hash)).collect();
            tail.sort();
            let id = inputs.id();
            match by_id.get_mut(&id) {
                Some(existing) => {
                    // A second divergent entry of the same stranded epoch at the same fork: one
                    // divergence; the lower root hash names it. Roots are visited in ascending
                    // hash order, so the first one already is the lower.
                    let mut all: Vec<(u64, EntryHash)> = existing
                        .tail
                        .iter()
                        .map(|h| (off[h].seq, *h))
                        .chain(tail)
                        .collect();
                    all.sort();
                    existing.tail = all.iter().map(|(_, h)| *h).collect();
                    existing.tail_head = tail_head(&all);
                }
                None => {
                    by_id.insert(
                        id,
                        Divergence {
                            inputs,
                            tail_head: tail_head(&tail),
                            tail: tail.iter().map(|(_, h)| *h).collect(),
                        },
                    );
                }
            }
        }
        Ok(by_id.into_values().collect())
    }

    /// Evaluates the log and writes the outcome: segment states, the placement row (adopting a
    /// higher canonical epoch with its `epoch_open` in this transaction, F2, or falling back after
    /// a tip lost validity) and divergence records.
    ///
    /// # Errors
    ///
    /// A store error.
    pub fn evaluate<S: LogStore + ?Sized>(&self, store: &mut S) -> Result<Evaluation, LogError> {
        let analysis = self.analyze(store)?;
        let mut evaluation = Evaluation {
            canonical: analysis.canonical,
            tip: analysis.line.last().map(|e| Pos {
                seq: e.seq,
                hash: e.entry_hash,
            }),
            ..Evaluation::default()
        };
        let (Some(canonical), Some(state), Some(agent)) = (
            analysis.canonical,
            analysis.placement_state,
            analysis.agent_id.clone(),
        ) else {
            evaluation.placement = store.placement(&self.log_id)?;
            return Ok(evaluation);
        };
        let before = store.placement(&self.log_id)?;
        match &before {
            Some(current) if current.epoch == canonical => {
                if current.state != state {
                    store.set_placement_state(&self.log_id, &canonical, state)?;
                }
                let open = canonical_open(store, &analysis.segments, canonical)?;
                if open.entry_hash != current.open_entry_hash {
                    // The claimant opened its epoch twice and the lower-hash open arrived later:
                    // same epoch, so no fencing rule is involved; only the named open changes.
                    let mut row = store
                        .placement(&self.log_id)?
                        .ok_or_else(|| StoreError::Corrupt("placement vanished".into()))?;
                    row.open_entry_hash = open.entry_hash;
                    store.write_placement_row(&row)?;
                }
            }
            Some(current) if canonical < current.epoch => {
                store.fall_back_placement(&self.log_id, &canonical, state)?;
            }
            _ => {
                let open = canonical_open(store, &analysis.segments, canonical)?;
                let payload = store
                    .blob(&open.payload_hash)?
                    .ok_or_else(|| StoreError::Corrupt("canonical epoch_open payload".into()))?;
                store.adopt_epoch(EpochClaim::new(agent, open, payload)?)?;
                if state != PlacementState::Active {
                    store.set_placement_state(&self.log_id, &canonical, state)?;
                }
            }
        }
        for row in &analysis.segments {
            if store.segment(&self.log_id, &row.epoch)?.as_ref() != Some(row) {
                store.put_segment(row)?;
            }
        }
        for divergence in &analysis.divergences {
            let id = divergence.inputs.id();
            let existing = store.divergence(&id)?;
            let record = DivergenceRecord {
                id,
                log_id: self.log_id,
                losing_epoch: divergence.inputs.stranded_epoch,
                fork_seq: divergence.inputs.fork.seq,
                tail_head_seq: divergence.tail_head.seq,
                recorded_entry: existing.as_ref().and_then(|r| r.recorded_entry),
            };
            if existing.as_ref() != Some(&record) {
                store.put_divergence(&record)?;
                evaluation
                    .changed_divergences
                    .push(divergence.inputs.clone());
            }
        }
        evaluation.placement = store.placement(&self.log_id)?;
        evaluation.placement_changed = evaluation.placement != before;
        evaluation.divergences = analysis.divergences;
        Ok(evaluation)
    }

    /// The canonical line, genesis first: the only entries a materializer applies (divergent
    /// tails never are, L10).
    ///
    /// # Errors
    ///
    /// A store error.
    pub fn canonical_line<S: LogStore + ?Sized>(
        &self,
        store: &S,
    ) -> Result<Vec<StoredEntry>, LogError> {
        Ok(self.analyze(store)?.line)
    }
}

fn canonical_open<S: LogStore + ?Sized>(
    store: &S,
    segments: &[Segment],
    canonical: Epoch,
) -> Result<StoredEntry, LogError> {
    segments
        .iter()
        .find(|s| s.epoch == canonical)
        .map(|s| find_open(store, s))
        .transpose()?
        .flatten()
        .ok_or_else(|| StoreError::Corrupt("canonical epoch_open missing".into()).into())
}

/// The `epoch_open` of a segment row (the lowest hash if a claimant opened one epoch twice).
fn find_open<S: LogStore + ?Sized>(
    store: &S,
    segment: &Segment,
) -> Result<Option<StoredEntry>, LogError> {
    Ok(store
        .entries_at(&segment.log_id, segment.start_seq)?
        .into_iter()
        .filter(|e| e.epoch == segment.epoch && e.kind == kind::EPOCH_OPEN)
        .min_by_key(|e| e.entry_hash))
}

/// The decoded payload of a stored entry, when held.
pub(crate) fn load_payload<S: LogStore + ?Sized>(
    store: &S,
    entry: &StoredEntry,
) -> Result<Option<Payload>, LogError> {
    if entry.payload_state != PayloadState::Stored {
        return Ok(None);
    }
    let Some(bytes) = store.blob(&entry.payload_hash)? else {
        return Ok(None);
    };
    Ok(Payload::decode(entry.kind, &bytes).ok())
}

/// Entry-validity checks that need the payload (C1 §3.2 rule 3, and the payload schema).
fn check_payload(header: &EntryHeader, bytes: &[u8]) -> Result<(), LogError> {
    let payload = Payload::decode(header.kind, bytes)?;
    match (&payload, header.seq) {
        (Payload::EpochOpen(open), 0) => {
            let ok = open.reason == Reason::Genesis
                && header.epoch.n == 1
                && open
                    .agent_id
                    .as_ref()
                    .is_some_and(|a| log_id(a) == header.log_id);
            if !ok {
                return Err(LogError::ChainMismatch(
                    "seq 0 must be a genesis of this log's agent at n 1".into(),
                ));
            }
        }
        (Payload::EpochOpen(open), _) if open.reason == Reason::Genesis => {
            return Err(LogError::ChainMismatch("genesis only at seq 0".into()));
        }
        (_, 0) => {
            return Err(LogError::ChainMismatch(
                "seq 0 must be an epoch_open".into(),
            ));
        }
        _ => {}
    }
    Ok(())
}

fn tail_head(sorted: &[(u64, EntryHash)]) -> Pos {
    let top = sorted.last().map_or(0, |(seq, _)| *seq);
    let hash = sorted
        .iter()
        .filter(|(seq, _)| *seq == top)
        .map(|(_, h)| *h)
        .min()
        .unwrap_or(EntryHash::ZERO);
    Pos { seq: top, hash }
}
