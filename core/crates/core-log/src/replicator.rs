//! The `baken/log/1` session as a pure state machine (C1 §4.3-4.6): hello, have, want, catch-up
//! then live tail, credit-by-ack backpressure, stateless resume. Every replica relays: entries a
//! session stores are reported as [`SessionEvent::Stored`], and the node hands them to its other
//! sessions through [`Session::on_stored`].
//!
//! The session owns no store and reads no clock. Each input names the store transaction and the
//! time; the driver commits the transaction before it sends what [`Session::poll_transmit`]
//! returns, so an `ack` always follows a durable commit.

use std::collections::{BTreeMap, BTreeSet, VecDeque};

use core_proto::NodeId;
use core_store::{EntryHash, LogId, LogStore, PayloadState, SegmentState, StoreError};

use crate::agent_log::{AgentLog, Evaluation, Incoming, LogError, Received};
use crate::hashes::log_digest;
use crate::header::{SIGNED_LEN, SignedEntry};
use crate::judge::ClaimJudge;
use crate::payload::kind;
use crate::value::Pos;
use crate::wire::{
    AgentHave, Hello, MAX_HAVE_PER_FRAME, Message, Mode, SegSummary, VERSION, WirePayload,
};
use crate::{
    BACKPRESSURE_RESYNC_MS, MAX_BATCH_BYTES, MAX_BATCH_ENTRIES, MAX_INLINE_PAYLOAD,
    PENDING_PER_LOG, PING_INTERVAL_MS, UNACKED_PER_CONN, UNACKED_PER_LOG,
};

/// What this node announces in `hello`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SessionConfig {
    /// This node.
    pub node_id: NodeId,
    /// The constellation.
    pub constellation_id: [u8; 32],
    /// This node's membership heads.
    pub member_heads: Vec<[u8; 32]>,
    /// Live, or heads-only (backgrounded or metered).
    pub mode: Mode,
    /// This node's store format.
    pub store_format: u64,
}

/// Something the node acts on.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SessionEvent {
    /// New entries were stored: relay them to the node's other sessions.
    Stored {
        /// The log.
        log_id: LogId,
        /// The entries, in storage order.
        entries: Vec<EntryHash>,
    },
    /// A log was re-evaluated.
    Evaluated {
        /// The log.
        log_id: LogId,
        /// The outcome.
        evaluation: Box<Evaluation>,
    },
    /// The peer's membership heads differ: sync `baken/member/1`; entries are stored meanwhile.
    MembershipDiffers,
    /// An entry was refused.
    Rejected {
        /// The log.
        log_id: LogId,
        /// Why (the wire code).
        code: &'static str,
    },
    /// The peer sent `error`.
    PeerError {
        /// Its code.
        code: String,
        /// Its message.
        message: String,
    },
    /// A `head` arrived for something this heads-only node does not hold; want it when ready.
    HeadAvailable {
        /// The log.
        log_id: LogId,
        /// The segment.
        epoch: core_proto::Epoch,
        /// The peer's head.
        pos: Pos,
    },
}

#[derive(Clone, Debug)]
struct Cursor {
    log_id: LogId,
    epoch: core_proto::Epoch,
    from_seq: u64,
    sent: BTreeSet<EntryHash>,
}

#[derive(Clone, Copy, Debug)]
struct Sent {
    epoch: core_proto::Epoch,
    seq: u64,
    bytes: usize,
}

/// One `baken/log/1` session with one peer.
#[derive(Debug)]
pub struct Session {
    config: SessionConfig,
    peer: Option<Hello>,
    peer_have: Vec<AgentHave>,
    control: VecDeque<Message>,
    cursors: VecDeque<Cursor>,
    live: BTreeSet<LogId>,
    live_queue: BTreeMap<LogId, VecDeque<EntryHash>>,
    over_budget_since: BTreeMap<LogId, u64>,
    unacked: BTreeMap<LogId, Vec<Sent>>,
    unacked_total: usize,
    pending: BTreeMap<LogId, BTreeMap<EntryHash, Vec<(SignedEntry, Incoming)>>>,
    requested: BTreeMap<(LogId, core_proto::Epoch), u64>,
    peer_segments: BTreeMap<(LogId, core_proto::Epoch), SegSummary>,
    full_requested: BTreeSet<(LogId, core_proto::Epoch)>,
    subscribed: BTreeSet<LogId>,
    announced: BTreeSet<LogId>,
    last_sent_ms: u64,
    nonce: u64,
    closing: bool,
}

