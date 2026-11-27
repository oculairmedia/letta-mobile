#![allow(
    clippy::similar_names,
    reason = "a materializer and what it materialized read best side by side"
)]
//! One actor per agent log: the only writer of the node's entries for that agent.
//!
//! As holder it runs the exporter (letta-code's files into the log, on watcher bursts and on
//! request), appends feed entries, writes checkpoints (E4), and drives the source side of a move.
//! As replica it serves the target side of a move, evaluates automatic failover, indexes the feed
//! journal, and records nothing. On seeing a higher epoch while holding it stops at once (F4):
//! aborts the turn, stops the runtime and the exporter, and reports `fenced` to the new holder,
//! which records the stranded tail as a divergence.

use std::collections::{BTreeMap, VecDeque};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use core_log::payload::{Checkpoint, EpochOpen, Payload, Reason, kind};
use core_log::{AgentLog, Pos, SignedEntry};
use core_placement::failover::{FailoverDecision, FailoverInput, Liveness, PeerFacts, evaluate};
use core_placement::fenced::{FencedAssessment, assess, report_if_fenced};
use core_placement::pipeline::{
    MoveOptions, MoveSource, MoveTarget, Prepare, SourceAction, SourceEvent, SourcePhase,
    TargetAction, TargetEvent, TargetFacts, TargetPhase, check_prepare, check_request,
};
use core_placement::{PlaceMessage, PlacementRecord};
use core_projection::lint::SecretScanner;
use core_projection::materialize::install;
use core_projection::{
    BackendWatcher, Gitoxide, Materialized, Materializer, NodeGitConfig, StoreExporter, diff_lines,
};
use core_proto::{AgentId, ConversationId, Epoch};
use core_store::{BindingKey, EntryHash, FeedRow, LogId, PayloadState, Store};
use tokio::sync::{mpsc, oneshot};

use crate::host::{AgentRuntime, Quiesce};
use crate::node::{Inner, NodeError};

/// Checkpoint after this many entries (E4).
const CHECKPOINT_EVERY_ENTRIES: u64 = 500;
/// Or after this long with activity (E4).
const CHECKPOINT_EVERY_MS: u64 = 600_000;

type Reply<T> = oneshot::Sender<Result<T, NodeError>>;

/// What an actor is asked to do.
pub(crate) enum Command {
    /// Create the log here (genesis) and become holder.
    Create { agent: AgentId, reply: Reply<Pos> },
    /// Export now; reply with the tip.
    Flush(Reply<Pos>),
    /// The backend watcher saw a burst.
    Changed,
    /// Append a feed entry.
    Feed { entry: FeedEntry, reply: Reply<u64> },
    /// Flush and write a checkpoint.
    Checkpoint(Reply<Pos>),
    /// Move the agent to `target`.
    Move {
        target: core_proto::NodeId,
        options: MoveOptions,
        reply: Reply<()>,
    },
    /// Add a subagent whose files ride in this log.
    AddSubagent(AgentId),
    /// The log changed (replication or membership).
    LogChanged,
    /// A `baken/place/1` message.
    Place {
        from: core_proto::NodeId,
        message: PlaceMessage,
    },
    /// Recover after a restart.
    Resume,
    /// Stop holding and end.
    Stop(oneshot::Sender<()>),
}

/// A feed v1 entry to journal.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FeedEntry {
    /// The conversation.
    pub conversation_id: ConversationId,
    /// The turn.
    pub turn: String,
    /// `turn_open`, `stream_delta`, `turn_closed`, `user_message`, ...
    pub kind: String,
    /// The feed v1 entry JSON, verbatim.
    pub entry: Vec<u8>,
    /// A cumulative snapshot this one supersedes.
    pub replaces_feed_seq: Option<u64>,
}

/// The handle of one agent's actor.
#[derive(Clone, Debug)]
pub struct AgentHandle {
    log_id: LogId,
    tx: mpsc::UnboundedSender<Command>,
}

impl AgentHandle {
    /// The agent log.
    #[must_use]
    pub const fn log_id(&self) -> LogId {
        self.log_id
    }

    pub(crate) fn send(&self, command: Command) {
        let _ = self.tx.send(command);
    }

    pub(crate) fn is_closed(&self) -> bool {
        self.tx.is_closed()
    }

    pub(crate) async fn request<T>(
        &self,
        make: impl FnOnce(Reply<T>) -> Command,
    ) -> Result<T, NodeError> {
        let (reply, response) = oneshot::channel();
        self.tx.send(make(reply)).map_err(|_| NodeError::Stopped)?;
        response.await.map_err(|_| NodeError::Stopped)?
    }

    /// Exports letta-code's latest file changes now (the watcher does it on its own too); returns
    /// the canonical tip.
    ///
    /// # Errors
    ///
    /// [`NodeError`]; `NotHolder` when this node does not hold the agent.
    pub async fn flush(&self) -> Result<Pos, NodeError> {
        self.request(Command::Flush).await
    }

    /// Flushes and writes a checkpoint; returns its position.
    ///
    /// # Errors
    ///
    /// [`NodeError`].
    pub async fn checkpoint(&self) -> Result<Pos, NodeError> {
        self.request(Command::Checkpoint).await
    }

    /// Journals a feed entry; returns its feed seq.
    ///
    /// # Errors
    ///
    /// [`NodeError`].
    pub async fn append_feed(&self, entry: FeedEntry) -> Result<u64, NodeError> {
        self.request(|reply| Command::Feed { entry, reply }).await
    }