impl Session {
    /// A session that has sent nothing yet.
    #[must_use]
    pub fn new(config: SessionConfig) -> Self {
        Self {
            config,
            peer: None,
            peer_have: Vec::new(),
            control: VecDeque::new(),
            cursors: VecDeque::new(),
            live: BTreeSet::new(),
            live_queue: BTreeMap::new(),
            over_budget_since: BTreeMap::new(),
            unacked: BTreeMap::new(),
            unacked_total: 0,
            pending: BTreeMap::new(),
            requested: BTreeMap::new(),
            peer_segments: BTreeMap::new(),
            full_requested: BTreeSet::new(),
            subscribed: BTreeSet::new(),
            announced: BTreeSet::new(),
            last_sent_ms: 0,
            nonce: 0,
            closing: false,
        }
    }

    /// The peer's hello, once received.
    #[must_use]
    pub const fn peer(&self) -> Option<&Hello> {
        self.peer.as_ref()
    }

    /// Whether the session should close once its queued frames are sent.
    #[must_use]
    pub const fn is_closing(&self) -> bool {
        self.closing
    }

    /// Whether nothing is queued or in flight from this side.
    #[must_use]
    pub fn is_idle(&self) -> bool {
        self.control.is_empty()
            && self.cursors.is_empty()
            && self.live_queue.values().all(VecDeque::is_empty)
    }

    /// Unacked bytes this side has sent.
    #[must_use]
    pub const fn unacked_bytes(&self) -> usize {
        self.unacked_total
    }

    /// Queues `hello` (step 1). Resumption is stateless: a new session always starts here, and
    /// every `want` it sends names the receiver's durable stored head.
    ///
    /// # Errors
    ///
    /// A store error.
    pub fn start<S: LogStore + ?Sized>(
        &mut self,
        store: &S,
        now_ms: u64,
    ) -> Result<(), StoreError> {
        self.last_sent_ms = now_ms;
        self.control.push_back(Message::Hello(Hello {
            version: VERSION,
            node_id: self.config.node_id,
            constellation_id: self.config.constellation_id,
            member_heads: self.config.member_heads.clone(),
            log_digest: digest(store)?,
            mode: self.config.mode,
            store_format: self.config.store_format,
        }));
        Ok(())
    }

    /// Handles one received message inside the store transaction `store`.
    ///
    /// # Errors
    ///
    /// A store error; the driver must then abort the transaction and end the session.
    pub fn handle<S: LogStore + ?Sized, J: ClaimJudge + ?Sized>(
        &mut self,
        store: &mut S,
        judge: &J,
        message: Message,
        now_ms: u64,
    ) -> Result<Vec<SessionEvent>, StoreError> {
        let _ = now_ms;
        let mut events = Vec::new();
        match message {
            Message::Hello(hello) => self.on_hello(store, hello, &mut events)?,
            Message::Have { agents, last } => {
                self.peer_have.extend(agents);
                if last {
                    self.plan_wants(store)?;
                }
            }
            Message::Want {
                log_id,
                epoch,
                after,
                live,
                ..
            } => self.on_want(store, log_id, epoch, after, live)?,
            Message::Entries { log_id, entries } => {
                self.on_entries(store, judge, log_id, entries, &mut events)?;
            }
            Message::Ack {
                log_id,
                epoch,
                complete,
            } => {
                if let Some(sent) = self.unacked.get_mut(&log_id) {
                    let before: usize = sent.iter().map(|s| s.bytes).sum();
                    sent.retain(|s| !(s.epoch == epoch && s.seq <= complete.seq));
                    let after: usize = sent.iter().map(|s| s.bytes).sum();
                    self.unacked_total -= before - after;
                }
            }
            Message::Head { log_id, epoch, pos } => {
                self.on_head(store, log_id, epoch, pos, &mut events)?;
            }
            Message::Ping { .. } => {}
            Message::Resync { log_id, .. } => self.rewant_log(store, log_id)?,
            Message::Error { code, message, .. } => {
                if code == "constellation_mismatch" {
                    self.closing = true;
                }
                events.push(SessionEvent::PeerError { code, message });
            }
        }
        Ok(events)
    }