    /// Moves the agent to `target` (C1 §5.5) and waits until the target opened its epoch.
    ///
    /// # Errors
    ///
    /// [`NodeError::MoveAborted`] or [`NodeError::Refused`] when the move ended before the seal.
    pub async fn move_to(
        &self,
        target: core_proto::NodeId,
        options: MoveOptions,
    ) -> Result<(), NodeError> {
        self.request(|reply| Command::Move {
            target,
            options,
            reply,
        })
        .await
    }

    /// Adds a subagent whose files ride in this agent's log.
    pub fn add_subagent(&self, agent: AgentId) {
        self.send(Command::AddSubagent(agent));
    }
}

/// Starts the actor of `log_id` on the current runtime.
pub(crate) fn spawn<S: Store + 'static>(inner: Arc<Inner<S>>, log_id: LogId) -> AgentHandle {
    let (tx, rx) = mpsc::unbounded_channel();
    let actor = Actor {
        started_ms: inner.clock.mono_ms(),
        inner,
        log_id,
        agent: None,
        tx: tx.clone(),
        holder: None,
        source: None,
        target: None,
        subagents: Vec::new(),
    };
    tokio::spawn(actor.run(rx));
    AgentHandle { log_id, tx }
}

struct Holder {
    exporter: StoreExporter<Gitoxide>,
    epoch: Epoch,
    feed_heads: BTreeMap<ConversationId, u64>,
    since_checkpoint: u64,
    checkpoint_due_ms: Option<u64>,
    _watcher: Option<BackendWatcher>,
}

struct SourceMove {
    machine: MoveSource,
    target: core_proto::NodeId,
    reply: Option<Reply<()>>,
}

struct TargetMove {
    machine: MoveTarget,
    source: core_proto::NodeId,
    complete: Pos,
    staging: PathBuf,
    materialized: Option<Materialized>,
    mismatch: Option<core_projection::Mismatch>,
    commit: Option<Pos>,
}

struct Actor<S: Store + 'static> {
    inner: Arc<Inner<S>>,
    log_id: LogId,
    agent: Option<AgentId>,
    tx: mpsc::UnboundedSender<Command>,
    holder: Option<Holder>,
    source: Option<SourceMove>,
    target: Option<TargetMove>,
    subagents: Vec<AgentId>,
    started_ms: u64,
}

impl<S: Store + 'static> Actor<S> {
    async fn run(mut self, mut rx: mpsc::UnboundedReceiver<Command>) {
        let mut tick = tokio::time::interval(self.inner.config.tick);
        tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            tokio::select! {
                command = rx.recv() => match command {
                    None => break,
                    Some(Command::Stop(done)) => {
                        self.stop_holding().await;
                        let _ = done.send(());
                        break;
                    }
                    Some(command) => self.handle(command).await,
                },
                _ = tick.tick() => self.on_tick().await,
            }
        }
    }

    fn me(&self) -> core_proto::NodeId {
        self.inner.id
    }

    fn now(&self) -> u64 {
        self.inner.clock.mono_ms()
    }

    fn record(&mut self) -> Option<PlacementRecord> {
        let record = self.inner.record(self.log_id).ok().flatten();
        if let Some(record) = &record {
            self.agent.get_or_insert_with(|| record.agent_id.clone());
        }
        record
    }

    async fn handle(&mut self, command: Command) {
        match command {
            Command::Create { agent, reply } => {
                let result = self.create(agent).await;
                let _ = reply.send(result);
            }
            Command::Flush(reply) => {
                let result = self.flush();
                let _ = reply.send(result);
            }
            Command::Changed => {
                let _ = self.flush();
            }
            Command::Feed { entry, reply } => {
                let _ = reply.send(self.append_feed(entry));
            }
            Command::Checkpoint(reply) => {
                let result = self.write_checkpoint();
                let _ = reply.send(result);
            }
            Command::Move {
                target,
                options,
                reply,
            } => self.start_move(target, options, reply).await,
            Command::AddSubagent(agent) => {
                if let Some(holder) = &mut self.holder {
                    holder.exporter.add_subagent(agent.as_str());
                }
                self.subagents.push(agent);
            }
            Command::LogChanged => self.on_log_changed().await,
            Command::Place { from, message } => self.on_place(from, message).await,
            Command::Resume => self.resume().await,
            Command::Stop(_) => {}
        }
    }

    // ----- holding -----

    async fn create(&mut self, agent: AgentId) -> Result<Pos, NodeError> {
        let judge = self.inner.judge();
        let log = AgentLog::new(self.log_id, &judge);
        let open = EpochOpen {
            agent_id: Some(agent.clone()),
            reason: Reason::Genesis,
            prior_epoch: None,
            votes: Vec::new(),
            store_format: core_log::allowlist::STORE_FORMAT,
            runtime_parity: Some(self.inner.parity.clone()),
            prior_head_seen: None,
            uncertain: Vec::new(),
        };
        let key = Arc::clone(&self.inner.key);
        let ts = self.inner.clock.wall_ms();
        let entry = self
            .inner
            .store
            .write(|txn| log.genesis(txn, &key, open, ts))?;
        self.inner.publish(self.log_id, &[entry]);
        self.agent = Some(agent);
        self.become_holder().await
    }

    /// Takes the holder role over the current canonical line: seeds the exporter with it, starts
    /// the runtime and the watcher, exports anything newer and writes the post-open checkpoint.
    async fn become_holder(&mut self) -> Result<Pos, NodeError> {
        let record = self
            .record()
            .ok_or_else(|| NodeError::Refused("no placement".into()))?;
        let agent = record.agent_id.clone();
        let line = self.inner.canonical(self.log_id, None)?;
        let bindings: Vec<String> = self
            .inner
            .store
            .bindings()
            .bindings()?
            .into_iter()
            .map(|(_, value)| value)
            .collect();
        let backend = self.inner.config.backend_dir.clone();
        std::fs::create_dir_all(&backend).map_err(|e| NodeError::Refused(e.to_string()))?;
        let mut exporter = StoreExporter::new(
            &backend,
            Gitoxide,
            agent.as_str(),
            SecretScanner::new(bindings),
        );
        for sub in &self.subagents {
            exporter.add_subagent(sub.as_str());
        }
        exporter.seed(line.iter().map(|(_, p)| p))?;
        let mut feed_heads = BTreeMap::new();
        for (_, payload) in &line {
            if let Payload::FeedAppend {
                conversation_id,
                feed_seq,
                ..
            } = payload
            {
                let head = feed_heads.entry(conversation_id.clone()).or_insert(0);
                *head = (*head).max(*feed_seq);
            }
        }
        let tx = self.tx.clone();
        let watcher =
            BackendWatcher::start(&backend, self.inner.config.watch_debounce, move |_| {
                let _ = tx.send(Command::Changed);
            })
            .ok();
        let hooks_dir = self.inner.config.hooks_dir();
        let _ = std::fs::create_dir_all(&hooks_dir);
        self.inner
            .host
            .start(&AgentRuntime {
                agent_id: agent,
                backend_dir: backend,
                hooks_dir,
                env: BTreeMap::new(),
            })
            .await?;
        self.holder = Some(Holder {
            exporter,
            epoch: record.epoch,
            feed_heads,
            since_checkpoint: 0,
            checkpoint_due_ms: None,
            _watcher: watcher,
        });
        self.write_checkpoint()
    }

    async fn stop_holding(&mut self) {
        if self.holder.take().is_some()
            && let Some(agent) = self.agent.clone()
        {
            let _ = self.inner.host.stop(&agent).await;
        }
    }

    fn append(&mut self, payloads: &[Payload]) -> Result<Vec<SignedEntry>, NodeError> {
        if payloads.is_empty() {
            return Ok(Vec::new());
        }
        let judge = self.inner.judge();
        let log = AgentLog::new(self.log_id, &judge);
        let key = Arc::clone(&self.inner.key);
        let ts = self.inner.clock.wall_ms();
        let entries = self.inner.store.write(|txn| {
            payloads
                .iter()
                .map(|payload| log.append(txn, &key, payload, ts))
                .collect::<Result<Vec<_>, _>>()
        })?;
        self.inner.publish(self.log_id, &entries);
        if let Some(holder) = &mut self.holder {
            holder.since_checkpoint += entries.len() as u64;
            holder
                .checkpoint_due_ms
                .get_or_insert(self.inner.clock.mono_ms() + CHECKPOINT_EVERY_MS);
        }
        Ok(entries)
    }

    fn tip(&mut self) -> Result<Pos, NodeError> {
        self.record()
            .map(|r| r.tip)
            .ok_or_else(|| NodeError::Refused("no placement".into()))
    }

    fn flush(&mut self) -> Result<Pos, NodeError> {
        let Some(holder) = &mut self.holder else {
            return Err(NodeError::Refused("not_holder".into()));
        };
        let batch = holder.exporter.scan()?;
        if let Err(error) = self.append(&batch.payloads) {
            // The exporter counted the batch as exported: rebuild it from the log.
            self.reseed()?;
            return Err(error);
        }
        let due = self.holder.as_ref().is_some_and(|h| {
            h.since_checkpoint >= CHECKPOINT_EVERY_ENTRIES
                || h.checkpoint_due_ms.is_some_and(|t| t <= self.now())
        });
        if due {
            return self.checkpoint_now();
        }
        self.tip()
    }

    fn reseed(&mut self) -> Result<(), NodeError> {
        let line = self.inner.canonical(self.log_id, None)?;
        if let (Some(holder), Some(agent)) = (&mut self.holder, &self.agent) {
            let backend = holder.exporter.root().to_path_buf();
            let scope = holder.exporter.scope().clone();
            let mut exporter =
                StoreExporter::new(&backend, Gitoxide, agent.as_str(), SecretScanner::default());
            for agent in scope {
                exporter.add_subagent(&agent);
            }
            exporter.seed(line.iter().map(|(_, p)| p))?;
            holder.exporter = exporter;
        }
        Ok(())
    }

    fn write_checkpoint(&mut self) -> Result<Pos, NodeError> {
        self.flush()?;
        self.checkpoint_now()
    }

    fn checkpoint_now(&mut self) -> Result<Pos, NodeError> {
        let Some(holder) = &self.holder else {
            return Err(NodeError::Refused("not_holder".into()));
        };
        let heads: Vec<(ConversationId, u64)> = holder
            .feed_heads
            .iter()
            .map(|(c, s)| (c.clone(), *s))
            .collect();
        let checkpoint = holder.exporter.checkpoint(heads, &self.inner.parity);
        let entries = self.append(&[Payload::Checkpoint(checkpoint)])?;
        if let Some(holder) = &mut self.holder {
            holder.since_checkpoint = 0;
            holder.checkpoint_due_ms = None;
        }
        let entry = entries.first().ok_or(NodeError::Stopped)?;
        Ok(Pos {
            seq: entry.header().seq,
            hash: entry.hash(),
        })
    }

    fn append_feed(&mut self, feed: FeedEntry) -> Result<u64, NodeError> {
        if self.holder.is_none() {
            return Err(NodeError::Refused("not_holder".into()));
        }
        let judge = self.inner.judge();
        let log = AgentLog::new(self.log_id, &judge);
        let key = Arc::clone(&self.inner.key);
        let ts = self.inner.clock.wall_ms();
        let (entry, seq) = self.inner.store.write(|txn| {
            let seq = txn.feed_head(&feed.conversation_id)?.map_or(1, |h| h + 1);
            let payload = Payload::FeedAppend {
                conversation_id: feed.conversation_id.clone(),
                feed_seq: seq,
                turn: feed.turn.clone(),
                entry_kind: feed.kind.clone(),
                entry: feed.entry.clone(),
                replaces_feed_seq: feed.replaces_feed_seq,
            };
            let entry = log.append(txn, &key, &payload, ts)?;
            txn.put_feed_row(&FeedRow {
                conversation_id: feed.conversation_id.clone(),
                feed_seq: seq,
                entry_hash: entry.hash(),
            })?;
            Ok::<_, NodeError>((entry, seq))
        })?;
        self.inner
            .publish(self.log_id, std::slice::from_ref(&entry));
        if let Some(holder) = &mut self.holder {
            holder.feed_heads.insert(feed.conversation_id, seq);
            holder.since_checkpoint += 1;
        }
        Ok(seq)
    }

    /// Replicas index the journal from the canonical line (idempotent).
    fn index_feed(&self) -> Result<(), NodeError> {
        let line = self.inner.canonical(self.log_id, None)?;
        let rows: Vec<FeedRow> = line
            .iter()
            .filter_map(|(pos, payload)| match payload {
                Payload::FeedAppend {
                    conversation_id,
                    feed_seq,
                    ..
                } => Some(FeedRow {
                    conversation_id: conversation_id.clone(),
                    feed_seq: *feed_seq,
                    entry_hash: pos.hash,
                }),
                _ => None,
            })
            .collect();
        if rows.is_empty() {
            return Ok(());
        }
        self.inner.store.write(|txn| {
            for row in &rows {
                txn.put_feed_row(row)?;
            }
            Ok::<_, NodeError>(())
        })
    }

    // ----- replication events -----

    async fn on_log_changed(&mut self) {
        let Some(record) = self.record() else {
            return;
        };
        if self.holder.is_none() {
            let _ = self.index_feed();
        }
        // The source's part ends when the target's epoch is canonical.
        if let Some(source) = &self.source
            && source.machine.committed()
            && record.epoch == source.machine.prepare().proposed
        {
            self.drive_source(SourceEvent::TargetOpened).await;
        }
        // F4: a holder that sees a higher epoch stops at once.
        if let Some(holder) = &self.holder
            && !record.may_append(&self.me())
            && !self.source.as_ref().is_some_and(|s| s.machine.committed())
        {
            let held = holder.epoch;
            if self.source.is_some() {
                self.drive_source(SourceEvent::HigherEpochSeen).await;
            }
            if let Some(agent) = self.agent.clone() {
                let _ = self.inner.host.abort_turn(&agent).await;
            }
            self.stop_holding().await;
            self.report_fenced(&record, held);
        }
        self.advance_target(&record).await;
    }

    fn report_fenced(&self, record: &PlacementRecord, held: Epoch) {
        let row = self
            .inner
            .store
            .read(|txn| txn.segment(&self.log_id, &held));
        let Ok(Some(segment)) = row else {
            return;
        };
        let segment_head = Pos {
            seq: segment.head_seq,
            hash: segment.head_hash,
        };
        if let (Some(report), Some(holder)) = (
            report_if_fenced(record, held, segment_head),
            record.holder(),
        ) {
            self.inner.send_place(holder, &PlaceMessage::Fenced(report));
        }
    }

    // ----- place messages -----

    async fn on_place(&mut self, from: core_proto::NodeId, message: PlaceMessage) {
        match message {
            PlaceMessage::MoveRequest { target, .. } => {
                let options = MoveOptions {
                    quiesce_timeout_ms: self.inner.membership.view().policy().quiesce_timeout_ms,
                    stop_turn: false,
                };
                let (reply, _) = oneshot::channel();
                self.start_move(target, options, reply).await;
            }
            PlaceMessage::Prepare {
                log_id,
                from: from_epoch,
                proposed,
                store_format,
                runtime_parity,
            } => {
                let prepare = Prepare {
                    log_id,
                    from: from_epoch,
                    proposed,
                    store_format,
                    runtime_parity,
                };
                self.on_prepare(from, prepare);
            }
            PlaceMessage::Prepared { complete } => {
                self.drive_source(SourceEvent::Prepared { complete }).await;
            }
            PlaceMessage::Refused { reason } => {
                self.drive_source(SourceEvent::Refused(reason)).await;
            }
            PlaceMessage::Verified { checkpoint } => {
                self.drive_source(SourceEvent::Verified(checkpoint)).await;
            }
            PlaceMessage::VerifyFailed { .. } => {
                self.drive_source(SourceEvent::VerifyFailed).await;
            }
            PlaceMessage::Abort { reason } => {
                if self.source.is_some() {
                    self.drive_source(SourceEvent::PeerAborted(reason.clone()))
                        .await;
                }
                if self.target.is_some() {
                    self.drive_target(TargetEvent::PeerAborted(reason)).await;
                }
            }
            PlaceMessage::Commit { seal } => {
                if let (Some(target), Ok(entry)) =
                    (&mut self.target, SignedEntry::from_bytes(&seal))
                {
                    target.commit = Some(Pos {
                        seq: entry.header().seq,
                        hash: entry.hash(),
                    });
                }
                if let Some(record) = self.record() {
                    self.advance_target(&record).await;
                }
            }
            PlaceMessage::Fenced(report) => self.on_fenced(from, report),
            PlaceMessage::ClaimRequest { .. } => {
                // Quorum voting is not served by this node yet.
                self.inner.send_place(
                    from,
                    &PlaceMessage::ClaimRefused {
                        reason: "not_voter".into(),
                    },
                );
            }
            PlaceMessage::ClaimVote { .. } | PlaceMessage::ClaimRefused { .. } => {}
        }
    }

    fn on_fenced(&mut self, from: core_proto::NodeId, report: core_placement::FencedReport) {
        if self.holder.is_none() {
            return;
        }
        let judge = self.inner.judge();
        let log = AgentLog::new(self.log_id, &judge);
        let assessed = self.inner.store.read(|txn| {
            let analysis = log.analyze(txn)?;
            Ok::<_, NodeError>(assess(&report, &from, &analysis, txn)?)
        });
        let Ok(FencedAssessment::Divergent { to_record, .. }) = assessed else {
            return;
        };
        for divergence in to_record {
            let Ok(canonical) = self
                .inner
                .canonical(self.log_id, Some(divergence.inputs.fork.seq))
            else {
                continue;
            };
            let canonical: Vec<Payload> = canonical.into_iter().map(|(_, p)| p).collect();
            let tail = self.tail_payloads(&divergence.tail);
            let projection = diff_lines(&divergence.inputs, &canonical, &tail, false);
            let key = Arc::clone(&self.inner.key);
            let ts = self.inner.clock.wall_ms();
            if let Ok(entry) = self
                .inner
                .store
                .write(|txn| log.record_divergence(txn, &key, &divergence, &projection, ts))
            {
                self.inner.publish(self.log_id, &[entry]);
            }
        }
    }

    fn tail_payloads(&self, tail: &[EntryHash]) -> Vec<(Pos, Payload)> {
        self.inner
            .store
            .read(|txn| {
                let mut out = Vec::new();
                for hash in tail {
                    let Some(entry) = txn.entry(hash)? else {
                        continue;
                    };
                    if entry.payload_state != PayloadState::Stored {
                        continue;
                    }
                    if let Some(bytes) = txn.blob(&entry.payload_hash)?
                        && let Ok(payload) = Payload::decode(entry.kind, &bytes)
                    {
                        out.push((
                            Pos {
                                seq: entry.seq,
                                hash: entry.entry_hash,
                            },
                            payload,
                        ));
                    }
                }
                Ok::<_, core_store::StoreError>(out)
            })
            .unwrap_or_default()
    }

    // ----- the move, source side -----

    async fn start_move(
        &mut self,
        target: core_proto::NodeId,
        options: MoveOptions,
        reply: Reply<()>,
    ) {
        if self.source.is_some() || self.holder.is_none() {
            let _ = reply.send(Err(NodeError::Refused("not_holder_or_busy".into())));
            return;
        }
        let Some(record) = self.record() else {
            let _ = reply.send(Err(NodeError::Refused("no placement".into())));
            return;
        };
        let view = self.inner.membership.view();
        if let Err(refusal) = check_request(&record, &view, &self.me(), &target) {
            let _ = reply.send(Err(NodeError::Refused(format!("{refusal:?}"))));
            return;
        }
        let (machine, actions) = MoveSource::start(
            &record,
            target,
            core_log::allowlist::STORE_FORMAT,
            self.inner.parity.clone(),
            options,
            self.now(),
        );
        self.inner.note_move(target, self.log_id);
        self.source = Some(SourceMove {
            machine,
            target,
            reply: Some(reply),
        });
        let mut events = VecDeque::new();
        for action in actions {
            if let Some(event) = self.perform_source(action).await {
                events.push_back(event);
            }
        }
        while let Some(event) = events.pop_front() {
            self.drive_source(event).await;
        }
    }

    async fn drive_source(&mut self, event: SourceEvent) {
        let mut events = VecDeque::from([event]);
        while let Some(event) = events.pop_front() {
            let now = self.now();
            let Some(source) = &mut self.source else {
                return;
            };
            let actions = source.machine.handle(&event, now);
            for action in actions {
                if let Some(next) = self.perform_source(action).await {
                    events.push_back(next);
                }
            }
            self.finish_source_if_done().await;
        }
    }

    async fn finish_source_if_done(&mut self) {
        let Some(source) = &self.source else {
            return;
        };
        let outcome = match source.machine.phase() {
            SourcePhase::Done => Ok(()),
            SourcePhase::Aborted(reason) => Err(NodeError::MoveAborted(reason.code().to_owned())),
            _ => return,
        };
        let Some(mut source) = self.source.take() else {
            return;
        };
        self.inner.end_move(&source.target);
        if outcome.is_ok() {
            self.stop_holding().await;
        }
        if let Some(reply) = source.reply.take() {
            let _ = reply.send(outcome);
        }
    }

    async fn perform_source(&mut self, action: SourceAction) -> Option<SourceEvent> {
        let target = self.source.as_ref()?.target;
        match action {
            SourceAction::SendPrepare(prepare) => {
                self.inner.send_place(
                    target,
                    &PlaceMessage::Prepare {
                        log_id: prepare.log_id,
                        from: prepare.from,
                        proposed: prepare.proposed,
                        store_format: prepare.store_format,
                        runtime_parity: prepare.runtime_parity,
                    },
                );
                None
            }
            SourceAction::GateMoving => {
                // New input is refused from here (the actor serializes it); wait for the turn.
                let agent = self.agent.clone()?;
                let timeout =
                    Duration::from_millis(self.inner.membership.view().policy().quiesce_timeout_ms);
                match self.inner.host.quiesce(&agent, timeout).await {
                    Ok(Quiesce::Idle) => {
                        let _ = self.inner.host.checkpoint(&agent).await;
                        Some(SourceEvent::TurnIdle)
                    }
                    _ => Some(SourceEvent::Tick),
                }
            }
            SourceAction::GateReopen | SourceAction::TopUp => None,
            SourceAction::StopTurn => {
                let agent = self.agent.clone()?;
                let _ = self.inner.host.abort_turn(&agent).await;
                let _ = self.inner.host.checkpoint(&agent).await;
                Some(SourceEvent::TurnIdle)
            }
            SourceAction::WriteCheckpoint => match self.write_checkpoint() {
                Ok(pos) => Some(SourceEvent::CheckpointWritten(pos)),
                Err(error) => Some(SourceEvent::PeerAborted(error.to_string())),
            },
            SourceAction::WriteSeal {
                next_epoch,
                checkpoint,
            } => {
                let judge = self.inner.judge();
                let log = AgentLog::new(self.log_id, &judge);
                let key = Arc::clone(&self.inner.key);
                let ts = self.inner.clock.wall_ms();
                let sealed = self
                    .inner
                    .store
                    .write(|txn| log.seal(txn, &key, Some(next_epoch), checkpoint, ts));
                match sealed {
                    Ok(entry) => {
                        self.inner
                            .publish(self.log_id, std::slice::from_ref(&entry));
                        Some(SourceEvent::SealWritten(Pos {
                            seq: entry.header().seq,
                            hash: entry.hash(),
                        }))
                    }
                    Err(error) => Some(SourceEvent::PeerAborted(error.to_string())),
                }
            }
            SourceAction::SendCommit { seal } => {
                let stored = self.inner.store.read(|txn| txn.entry(&seal.hash)).ok()??;
                let entry = SignedEntry::from_stored(&stored).ok()?;
                self.inner.send_place(
                    target,
                    &PlaceMessage::Commit {
                        seal: entry.to_bytes(),
                    },
                );
                None
            }
            SourceAction::SendAbort(reason) => {
                self.inner.send_place(
                    target,
                    &PlaceMessage::Abort {
                        reason: reason.code().to_owned(),
                    },
                );
                None
            }
        }
    }

    // ----- the move, target side -----

    fn on_prepare(&mut self, from: core_proto::NodeId, prepare: Prepare) {
        let facts = self.target_facts();
        let view = self.inner.membership.view();
        match check_prepare(&prepare, &view, &self.me(), &facts) {
            Ok(complete) => {
                let staging = self
                    .inner
                    .config
                    .data_dir
                    .join("staging")
                    .join(hex::encode(self.log_id.as_bytes()));
                self.inner.note_move(from, self.log_id);
                self.target = Some(TargetMove {
                    machine: MoveTarget::new(prepare, self.now()),
                    source: from,
                    complete,
                    staging,
                    materialized: None,
                    mismatch: None,
                    commit: None,
                });
                self.inner
                    .send_place(from, &PlaceMessage::Prepared { complete });
            }
            Err(refusal) => {
                self.inner.send_place(
                    from,
                    &PlaceMessage::Refused {
                        reason: refusal.code().to_owned(),
                    },
                );
            }
        }
    }

    fn target_facts(&mut self) -> TargetFacts {
        let replica = self.record().map(|r| r.tip);
        let line = self.inner.canonical(self.log_id, None).unwrap_or_default();
        let mut fold = core_log::fold::StateFold::new();
        let mut latest: Option<Checkpoint> = None;
        for (_, payload) in &line {
            let _ = fold.apply(payload);
            if let Payload::Checkpoint(checkpoint) = payload {
                latest = Some(checkpoint.clone());
            }
        }
        let mut profiles = std::collections::BTreeSet::new();
        for entry in fold.manifest() {
            let is_record =
                entry.path.starts_with("agents/") || entry.path.ends_with("/conversation.json");
            if !is_record {
                continue;
            }
            if let Some(model) = fold
                .file(&entry.path)
                .and_then(|b| serde_json::from_slice::<serde_json::Value>(b).ok())
                .and_then(|v| v.get("model").and_then(|m| m.as_str()).map(str::to_owned))
                && model.starts_with("profile/")
            {
                profiles.insert(model);
            }
        }
        let bindings = self.inner.store.bindings();
        let missing_profiles = profiles
            .into_iter()
            .filter(|p| {
                bindings
                    .binding(&BindingKey::ModelProfile(p.clone()))
                    .ok()
                    .flatten()
                    .is_none()
            })
            .collect();
        TargetFacts {
            store_format: core_log::allowlist::STORE_FORMAT,
            runtime_parity: self.inner.parity.clone(),
            missing_profiles,
            h1_violations: latest.map(|c| c.h1_violations).unwrap_or_default(),
            replica,
        }
    }

    /// Feeds the target machine what the log now shows: the source's checkpoint arriving, or the
    /// seal a `commit` named being stored with its payload.
    async fn advance_target(&mut self, record: &PlacementRecord) {
        let Some(target) = &self.target else {
            return;
        };
        let event = match target.machine.phase() {
            TargetPhase::AwaitingCheckpoint { .. } => {
                let tip_is_checkpoint = self
                    .inner
                    .store
                    .read(|txn| txn.entry(&record.tip.hash))
                    .ok()
                    .flatten()
                    .is_some_and(|e| {
                        e.kind == kind::CHECKPOINT
                            && e.payload_state == PayloadState::Stored
                            && e.epoch == record.epoch
                    });
                (tip_is_checkpoint && record.tip.seq > target.complete.seq)
                    .then_some(TargetEvent::CheckpointComplete(record.tip))
            }
            // The seal must be stored with its payload before the open can name it as parent.
            TargetPhase::AwaitingCommit { .. } => target
                .commit
                .filter(|seal| {
                    self.inner
                        .store
                        .read(|txn| txn.entry(&seal.hash))
                        .ok()
                        .flatten()
                        .is_some_and(|e| e.payload_state == PayloadState::Stored)
                })
                .map(|seal| TargetEvent::Commit { seal }),
            _ => None,
        };
        if let Some(event) = event {
            self.drive_target(event).await;
        }
    }

    async fn drive_target(&mut self, event: TargetEvent) {
        let mut events = VecDeque::from([event]);
        while let Some(event) = events.pop_front() {
            let now = self.now();
            let Some(target) = &mut self.target else {
                return;
            };
            let actions = target.machine.handle(&event, now);
            for action in actions {
                if let Some(next) = self.perform_target(action).await {
                    events.push_back(next);
                }
            }
        }
        if let Some(target) = &self.target
            && matches!(
                target.machine.phase(),
                TargetPhase::Opened | TargetPhase::Aborted(_)
            )
        {
            let source = target.source;
            self.target = None;
            self.inner.end_move(&source);
        }
        // A commit that arrived before verify finished is acted on now.
        if self.target.is_some()
            && let Some(record) = self.record()
        {
            Box::pin(self.advance_target(&record)).await;
        }
    }

    async fn perform_target(&mut self, action: TargetAction) -> Option<TargetEvent> {
        let source = self.target.as_ref()?.source;
        match action {
            TargetAction::Materialize { checkpoint } => {
                let matches = self.materialize_staging(checkpoint).unwrap_or(false);
                Some(TargetEvent::Materialized { matches })
            }
            TargetAction::SendVerified(checkpoint) => {
                self.inner
                    .send_place(source, &PlaceMessage::Verified { checkpoint });
                None
            }
            TargetAction::SendVerifyFailed => {
                let mismatch = self
                    .target
                    .as_ref()
                    .and_then(|t| t.mismatch.clone())
                    .unwrap_or_default();
                self.inner.send_place(
                    source,
                    &PlaceMessage::VerifyFailed {
                        diffs: mismatch.diffs,
                        refs_mismatch: mismatch.refs_mismatch,
                    },
                );
                None
            }
            TargetAction::WriteOpen { prior_epoch, seal } => {
                let open = EpochOpen {
                    agent_id: None,
                    reason: Reason::Move,
                    prior_epoch: Some(prior_epoch),
                    votes: Vec::new(),
                    store_format: core_log::allowlist::STORE_FORMAT,
                    runtime_parity: Some(self.inner.parity.clone()),
                    prior_head_seen: None,
                    uncertain: Vec::new(),
                };
                let judge = self.inner.judge();
                let log = AgentLog::new(self.log_id, &judge);
                let key = Arc::clone(&self.inner.key);
                let ts = self.inner.clock.wall_ms();
                match self
                    .inner
                    .store
                    .write(|txn| log.claim(txn, &key, &open, seal, ts))
                {
                    Ok((entry, _)) => {
                        self.inner.publish(self.log_id, &[entry]);
                        Some(TargetEvent::OpenWritten)
                    }
                    Err(error) => Some(TargetEvent::PeerAborted(error.to_string())),
                }
            }
            TargetAction::TakeOver => {
                let (staging, materialized) = {
                    let target = self.target.as_mut()?;
                    (target.staging.clone(), target.materialized.take()?)
                };
                if install(&staging, &self.inner.config.backend_dir, &materialized).is_ok() {
                    let _ = std::fs::remove_dir_all(&staging);
                    let _ = self.become_holder().await;
                }
                None
            }
            TargetAction::DiscardStaging => {
                if let Some(target) = &self.target {
                    let _ = std::fs::remove_dir_all(&target.staging);
                }
                None
            }
            TargetAction::SendAbort(reason) => {
                self.inner.send_place(
                    source,
                    &PlaceMessage::Abort {
                        reason: reason.code().to_owned(),
                    },
                );
                None
            }
        }
    }

    /// Materializes the canonical line up to `checkpoint` into staging and compares it with the
    /// checkpoint's manifest and refs.
    fn materialize_staging(&mut self, checkpoint: Pos) -> Result<bool, NodeError> {
        let line = self.inner.canonical(self.log_id, Some(checkpoint.seq))?;
        let expected = match line.last() {
            Some((pos, Payload::Checkpoint(cp))) if *pos == checkpoint => cp.clone(),
            _ => return Ok(false),
        };
        let staging = self
            .target
            .as_ref()
            .ok_or(NodeError::Stopped)?
            .staging
            .clone();
        if staging.exists() {
            std::fs::remove_dir_all(&staging).map_err(|e| NodeError::Refused(e.to_string()))?;
        }
        let materializer = Materializer::new(
            Gitoxide,
            NodeGitConfig {
                hooks_path: Some(self.inner.config.hooks_dir()),
            },
        );
        let materialized = materializer.materialize(line.iter().map(|(_, p)| p), &staging)?;
        let verdict = materialized.verify(&expected);
        let target = self.target.as_mut().ok_or(NodeError::Stopped)?;
        target.materialized = Some(materialized);
        match verdict {
            Ok(()) => Ok(true),
            Err(mismatch) => {
                target.mismatch = Some(mismatch);
                Ok(false)
            }
        }
    }

    // ----- ticks: deadlines, failover, periodic checkpoints -----

    async fn on_tick(&mut self) {
        if self.source.is_some() {
            self.drive_source(SourceEvent::Tick).await;
        }
        if self.target.is_some() {
            self.drive_target(TargetEvent::Tick).await;
        }
        if let Some(holder) = &self.holder
            && holder.checkpoint_due_ms.is_some_and(|t| t <= self.now())
        {
            let _ = self.write_checkpoint();
        }
        if self.holder.is_none() && self.target.is_none() {
            self.consider_failover().await;
        }
    }

    async fn consider_failover(&mut self) {
        let Some(record) = self.record() else {
            return;
        };
        let watched = match record.state {
            core_store::PlacementState::Active => record.epoch.claimant,
            core_store::PlacementState::SealedTo(next) => next.claimant,
            _ => return,
        };
        let view = self.inner.membership.view();
        let mut facts = PeerFacts {
            classes: self.inner.config.peer_classes.clone(),
            complete_seq: BTreeMap::new(),
        };
        facts.classes.insert(self.me(), self.inner.config.class);
        let line = self.inner.canonical(self.log_id, None).unwrap_or_default();
        let parity_matches = line
            .iter()
            .rev()
            .find_map(|(_, p)| match p {
                Payload::Checkpoint(c) => Some(c.runtime_parity == self.inner.parity),
                _ => None,
            })
            .unwrap_or(true);
        let input = FailoverInput {
            view: &view,
            placement: &record,
            me: self.me(),
            facts: &facts,
            liveness: Liveness {
                last_heard_ms: self.inner.last_heard(&watched).unwrap_or(self.started_ms),
                last_dial_failure_ms: self.inner.last_dial_failure(&watched),
                peer_heard_ms: None,
            },
            runtime_parity_matches: parity_matches,
            now_ms: self.now(),
        };
        if let FailoverDecision::Claim { .. } = evaluate(&input) {
            let _ = self.claim_failover(&record).await;
        }
    }

    /// Claims by failover over the canonical tip, then materializes and takes over.
    async fn claim_failover(&mut self, record: &PlacementRecord) -> Result<(), NodeError> {
        let open = EpochOpen {
            agent_id: None,
            reason: Reason::Failover,
            prior_epoch: Some(record.epoch),
            votes: Vec::new(),
            store_format: core_log::allowlist::STORE_FORMAT,
            runtime_parity: Some(self.inner.parity.clone()),
            prior_head_seen: Some(record.tip),
            uncertain: Vec::new(),
        };
        let judge = self.inner.judge();
        let log = AgentLog::new(self.log_id, &judge);
        let key = Arc::clone(&self.inner.key);
        let ts = self.inner.clock.wall_ms();
        let (entry, _) = self
            .inner
            .store
            .write(|txn| log.claim(txn, &key, &open, record.tip, ts))?;
        self.inner.publish(self.log_id, &[entry]);
        let staging = self
            .inner
            .config
            .data_dir
            .join("staging")
            .join(hex::encode(self.log_id.as_bytes()));
        if staging.exists() {
            std::fs::remove_dir_all(&staging).map_err(|e| NodeError::Refused(e.to_string()))?;
        }
        let line = self.inner.canonical(self.log_id, None)?;
        let materializer = Materializer::new(
            Gitoxide,
            NodeGitConfig {
                hooks_path: Some(self.inner.config.hooks_dir()),
            },
        );
        let materialized = materializer.materialize(line.iter().map(|(_, p)| p), &staging)?;
        install(&staging, &self.inner.config.backend_dir, &materialized)?;
        let _ = std::fs::remove_dir_all(&staging);
        self.become_holder().await.map(|_| ())
    }

    /// After a restart: a holder of record resumes holding (its local store is the materialized
    /// one); everything else waits for replication.
    async fn resume(&mut self) {
        let Some(record) = self.record() else {
            return;
        };
        if record.may_append(&self.me()) {
            let _ = self.become_holder().await;
        }
    }
}