    fn on_hello<S: LogStore + ?Sized>(
        &mut self,
        store: &S,
        hello: Hello,
        events: &mut Vec<SessionEvent>,
    ) -> Result<(), StoreError> {
        if hello.constellation_id != self.config.constellation_id {
            self.control.push_back(Message::Error {
                code: "constellation_mismatch".into(),
                message: "different constellation".into(),
                log_id: None,
            });
            self.closing = true;
            return Ok(());
        }
        let mut ours = self.config.member_heads.clone();
        ours.sort_unstable();
        ours.dedup();
        if ours != hello.member_heads {
            events.push(SessionEvent::MembershipDiffers);
        }
        let same = hello.log_digest == digest(store)?;
        self.peer = Some(hello);
        if same {
            // Identical logs: subscribe to the live tail of every log, no catch-up.
            for log_id in store.logs()? {
                if let Some(want) = live_want(store, log_id)? {
                    self.control.push_back(want);
                }
            }
        } else {
            let haves = agent_haves(store)?;
            if haves.is_empty() {
                self.control.push_back(Message::Have {
                    agents: Vec::new(),
                    last: true,
                });
            }
            let chunks: Vec<&[AgentHave]> = haves.chunks(MAX_HAVE_PER_FRAME).collect();
            for (index, chunk) in chunks.iter().enumerate() {
                self.control.push_back(Message::Have {
                    agents: chunk.to_vec(),
                    last: index + 1 == chunks.len(),
                });
            }
        }
        Ok(())
    }

    /// Step 4: wants for everything the peer has that this side lacks; unknown segments first
    /// (fencing information travels first), then gaps in known segments. The last want of each log
    /// subscribes to its live tail.
    fn plan_wants<S: LogStore + ?Sized>(&mut self, store: &S) -> Result<(), StoreError> {
        let mut unknown = Vec::new();
        let mut gaps = Vec::new();
        let mut logs: BTreeSet<LogId> = store.logs()?.into_iter().collect();
        for have in std::mem::take(&mut self.peer_have) {
            logs.insert(have.log_id);
            let local = store.segments(&have.log_id)?;
            for seg in &have.segments {
                self.requested
                    .entry((have.log_id, seg.epoch))
                    .and_modify(|s| *s = (*s).max(seg.head.seq))
                    .or_insert(seg.head.seq);
                match local.iter().find(|l| l.epoch == seg.epoch) {
                    None => {
                        self.peer_segments
                            .insert((have.log_id, seg.epoch), seg.clone());
                        unknown.push((seg.epoch, have.log_id, None));
                    }
                    Some(mine) => {
                        let (count, xor) = segment_set(store, &have.log_id, &mine.epoch)?;
                        if (count, xor) != (seg.entries, seg.set_xor) {
                            // A pure extension of this side's chain: want from its head. Anything
                            // else (other branches): the whole segment. After the extension
                            // arrives the sets are compared again (`recheck`).
                            let extension = store.entry(&seg.head.hash)?.is_none()
                                && seg.head.seq > mine.head_seq
                                && seg.entries.checked_sub(count)
                                    == Some(seg.head.seq - mine.head_seq);
                            let after = if extension {
                                Some(Pos {
                                    seq: mine.head_seq,
                                    hash: mine.head_hash,
                                })
                            } else {
                                self.full_requested.insert((have.log_id, seg.epoch));
                                None
                            };
                            self.peer_segments
                                .insert((have.log_id, seg.epoch), seg.clone());
                            gaps.push((seg.epoch, have.log_id, after));
                        }
                    }
                }
            }
        }
        unknown.sort_by_key(|(epoch, log, _)| (*epoch, *log));
        gaps.sort_by_key(|(epoch, log, _)| (*epoch, *log));
        let mut wants: Vec<Message> = unknown
            .into_iter()
            .chain(gaps)
            .map(|(epoch, log_id, after)| Message::Want {
                log_id,
                epoch,
                after,
                live: false,
                payloads: true,
            })
            .collect();
        for log_id in logs {
            let last = wants
                .iter_mut()
                .rev()
                .find(|w| matches!(w, Message::Want { log_id: l, .. } if *l == log_id));
            match last {
                Some(Message::Want { live, .. }) => *live = true,
                _ => {
                    if let Some(want) = live_want(store, log_id)? {
                        wants.push(want);
                    }
                }
            }
        }
        self.control.extend(wants);
        Ok(())
    }

    fn on_want<S: LogStore + ?Sized>(
        &mut self,
        store: &S,
        log_id: LogId,
        epoch: core_proto::Epoch,
        after: Option<Pos>,
        live: bool,
    ) -> Result<(), StoreError> {
        if live {
            self.live.insert(log_id);
            // Hint every other segment's head, so the peer can want what it has not seen.
            for segment in store.segments(&log_id)? {
                if segment.epoch != epoch {
                    self.control.push_back(Message::Head {
                        log_id,
                        epoch: segment.epoch,
                        pos: Pos {
                            seq: segment.head_seq,
                            hash: segment.head_hash,
                        },
                    });
                }
            }
        }
        if store.segment(&log_id, &epoch)?.is_none() {
            return Ok(());
        }
        // No `after`: every entry this side holds in that epoch, every branch and every open.
        let from_seq = after.map_or(0, |p| p.seq + 1);
        self.cursors.push_back(Cursor {
            log_id,
            epoch,
            from_seq,
            sent: BTreeSet::new(),
        });
        Ok(())
    }

    fn on_head<S: LogStore + ?Sized>(
        &mut self,
        store: &S,
        log_id: LogId,
        epoch: core_proto::Epoch,
        pos: Pos,
        events: &mut Vec<SessionEvent>,
    ) -> Result<(), StoreError> {
        if store.entry(&pos.hash)?.is_some() {
            return Ok(());
        }
        if self.config.mode == Mode::HeadsOnly {
            events.push(SessionEvent::HeadAvailable { log_id, epoch, pos });
            return Ok(());
        }
        if self
            .requested
            .get(&(log_id, epoch))
            .is_some_and(|s| *s >= pos.seq)
        {
            return Ok(());
        }
        self.requested.insert((log_id, epoch), pos.seq);
        let segment = store.segment(&log_id, &epoch)?;
        let after = segment.as_ref().map(|s| Pos {
            seq: s.head_seq,
            hash: s.head_hash,
        });
        // A head for a log this side has not subscribed to (a log new to it): subscribe now.
        self.control.push_back(Message::Want {
            log_id,
            epoch,
            after,
            live: !self.subscribed.contains(&log_id),
            payloads: true,
        });
        Ok(())
    }

    /// Wants a segment the node chose to fetch (heads-only mode).
    ///
    /// # Errors
    ///
    /// A store error.
    pub fn want_now<S: LogStore + ?Sized>(
        &mut self,
        store: &S,
        log_id: LogId,
        epoch: core_proto::Epoch,
    ) -> Result<(), StoreError> {
        let after = store.segment(&log_id, &epoch)?.map(|s| Pos {
            seq: s.head_seq,
            hash: s.head_hash,
        });
        self.control.push_back(Message::Want {
            log_id,
            epoch,
            after,
            live: false,
            payloads: true,
        });
        Ok(())
    }

    /// After `resync` (or a dropped pending buffer): re-want every segment of the log from this
    /// side's stored head, the last one live.
    fn rewant_log<S: LogStore + ?Sized>(
        &mut self,
        store: &S,
        log_id: LogId,
    ) -> Result<(), StoreError> {
        let segments = store.segments(&log_id)?;
        let count = segments.len();
        for (index, segment) in segments.into_iter().enumerate() {
            self.control.push_back(Message::Want {
                log_id,
                epoch: segment.epoch,
                after: Some(Pos {
                    seq: segment.head_seq,
                    hash: segment.head_hash,
                }),
                live: index + 1 == count,
                payloads: true,
            });
        }
        Ok(())
    }

    fn on_entries<S: LogStore + ?Sized, J: ClaimJudge + ?Sized>(
        &mut self,
        store: &mut S,
        judge: &J,
        log_id: LogId,
        entries: Vec<(Vec<u8>, WirePayload)>,
        events: &mut Vec<SessionEvent>,
    ) -> Result<(), StoreError> {
        let log = AgentLog::new(log_id, judge);
        let mut taken: BTreeMap<core_proto::Epoch, Pos> = BTreeMap::new();
        let mut stored = Vec::new();
        let mut evaluate = false;
        for (bytes, payload) in entries {
            let entry = match SignedEntry::from_bytes(&bytes) {
                Ok(entry) => entry,
                Err(error) => {
                    let error = LogError::Header(error);
                    self.reject(log_id, &error, events);
                    continue;
                }
            };
            let header = entry.header();
            let pos = Pos {
                seq: header.seq,
                hash: entry.hash(),
            };
            taken
                .entry(header.epoch)
                .and_modify(|p| {
                    if pos.seq > p.seq {
                        *p = pos;
                    }
                })
                .or_insert(pos);
            let incoming = match payload {
                WirePayload::Inline(bytes) => Incoming::Inline(bytes),
                WirePayload::ByHash => Incoming::ByHash,
                WirePayload::Pruned => Incoming::Pruned,
            };
            self.accept(
                store,
                &log,
                entry,
                incoming,
                &mut stored,
                &mut evaluate,
                events,
            )?;
        }
        if evaluate {
            let evaluation = match log.evaluate(store) {
                Ok(evaluation) => evaluation,
                Err(LogError::Store(error)) => return Err(error),
                Err(error) => {
                    self.reject(log_id, &error, events);
                    Evaluation::default()
                }
            };
            events.push(SessionEvent::Evaluated {
                log_id,
                evaluation: Box::new(evaluation),
            });
        }
        for (epoch, complete) in &taken {
            self.control.push_back(Message::Ack {
                log_id,
                epoch: *epoch,
                complete: *complete,
            });
        }
        for epoch in taken.keys() {
            self.recheck(store, log_id, *epoch)?;
        }
        if !stored.is_empty() {
            events.push(SessionEvent::Stored {
                log_id,
                entries: stored,
            });
        }
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    fn accept<S: LogStore + ?Sized, J: ClaimJudge + ?Sized>(
        &mut self,
        store: &mut S,
        log: &AgentLog<'_, J>,
        entry: SignedEntry,
        incoming: Incoming,
        stored: &mut Vec<EntryHash>,
        evaluate: &mut bool,
        events: &mut Vec<SessionEvent>,
    ) -> Result<(), StoreError> {
        let log_id = log.log_id();
        let mut stack = vec![(entry, incoming)];
        while let Some((entry, incoming)) = stack.pop() {
            match log.receive(store, &entry, &incoming) {
                Ok(Received::Stored { simple }) => {
                    *evaluate |= !simple;
                    stored.push(entry.hash());
                    if let Some(children) = self
                        .pending
                        .get_mut(&log_id)
                        .and_then(|p| p.remove(&entry.hash()))
                    {
                        stack.extend(children);
                    }
                }
                Ok(Received::PayloadAttached) => {
                    *evaluate |= entry.header().kind == kind::EPOCH_OPEN
                        || entry.header().kind == kind::EPOCH_SEAL;
                }
                Ok(Received::Duplicate) => {}
                Ok(Received::Pending { missing }) => {
                    let buffer = self.pending.entry(log_id).or_default();
                    let count: usize = buffer.values().map(Vec::len).sum();
                    if count >= PENDING_PER_LOG {
                        self.pending.remove(&log_id);
                        self.rewant_log(store, log_id)?;
                    } else {
                        let epoch = entry.header().epoch;
                        let is_open = entry.header().kind == kind::EPOCH_OPEN;
                        buffer.entry(missing).or_default().push((entry, incoming));
                        // The parent sits on a branch this side never asked for: want the whole
                        // epoch (a plain entry's parent shares its epoch), or, for an open, every
                        // segment the peer announced.
                        if is_open {
                            let epochs: Vec<core_proto::Epoch> = self
                                .peer_segments
                                .keys()
                                .filter(|(log, _)| *log == log_id)
                                .map(|(_, e)| *e)
                                .collect();
                            for epoch in epochs {
                                self.want_whole(log_id, epoch);
                            }
                        } else {
                            self.want_whole(log_id, epoch);
                        }
                    }
                }
                Err(LogError::Store(error)) => return Err(error),
                Err(error) => self.reject(log_id, &error, events),
            }
        }
        Ok(())
    }

    /// Once this side holds the peer's announced head of a segment, compares the two entry sets;
    /// if they still differ (branches below the head), wants the whole segment once.
    fn recheck<S: LogStore + ?Sized>(
        &mut self,
        store: &S,
        log_id: LogId,
        epoch: core_proto::Epoch,
    ) -> Result<(), StoreError> {
        let key = (log_id, epoch);
        let Some(peer) = self.peer_segments.get(&key) else {
            return Ok(());
        };
        if store.entry(&peer.head.hash)?.is_none() {
            return Ok(());
        }
        let peer_set = (peer.entries, peer.set_xor);
        if segment_set(store, &log_id, &epoch)? == peer_set {
            self.peer_segments.remove(&key);
            return Ok(());
        }
        self.want_whole(log_id, epoch);
        Ok(())
    }

    /// Wants every entry of `(log_id, epoch)` the peer holds, once per session.
    fn want_whole(&mut self, log_id: LogId, epoch: core_proto::Epoch) {
        if self.full_requested.insert((log_id, epoch)) {
            self.peer_segments.remove(&(log_id, epoch));
            self.control.push_back(Message::Want {
                log_id,
                epoch,
                after: None,
                live: false,
                payloads: true,
            });
        }
    }

    fn reject(&mut self, log_id: LogId, error: &LogError, events: &mut Vec<SessionEvent>) {
        let code = error.wire_code();
        events.push(SessionEvent::Rejected { log_id, code });
        self.control.push_back(Message::Error {
            code: code.into(),
            message: error.to_string(),
            log_id: Some(log_id),
        });
    }

    /// Entries stored by someone else (the holder's own appends, or another session): push them
    /// to this peer if it subscribed to the log's live tail. `from_this_peer` suppresses the echo.
    ///
    /// # Errors
    ///
    /// A store error.
    pub fn on_stored<S: LogStore + ?Sized>(
        &mut self,
        store: &S,
        log_id: LogId,
        entries: &[EntryHash],
    ) -> Result<(), StoreError> {
        if !self.live.contains(&log_id) {
            // The peer has not subscribed to this log (it may not know it exists): announce its
            // segments once; the peer wants them, live.
            if self.announced.insert(log_id) {
                for segment in store.segments(&log_id)? {
                    self.control.push_back(Message::Head {
                        log_id,
                        epoch: segment.epoch,
                        pos: Pos {
                            seq: segment.head_seq,
                            hash: segment.head_hash,
                        },
                    });
                }
            }
            return Ok(());
        }
        let heads_only = self
            .peer
            .as_ref()
            .is_some_and(|p| p.mode == Mode::HeadsOnly);
        if heads_only {
            let mut epochs = BTreeSet::new();
            for hash in entries {
                if let Some(entry) = store.entry(hash)? {
                    epochs.insert(entry.epoch);
                }
            }
            for epoch in epochs {
                if let Some(segment) = store.segment(&log_id, &epoch)? {
                    self.control.push_back(Message::Head {
                        log_id,
                        epoch,
                        pos: Pos {
                            seq: segment.head_seq,
                            hash: segment.head_hash,
                        },
                    });
                }
            }
            return Ok(());
        }
        let queue = self.live_queue.entry(log_id).or_default();
        for hash in entries {
            let is_open = store
                .entry(hash)?
                .is_some_and(|e| e.kind == kind::EPOCH_OPEN);
            if is_open {
                // A new epoch_open goes ahead of queued entries (fencing information first).
                queue.push_front(*hash);
            } else {
                queue.push_back(*hash);
            }
        }
        Ok(())
    }

    /// Advances timers: stops a live tail that stayed over budget for 5 s (sending `resync`), and
    /// queues a `ping` on an idle link. Returns the next time it needs to be called.
    pub fn tick(&mut self, now_ms: u64) -> u64 {
        let expired: Vec<LogId> = self
            .over_budget_since
            .iter()
            .filter(|(_, since)| now_ms.saturating_sub(**since) >= BACKPRESSURE_RESYNC_MS)
            .map(|(log, _)| *log)
            .collect();
        for log_id in expired {
            self.over_budget_since.remove(&log_id);
            self.live_queue.remove(&log_id);
            self.live.remove(&log_id);
            self.control.push_back(Message::Resync {
                log_id,
                reason: "backpressure".into(),
            });
        }
        if self.peer.is_some() && now_ms.saturating_sub(self.last_sent_ms) >= PING_INTERVAL_MS {
            self.nonce += 1;
            self.control.push_back(Message::Ping {
                nonce: self.nonce,
                heard: Vec::new(),
            });
            self.last_sent_ms = now_ms;
        }
        let ping_at = if self.peer.is_some() {
            self.last_sent_ms + PING_INTERVAL_MS
        } else {
            now_ms + PING_INTERVAL_MS
        };
        self.over_budget_since
            .values()
            .map(|since| since + BACKPRESSURE_RESYNC_MS)
            .fold(ping_at, u64::min)
    }

    /// The next message to send, if any: control frames first, then live tails, then catch-up,
    /// within the credit (`UNACKED_PER_LOG`, `UNACKED_PER_CONN`).
    ///
    /// # Errors
    ///
    /// A store error.
    #[allow(clippy::too_many_lines)]
    pub fn poll_transmit<S: LogStore + ?Sized>(
        &mut self,
        store: &S,
        now_ms: u64,
    ) -> Result<Option<Message>, StoreError> {
        if let Some(message) = self.control.pop_front() {
            if let Message::Want {
                log_id, live: true, ..
            } = &message
            {
                self.subscribed.insert(*log_id);
            }
            self.last_sent_ms = now_ms;
            return Ok(Some(message));
        }
        if self.peer.is_none() || self.closing {
            return Ok(None);
        }
        let logs: Vec<LogId> = self
            .live_queue
            .iter()
            .filter(|(_, q)| !q.is_empty())
            .map(|(l, _)| *l)
            .collect();
        for log_id in logs {
            let budget = self.budget(&log_id);
            if budget == 0 {
                self.over_budget_since.entry(log_id).or_insert(now_ms);
                continue;
            }
            self.over_budget_since.remove(&log_id);
            let mut batch = Vec::new();
            let mut bytes = 0;
            while let Some(hash) = self
                .live_queue
                .get_mut(&log_id)
                .and_then(VecDeque::pop_front)
            {
                let Some(entry) = store.entry(&hash)? else {
                    continue;
                };
                let (item, size) = wire_entry(store, &entry)?;
                if !batch.is_empty() && (bytes + size > budget || batch.len() >= MAX_BATCH_ENTRIES)
                {
                    if let Some(queue) = self.live_queue.get_mut(&log_id) {
                        queue.push_front(hash);
                    }
                    break;
                }
                bytes += size;
                self.record_sent(log_id, entry.epoch, entry.seq, size);
                batch.push(item);
            }
            if !batch.is_empty() {
                self.last_sent_ms = now_ms;
                return Ok(Some(Message::Entries {
                    log_id,
                    entries: batch,
                }));
            }
        }
        for _ in 0..self.cursors.len() {
            let Some(mut cursor) = self.cursors.pop_front() else {
                break;
            };
            let budget = self.budget(&cursor.log_id);
            if budget == 0 {
                self.cursors.push_back(cursor);
                continue;
            }
            let rows = store.segment_entries(
                &cursor.log_id,
                &cursor.epoch,
                cursor.from_seq,
                MAX_BATCH_ENTRIES + cursor.sent.len() + 1,
            )?;
            let mut batch = Vec::new();
            let mut bytes = 0;
            let mut exhausted = true;
            for entry in rows {
                if cursor.sent.contains(&entry.entry_hash) {
                    continue;
                }
                let (item, size) = wire_entry(store, &entry)?;
                if !batch.is_empty() && (bytes + size > budget || batch.len() >= MAX_BATCH_ENTRIES)
                {
                    exhausted = false;
                    break;
                }
                bytes += size;
                if entry.seq > cursor.from_seq {
                    cursor.sent.clear();
                    cursor.from_seq = entry.seq;
                }
                cursor.sent.insert(entry.entry_hash);
                self.record_sent(cursor.log_id, entry.epoch, entry.seq, size);
                batch.push(item);
            }
            if batch.is_empty() {
                continue;
            }
            let log_id = cursor.log_id;
            // Kept until a poll finds nothing more: the next round reads past this batch.
            let _ = exhausted;
            self.cursors.push_back(cursor);
            self.last_sent_ms = now_ms;
            return Ok(Some(Message::Entries {
                log_id,
                entries: batch,
            }));
        }
        Ok(None)
    }

    fn budget(&self, log_id: &LogId) -> usize {
        let log: usize = self
            .unacked
            .get(log_id)
            .map_or(0, |s| s.iter().map(|x| x.bytes).sum());
        let per_log = UNACKED_PER_LOG.saturating_sub(log);
        let per_conn = UNACKED_PER_CONN.saturating_sub(self.unacked_total);
        per_log.min(per_conn).min(MAX_BATCH_BYTES)
    }

    fn record_sent(&mut self, log_id: LogId, epoch: core_proto::Epoch, seq: u64, bytes: usize) {
        self.unacked
            .entry(log_id)
            .or_default()
            .push(Sent { epoch, seq, bytes });
        self.unacked_total += bytes;
    }
}

fn live_want<S: LogStore + ?Sized>(
    store: &S,
    log_id: LogId,
) -> Result<Option<Message>, StoreError> {
    let segment = match store.placement(&log_id)? {
        Some(placement) => store.segment(&log_id, &placement.epoch)?,
        None => store.segments(&log_id)?.pop(),
    };
    Ok(segment.map(|s| Message::Want {
        log_id,
        epoch: s.epoch,
        after: Some(Pos {
            seq: s.head_seq,
            hash: s.head_hash,
        }),
        live: true,
        payloads: true,
    }))
}

fn wire_entry<S: LogStore + ?Sized>(
    store: &S,
    entry: &core_store::StoredEntry,
) -> Result<((Vec<u8>, WirePayload), usize), StoreError> {
    let mut signed = entry.header.clone();
    signed.extend_from_slice(&entry.signature);
    let payload = match entry.payload_state {
        PayloadState::Stored if entry.payload_len <= MAX_INLINE_PAYLOAD as u64 => store
            .blob(&entry.payload_hash)?
            .map_or(WirePayload::ByHash, WirePayload::Inline),
        PayloadState::Pruned => WirePayload::Pruned,
        _ => WirePayload::ByHash,
    };
    let size = SIGNED_LEN
        + 16
        + match &payload {
            WirePayload::Inline(bytes) => bytes.len(),
            _ => 0,
        };
    Ok(((signed, payload), size))
}

/// The hello digest (C1 §4.4), from the store's rows: per log sorted by id,
/// `log_id ‖ hwm.n ‖ hwm.claimant ‖ tip.seq ‖ tip.hash ‖ max_n_seen ‖ divergent_segment_count`.
///
/// # Errors
///
/// A store error.
pub fn digest<S: LogStore + ?Sized>(store: &S) -> Result<[u8; 32], StoreError> {
    let mut input = Vec::new();
    for log_id in store.logs()? {
        let placement = store.placement(&log_id)?;
        let segments = store.segments(&log_id)?;
        let canonical = placement
            .as_ref()
            .and_then(|p| segments.iter().find(|s| s.epoch == p.epoch));
        input.extend_from_slice(log_id.as_bytes());
        match &placement {
            Some(p) => {
                input.extend_from_slice(&p.epoch.n.to_be_bytes());
                input.extend_from_slice(p.epoch.claimant.as_bytes());
            }
            None => input.extend_from_slice(&[0; 40]),
        }
        match canonical {
            Some(s) => {
                input.extend_from_slice(&s.head_seq.to_be_bytes());
                input.extend_from_slice(s.head_hash.as_bytes());
            }
            None => input.extend_from_slice(&[0; 40]),
        }
        input.extend_from_slice(&store.max_n_seen(&log_id)?.unwrap_or(0).to_be_bytes());
        let divergent = segments
            .iter()
            .filter(|s| s.state == SegmentState::Divergent)
            .count();
        input.extend_from_slice(&u32::try_from(divergent).unwrap_or(u32::MAX).to_be_bytes());
        for segment in &segments {
            let (count, xor) = segment_set(store, &log_id, &segment.epoch)?;
            input.extend_from_slice(&crate::hashes::epoch_bytes(&segment.epoch));
            input.extend_from_slice(&count.to_be_bytes());
            input.extend_from_slice(&xor);
        }
    }
    Ok(log_digest(&input))
}

/// Every log as `AgentHave`, from the store's rows.
///
/// # Errors
///
/// A store error.
pub fn agent_haves<S: LogStore + ?Sized>(store: &S) -> Result<Vec<AgentHave>, StoreError> {
    let mut out = Vec::new();
    for log_id in store.logs()? {
        let placement = store.placement(&log_id)?;
        let segments = store.segments(&log_id)?;
        let canonical = placement
            .as_ref()
            .and_then(|p| segments.iter().find(|s| s.epoch == p.epoch));
        out.push(AgentHave {
            log_id,
            agent_id: placement.as_ref().map(|p| p.agent_id.clone()),
            hwm: placement.as_ref().map(|p| p.epoch),
            max_n_seen: store.max_n_seen(&log_id)?.unwrap_or(0),
            tip: canonical.map(|s| Pos {
                seq: s.head_seq,
                hash: s.head_hash,
            }),
            complete_seq: canonical.and_then(|s| s.complete_seq),
            segments: segments
                .iter()
                .map(|s| {
                    let (entries, set_xor) = segment_set(store, &log_id, &s.epoch)?;
                    Ok(SegSummary {
                        epoch: s.epoch,
                        start_seq: s.start_seq,
                        parent_hash: s.parent,
                        head: Pos {
                            seq: s.head_seq,
                            hash: s.head_hash,
                        },
                        state: segment_state_code(s.state),
                        entries,
                        set_xor,
                    })
                })
                .collect::<Result<_, StoreError>>()?,
        });
    }
    Ok(out)
}

/// The number of stored entries of a segment and the XOR of their hashes.
///
/// # Errors
///
/// A store error.
pub fn segment_set<S: LogStore + ?Sized>(
    store: &S,
    log_id: &LogId,
    epoch: &core_proto::Epoch,
) -> Result<(u64, [u8; 32]), StoreError> {
    let mut xor = [0_u8; 32];
    let entries = store.segment_entries(log_id, epoch, 0, usize::MAX)?;
    for entry in &entries {
        for (acc, byte) in xor.iter_mut().zip(entry.entry_hash.as_bytes()) {
            *acc ^= byte;
        }
    }
    Ok((entries.len() as u64, xor))
}

/// The integer code of a segment state (store v1 codes).
#[must_use]
pub const fn segment_state_code(state: SegmentState) -> u64 {
    match state {
        SegmentState::Open => 0,
        SegmentState::Sealed => 1,
        SegmentState::CanonicalAncestor => 2,
        SegmentState::Divergent => 3,
        SegmentState::Invalid => 4,
        SegmentState::Quarantined => 5,
    }
}
