//! Each node's agent actor: what a node does for every agent it replicates, built from the pure
//! state machines of core-log and core-placement.
//!
//! - **Holder:** turns and tool calls append through [`AgentLog::append`] after the F4 gate, the
//!   startup probe, the move gate and the quorum self-fence; divergences are recorded as
//!   `divergence` entries.
//! - **Move:** [`MoveSource`] and [`MoveTarget`] driven by `baken/place/1` messages; the checkpoint
//!   is folded with [`StateFold`] and verified by the target the same way. Crashes can be injected
//!   at the nine points of [`MovePoint`].
//! - **Failover:** the evaluator of core-placement each tick, the probe dial, claims with or
//!   without a quorum (votes cast with persisted promises), and restart recovery.
//!
//! Turns are atomic here (a turn appends all its entries in one transaction), so a move's quiesce
//! completes at once.

use std::collections::{BTreeMap, VecDeque};
use std::rc::Rc;

use core_log::driver::Notice;
use core_log::fold::StateFold;
use core_log::hashes::log_id;
use core_log::payload::{Action, ActionState, Checkpoint, kind};
use core_log::{
    AgentLog, DivergenceProjection, EpochOpen, LogError, Payload, Pos, Reason, SignedEntry,
    TouchedConversation,
};
use core_membership::policy::FailoverPolicy;
use core_membership::{MemberStatus, MembershipView};
use core_placement::failover::{
    self, FailoverDecision, FailoverInput, Liveness, PeerFacts, StartupProbe,
};
use core_placement::pipeline::{
    MoveOptions, Prepare, SourceAction, SourceEvent, SourcePhase, TargetAction, TargetEvent,
    TargetFacts, TargetPhase, check_prepare, check_request, recover_target,
};
use core_placement::voter::{ClaimRequest, VoterView, cast_vote};
use core_placement::{MoveSource, MoveTarget, PlaceMessage, PlacementRecord, Tally};
use core_proto::{AgentId, ConversationId, Epoch, NodeId};
use core_store::{EntryHash, LogId, PayloadState, PlacementState, Store, StoredEntry};

use crate::model::{PARITY, PROBE_INTERVAL_MS, STARTUP_PROBE_MS, STORE_FORMAT, TICK_MS};
use crate::world::{AgentState, MovePoint, Node, PendingClaim, World, crash, probe, send_place};

/// Why a node refused a turn (the input gate, C1 §5.6).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Refusal {
    /// The node is down.
    Down,
    /// The node has no valid log for the agent.
    NoLog,
    /// The F4 gate fails: the node is not the holder (`not_holder`, `placeless`,
    /// `superseded_unknown`).
    Gate(&'static str),
    /// The holder is moving the agent.
    Moving,
    /// The holder restarted and its startup probe has not finished.
    StartupProbe,
    /// The holder has not heard a majority of voters for `self_fence_ms`.
    SelfFenced,
    /// The log refused the append.
    Log(String),
}

/// Why a move could not start.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum MoveRefusal {
    /// The node is down or holds no log.
    NoLog,
    /// core-placement's request check refused it.
    Request(String),
    /// A move or claim is already running.
    Busy,
}

fn read<T>(node: &Node, f: impl FnOnce(&dyn core_store::Txn) -> Result<T, LogError>) -> Option<T> {
    node.store().read(f).ok()
}

/// The placement record of `log` at `node`. Cached on the segment rows, `max_n_seen` and the
/// membership view, which are everything it derives from.
pub(crate) fn record(node: &Node, log: LogId) -> Option<PlacementRecord> {
    let judge = Rc::clone(&node.judge);
    let (segments, max_n) = read(node, |txn| Ok((txn.segments(&log)?, txn.max_n_seen(&log)?)))?;
    let key = (segments, max_n, judge.generation());
    if let Some((cached, record)) = node.record_cache.borrow().get(&log)
        && *cached == key
    {
        return record.clone();
    }
    let record = read(node, |txn| {
        PlacementRecord::derive(&AgentLog::new(log, &*judge), txn)
    })
    .flatten();
    node.record_cache
        .borrow_mut()
        .insert(log, (key, record.clone()));
    record
}

/// The canonical line of `log` at `node`.
pub(crate) fn line(node: &Node, log: LogId) -> Vec<StoredEntry> {
    let judge = Rc::clone(&node.judge);
    read(node, |txn| AgentLog::new(log, &*judge).canonical_line(txn)).unwrap_or_default()
}

/// The decoded payload of a stored entry, when held.
pub(crate) fn payload(node: &Node, entry: &StoredEntry) -> Option<Payload> {
    if entry.payload_state != PayloadState::Stored {
        return None;
    }
    let bytes = read(node, |txn| Ok(txn.blob(&entry.payload_hash)?)).flatten()?;
    Payload::decode(entry.kind, &bytes).ok()
}

fn publish(node: &Node, log: LogId, entries: Vec<EntryHash>) {
    node.hub().publish(
        None,
        &Notice {
            log_id: log,
            entries,
        },
    );
}

fn append(world: &World, node: &Node, log: LogId, payloads: &[Payload]) -> Result<Pos, LogError> {
    let judge = Rc::clone(&node.judge);
    let now = world.now_ms();
    let entries = node.store().write(|txn| {
        let agent_log = AgentLog::new(log, &*judge);
        payloads
            .iter()
            .map(|p| agent_log.append(txn, &node.key, p, now))
            .collect::<Result<Vec<SignedEntry>, LogError>>()
    })?;
    let last = entries.last().map_or(
        Pos {
            seq: 0,
            hash: EntryHash::ZERO,
        },
        |e| Pos {
            seq: e.header().seq,
            hash: e.hash(),
        },
    );
    publish(node, log, entries.iter().map(SignedEntry::hash).collect());
    Ok(last)
}

fn view(node: &Node) -> Option<MembershipView> {
    node.judge.view()
}

fn crash_point(world: &World, name: u8, point: MovePoint) -> bool {
    let armed = world.node(name).st.borrow().crash_at == Some(point);
    if armed {
        world
            .node(name)
            .st
            .borrow_mut()
            .move_log
            .push(format!("crash at {point:?}"));
        crash(world, name);
    }
    armed
}

fn note(world: &World, name: u8, text: String) {
    world.node(name).st.borrow_mut().move_log.push(text);
}

// ----- holder operations -----

/// Creates `agent`'s log at node `name` (the genesis `epoch_open`).
pub(crate) fn create_agent(world: &World, name: u8, agent: &AgentId) -> Result<(), LogError> {
    let node = world.node(name);
    let judge = Rc::clone(&node.judge);
    let now = world.now_ms();
    let open = EpochOpen {
        agent_id: Some(agent.clone()),
        reason: Reason::Genesis,
        prior_epoch: None,
        votes: Vec::new(),
        store_format: STORE_FORMAT,
        runtime_parity: Some(PARITY.into()),
        prior_head_seen: None,
        uncertain: Vec::new(),
    };
    let entry = node
        .store()
        .write(|txn| AgentLog::for_agent(agent, &*judge).genesis(txn, &node.key, open, now))?;
    publish(node, log_id(agent), vec![entry.hash()]);
    Ok(())
}

fn gate(node: &Node, log: LogId) -> Result<(), Refusal> {
    let st = node.st.borrow();
    if !st.up {
        return Err(Refusal::Down);
    }
    drop(st);
    let record = record(node, log).ok_or(Refusal::NoLog)?;
    let me = node.id;
    let g = record.gate(&me, false);
    if !g.accepts() {
        return Err(Refusal::Gate(g.name()));
    }
    let st = node.st.borrow();
    if !st.resumed {
        return Err(Refusal::StartupProbe);
    }
    let agent = st.agents.get(&log);
    if agent.is_some_and(|a| a.moving) {
        return Err(Refusal::Moving);
    }
    if agent.is_some_and(|a| a.self_fenced) {
        return Err(Refusal::SelfFenced);
    }
    Ok(())
}

/// Folds the canonical line (skipping entries whose payload is not held, which prune keeps
/// equivalent).
pub(crate) fn fold_line(node: &Node, line: &[StoredEntry]) -> (StateFold, BTreeMap<String, u64>) {
    let mut fold = StateFold::new();
    let mut feed_heads = BTreeMap::new();
    for entry in line {
        if let Some(p) = payload(node, entry) {
            if let Payload::FeedAppend {
                conversation_id,
                feed_seq,
                ..
            } = &p
            {
                let head = feed_heads
                    .entry(conversation_id.as_str().to_owned())
                    .or_insert(0);
                *head = (*head).max(*feed_seq);
            }
            let _ = fold.apply(&p);
        }
    }
    (fold, feed_heads)
}

/// Runs a turn of `n` messages in conversation `conv` at node `name`: per message a `file_append`
/// to the conversation's `messages.jsonl` and a `feed_append`, the last one closing the turn.
pub(crate) fn turn(
    world: &World,
    name: u8,
    agent: &AgentId,
    conv: &str,
    n: usize,
) -> Result<Pos, Refusal> {
    let node = world.node(name);
    let log = log_id(agent);
    gate(node, log)?;
    let line = line(node, log);
    let (fold, heads) = fold_line(node, &line);
    let path = format!("conversations/{conv}/messages.jsonl");
    let mut offset = fold.file(&path).map_or(0, |f| f.len() as u64);
    let feed_head = heads.get(conv).copied().unwrap_or(0);
    let now = world.now_ms();
    let turn_id = format!("turn-{name}-{now}-{}", line.len());
    let mut payloads = Vec::with_capacity(n * 2);
    for i in 0..n {
        let text = format!(
            "{{\"node\":{name},\"t\":{now},\"seq\":{},\"i\":{i}}}\n",
            line.len()
        );
        payloads.push(Payload::FileAppend {
            path: path.clone(),
            offset,
            bytes: text.clone().into_bytes(),
        });
        offset += text.len() as u64;
        payloads.push(Payload::FeedAppend {
            conversation_id: ConversationId::new(conv),
            feed_seq: feed_head + i as u64 + 1,
            turn: turn_id.clone(),
            entry_kind: if i + 1 == n {
                core_log::prune::TURN_CLOSED.into()
            } else {
                "stream_delta".into()
            },
            entry: text.into_bytes(),
            replaces_feed_seq: None,
        });
    }
    append(world, node, log, &payloads).map_err(|e| Refusal::Log(e.to_string()))
}

/// Appends arbitrary payloads as holder (file puts, tool-call statuses), through the same gate.
pub(crate) fn write(
    world: &World,
    name: u8,
    agent: &AgentId,
    payloads: &[Payload],
) -> Result<Pos, Refusal> {
    let node = world.node(name);
    let log = log_id(agent);
    gate(node, log)?;
    append(world, node, log, payloads).map_err(|e| Refusal::Log(e.to_string()))
}

/// Actions started on `line` and never finished there (C1 §5.9 uncertain actions).
pub(crate) fn uncertain(node: &Node, line: &[StoredEntry]) -> Vec<Action> {
    let mut open: BTreeMap<String, Action> = BTreeMap::new();
    for entry in line.iter().filter(|e| e.kind == kind::ACTION_STATUS) {
        if let Some(Payload::ActionStatus {
            idempotency_key,
            tool_call_id,
            conversation_id,
            state,
        }) = payload(node, entry)
        {
            if state == ActionState::Started {
                open.insert(
                    idempotency_key.clone(),
                    Action {
                        idempotency_key,
                        tool_call_id,
                        conversation_id,
                        started_at: Pos {
                            seq: entry.seq,
                            hash: entry.entry_hash,
                        },
                    },
                );
            } else {
                open.remove(&idempotency_key);
            }
        }
    }
    open.into_values().collect()
}

fn claim(
    world: &World,
    name: u8,
    log: LogId,
    open: &EpochOpen,
    parent: Pos,
) -> Result<SignedEntry, LogError> {
    let node = world.node(name);
    let judge = Rc::clone(&node.judge);
    let now = world.now_ms();
    let (entry, _) = node
        .store()
        .write(|txn| AgentLog::new(log, &*judge).claim(txn, &node.key, open, parent, now))?;
    publish(node, log, vec![entry.hash()]);
    node.st.borrow_mut().dirty.insert(log);
    note(
        world,
        name,
        format!(
            "claimed {:?} n={} at {now}",
            open.reason,
            entry.header().epoch.n
        ),
    );
    Ok(entry)
}

fn takeover_open(
    node: &Node,
    log: LogId,
    record: &PlacementRecord,
    reason: Reason,
    votes: Vec<core_proto::bcbor::Value>,
) -> EpochOpen {
    let line = line(node, log);
    EpochOpen {
        agent_id: None,
        reason,
        prior_epoch: Some(record.epoch),
        votes,
        store_format: STORE_FORMAT,
        runtime_parity: Some(PARITY.into()),
        prior_head_seen: Some(record.tip),
        uncertain: uncertain(node, &line),
    }
}

/// A manual claim (`takeover` or `takeover_forced`) at node `name`.
pub(crate) fn manual_claim(
    world: &World,
    name: u8,
    agent: &AgentId,
    reason: Reason,
) -> Result<(), String> {
    let node = world.node(name);
    let log = log_id(agent);
    let record = record(node, log).ok_or("no log")?;
    let policy = view(node).ok_or("no membership")?.policy().clone();
    if reason == Reason::Takeover && policy.quorum.is_some() {
        start_quorum_claim(world, name, log, &record, reason, &policy);
        return Ok(());
    }
    let open = takeover_open(node, log, &record, reason, Vec::new());
    claim(world, name, log, &open, record.tip)
        .map(|_| ())
        .map_err(|e| e.to_string())
}

// ----- moves -----

/// Starts moving `agent` from node `name` (the holder) to `target`.
pub(crate) fn start_move(
    world: &World,
    name: u8,
    agent: &AgentId,
    target: NodeId,
) -> Result<(), MoveRefusal> {
    let node = world.node(name);
    let log = log_id(agent);
    if !node.is_up() {
        return Err(MoveRefusal::NoLog);
    }
    let record = record(node, log).ok_or(MoveRefusal::NoLog)?;
    let view = view(node).ok_or(MoveRefusal::NoLog)?;
    check_request(&record, &view, &node.id, &target)
        .map_err(|r| MoveRefusal::Request(format!("{r:?}")))?;
    {
        let st = node.st.borrow();
        if st
            .agents
            .get(&log)
            .is_some_and(|a| a.source.is_some() || a.claim.is_some())
        {
            return Err(MoveRefusal::Busy);
        }
    }
    let now = world.now_ms();
    let (source, actions) = MoveSource::start(
        &record,
        target,
        STORE_FORMAT,
        PARITY.into(),
        MoveOptions {
            quiesce_timeout_ms: view.policy().quiesce_timeout_ms,
            stop_turn: false,
        },
        now,
    );
    note(world, name, format!("move to {target} started at {now}"));
    drive_source(world, name, log, target, source, actions, VecDeque::new());
    Ok(())
}

pub(crate) fn checkpoint_payload(node: &Node, log: LogId) -> Payload {
    let line = line(node, log);
    let (fold, heads) = fold_line(node, &line);
    Payload::Checkpoint(Checkpoint {
        manifest: fold.manifest(),
        refs: fold.refs(),
        feed_heads: heads
            .into_iter()
            .map(|(conv, seq)| (ConversationId::new(conv), seq))
            .collect(),
        store_format: STORE_FORMAT,
        runtime_parity: PARITY.into(),
        h1_violations: Vec::new(),
    })
}

#[allow(clippy::too_many_lines)]
fn drive_source(
    world: &World,
    name: u8,
    log: LogId,
    target: NodeId,
    mut source: MoveSource,
    mut actions: Vec<SourceAction>,
    mut events: VecDeque<SourceEvent>,
) {
    let node = world.node(name);
    loop {
        for action in std::mem::take(&mut actions) {
            match action {
                SourceAction::SendPrepare(p) => {
                    send_place(
                        world,
                        name,
                        target,
                        &PlaceMessage::Prepare {
                            log_id: p.log_id,
                            from: p.from,
                            proposed: p.proposed,
                            store_format: p.store_format,
                            runtime_parity: p.runtime_parity,
                        },
                    );
                    if crash_point(world, name, MovePoint::SourceAfterPrepare) {
                        return;
                    }
                }
                SourceAction::GateMoving => {
                    agent_mut(node, log, |a| a.moving = true);
                    if crash_point(world, name, MovePoint::SourceAfterQuiesce) {
                        return;
                    }
                    events.push_back(SourceEvent::TurnIdle);
                }
                SourceAction::GateReopen => agent_mut(node, log, |a| a.moving = false),
                SourceAction::StopTurn => events.push_back(SourceEvent::TurnIdle),
                SourceAction::WriteCheckpoint => {
                    // The holder writes through the log directly: its own gate is `moving`.
                    match append(world, node, log, &[checkpoint_payload(node, log)]) {
                        Ok(pos) => {
                            if crash_point(world, name, MovePoint::SourceAfterCheckpoint) {
                                return;
                            }
                            events.push_back(SourceEvent::CheckpointWritten(pos));
                        }
                        Err(_) => events.push_back(SourceEvent::HigherEpochSeen),
                    }
                }
                SourceAction::TopUp => {}
                SourceAction::WriteSeal {
                    next_epoch,
                    checkpoint,
                } => {
                    let judge = Rc::clone(&node.judge);
                    let now = world.now_ms();
                    let sealed = node.store().write(|txn| {
                        AgentLog::new(log, &*judge).seal(
                            txn,
                            &node.key,
                            Some(next_epoch),
                            checkpoint,
                            now,
                        )
                    });
                    match sealed {
                        Ok(entry) => {
                            publish(node, log, vec![entry.hash()]);
                            note(world, name, format!("sealed to {next_epoch:?} at {now}"));
                            if crash_point(world, name, MovePoint::SourceAfterSeal) {
                                return;
                            }
                            events.push_back(SourceEvent::SealWritten(Pos {
                                seq: entry.header().seq,
                                hash: entry.hash(),
                            }));
                        }
                        Err(_) => events.push_back(SourceEvent::HigherEpochSeen),
                    }
                }
                SourceAction::SendCommit { seal } => {
                    send_commit(world, name, log, target, seal);
                    if crash_point(world, name, MovePoint::SourceAfterCommit) {
                        return;
                    }
                }
                SourceAction::SendAbort(reason) => {
                    note(world, name, format!("move aborted: {}", reason.code()));
                    send_place(
                        world,
                        name,
                        target,
                        &PlaceMessage::Abort {
                            reason: reason.code().into(),
                        },
                    );
                }
            }
        }
        let Some(event) = events.pop_front() else {
            break;
        };
        actions = source.handle(&event, world.now_ms());
    }
    let finished = matches!(source.phase(), SourcePhase::Done | SourcePhase::Aborted(_));
    if !node.is_up() {
        return;
    }
    if finished {
        note(
            world,
            name,
            format!("move source finished: {:?}", source.phase()),
        );
    }
    agent_mut(node, log, |a| {
        if finished {
            a.source = None;
            a.moving = false;
        } else {
            a.source = Some((target, source));
        }
    });
}

fn send_commit(world: &World, name: u8, log: LogId, target: NodeId, seal: Pos) {
    let node = world.node(name);
    let Some(entry) = read(node, |txn| Ok(txn.entry(&seal.hash)?)).flatten() else {
        return;
    };
    let Ok(signed) = SignedEntry::from_stored(&entry) else {
        return;
    };
    let now = world.now_ms();
    agent_mut(node, log, |a| a.last_commit_ms = Some(now));
    send_place(
        world,
        name,
        target,
        &PlaceMessage::Commit {
            seal: signed.to_bytes(),
        },
    );
}

fn agent_mut(node: &Node, log: LogId, f: impl FnOnce(&mut AgentState)) {
    f(node.st.borrow_mut().agents.entry(log).or_default());
}

/// The target's view of one move.
fn find_checkpoint(node: &Node, log: LogId, from: Epoch, after_seq: u64) -> Option<Pos> {
    let line = line(node, log);
    let checkpoint = line
        .iter()
        .rev()
        .find(|e| e.kind == kind::CHECKPOINT && e.epoch == from && e.seq > after_seq)?;
    let complete = line
        .iter()
        .take_while(|e| e.seq <= checkpoint.seq)
        .all(StoredEntry::payload_complete);
    complete.then_some(Pos {
        seq: checkpoint.seq,
        hash: checkpoint.entry_hash,
    })
}

/// Materializes the canonical line below `checkpoint` and compares it with the checkpoint.
fn verify(node: &Node, log: LogId, checkpoint: Pos) -> bool {
    let line = line(node, log);
    let Some(entry) = line.iter().find(|e| e.entry_hash == checkpoint.hash) else {
        return false;
    };
    let Some(Payload::Checkpoint(expected)) = payload(node, entry) else {
        return false;
    };
    let before: Vec<StoredEntry> = line
        .iter()
        .take_while(|e| e.seq < checkpoint.seq)
        .cloned()
        .collect();
    let (fold, _) = fold_line(node, &before);
    fold.matches(&expected) && !node.st.borrow().corrupt_materialize
}

fn move_open(prior_epoch: Epoch) -> EpochOpen {
    EpochOpen {
        agent_id: None,
        reason: Reason::Move,
        prior_epoch: Some(prior_epoch),
        votes: Vec::new(),
        store_format: STORE_FORMAT,
        runtime_parity: Some(PARITY.into()),
        prior_head_seen: None,
        uncertain: Vec::new(),
    }
}

/// The target's machine plus what it needs that the machine keeps private.
#[derive(Debug)]
pub(crate) struct TargetMove {
    pub(crate) source: NodeId,
    pub(crate) from: Epoch,
    pub(crate) prepared_seq: u64,
    pub(crate) machine: MoveTarget,
}

fn drive_target(world: &World, name: u8, log: LogId, mut mv: TargetMove, first: Vec<TargetEvent>) {
    let node = world.node(name);
    let mut events: VecDeque<TargetEvent> = first.into();
    while let Some(event) = events.pop_front() {
        for action in mv.machine.handle(&event, world.now_ms()) {
            match action {
                TargetAction::Materialize { checkpoint } => {
                    events.push_back(TargetEvent::Materialized {
                        matches: verify(node, log, checkpoint),
                    });
                }
                TargetAction::SendVerified(checkpoint) => {
                    send_place(
                        world,
                        name,
                        mv.source,
                        &PlaceMessage::Verified { checkpoint },
                    );
                    if crash_point(world, name, MovePoint::TargetAfterVerified) {
                        return;
                    }
                }
                TargetAction::SendVerifyFailed => {
                    note(world, name, "verify failed".into());
                    send_place(
                        world,
                        name,
                        mv.source,
                        &PlaceMessage::VerifyFailed {
                            diffs: Vec::new(),
                            refs_mismatch: false,
                        },
                    );
                }
                TargetAction::WriteOpen { prior_epoch, seal } => {
                    if crash_point(world, name, MovePoint::TargetAfterCommit) {
                        return;
                    }
                    if claim(world, name, log, &move_open(prior_epoch), seal).is_ok() {
                        if crash_point(world, name, MovePoint::TargetAfterOpen) {
                            return;
                        }
                        events.push_back(TargetEvent::OpenWritten);
                    }
                }
                TargetAction::SendAbort(reason) => send_place(
                    world,
                    name,
                    mv.source,
                    &PlaceMessage::Abort {
                        reason: reason.code().into(),
                    },
                ),
                TargetAction::TakeOver | TargetAction::DiscardStaging => {}
            }
        }
    }
    if !node.is_up() {
        return;
    }
    let finished = matches!(
        mv.machine.phase(),
        TargetPhase::Opened | TargetPhase::Aborted(_)
    );
    let mut st = node.st.borrow_mut();
    let agent = st.agents.entry(log).or_default();
    if finished {
        agent.pending_commit = None;
    } else {
        st.targets.insert(log, mv);
    }
}

// ----- quorum claims -----

fn start_quorum_claim(
    world: &World,
    name: u8,
    log: LogId,
    record: &PlacementRecord,
    reason: Reason,
    policy: &FailoverPolicy,
) {
    let Some(quorum) = policy.quorum.clone() else {
        return;
    };
    let node = world.node(name);
    let now = world.now_ms();
    let request = ClaimRequest {
        log_id: log,
        epoch: Epoch {
            n: record.max_n_seen + 1,
            claimant: node.id,
        },
        parent: record.tip,
        manual: reason == Reason::Takeover,
    };
    let mut tally = Tally::new(request, quorum.clone());
    if quorum.voters.contains(&node.id)
        && let Some(vote) = vote(world, name, &request)
    {
        tally.add(vote);
    }
    let attempt = node
        .st
        .borrow()
        .agents
        .get(&log)
        .map_or(0, |a| a.claim_attempt);
    let wait = policy
        .stagger_ms
        .max(TICK_MS)
        .saturating_mul(1 << attempt.min(16));
    agent_mut(node, log, |a| {
        a.claim = Some(PendingClaim {
            tally,
            reason,
            prior: record.epoch,
            parent: record.tip,
            deadline_ms: now + wait,
        });
    });
    for voter in quorum.voters.iter().filter(|v| **v != node.id) {
        send_place(
            world,
            name,
            *voter,
            &PlaceMessage::ClaimRequest {
                log_id: log,
                epoch: request.epoch,
                parent: request.parent,
                reason,
            },
        );
    }
    try_certified_claim(world, name, log);
}

fn try_certified_claim(world: &World, name: u8, log: LogId) {
    let node = world.node(name);
    let ready = {
        let st = node.st.borrow();
        st.agents.get(&log).and_then(|a| {
            a.claim
                .as_ref()
                .and_then(|c| c.tally.certificate().map(|cert| (cert, c.clone())))
        })
    };
    let Some((certificate, pending)) = ready else {
        return;
    };
    agent_mut(node, log, |a| a.claim = None);
    let Some(record) = record(node, log) else {
        return;
    };
    let open = EpochOpen {
        votes: certificate,
        prior_epoch: Some(pending.prior),
        ..takeover_open(node, log, &record, pending.reason, Vec::new())
    };
    match claim(world, name, log, &open, pending.parent) {
        Ok(_) => agent_mut(node, log, |a| a.claim_attempt = 0),
        Err(error) => note(world, name, format!("certified claim refused: {error}")),
    }
}

/// Casts this node's vote for `request` (V1-V4), persisting the promise first.
fn vote(world: &World, name: u8, request: &ClaimRequest) -> Option<core_placement::SignedVote> {
    let node = world.node(name);
    let view = view(node)?;
    let record = record(node, request.log_id)?;
    let line = line(node, request.log_id);
    let now = world.now_ms();
    let holder = record.holder();
    let holder_heard_ms = match holder {
        Some(h) if h == node.id => Some(now),
        Some(h) => node.st.borrow().heard.get(&h).copied(),
        None => None,
    };
    let voter_view = VoterView {
        me: node.id,
        policy: view.policy(),
        line: &line,
        complete_seq: record.tip.seq,
        holder_heard_ms,
        now_ms: now,
    };
    node.store()
        .write(|txn| {
            cast_vote(txn, &node.key, request, &voter_view)
                .map_err(|e| core_store::StoreError::Invalid(e.to_string()))
        })
        .ok()
}

// ----- messages -----

/// Handles one `baken/place/1` message from `peer` at node `name`.
#[allow(clippy::too_many_lines)]
pub(crate) fn on_place(world: &Rc<World>, name: u8, peer: NodeId, message: PlaceMessage) {
    let node = world.node(name);
    if !node.is_up() {
        return;
    }
    let now = world.now_ms();
    node.st.borrow_mut().heard.insert(peer, now);
    let source_log = |node: &Node| -> Option<LogId> {
        node.st
            .borrow()
            .agents
            .iter()
            .find(|(_, a)| a.source.as_ref().is_some_and(|(t, _)| *t == peer))
            .map(|(log, _)| *log)
    };
    let source_event = |event: SourceEvent| {
        if let Some(log) = source_log(node) {
            let taken = agent_take_source(node, log);
            if let Some((target, source)) = taken {
                drive_source(
                    world,
                    name,
                    log,
                    target,
                    source,
                    Vec::new(),
                    VecDeque::from([event]),
                );
            }
        }
    };
    match message {
        PlaceMessage::Prepare {
            log_id,
            from,
            proposed,
            store_format,
            runtime_parity,
        } => {
            let Some(view) = view(node) else { return };
            let record = record(node, log_id);
            let facts = TargetFacts {
                store_format: STORE_FORMAT,
                runtime_parity: PARITY.into(),
                missing_profiles: Vec::new(),
                h1_violations: Vec::new(),
                replica: record.as_ref().map(|r| r.tip),
            };
            let prepare = Prepare {
                log_id,
                from,
                proposed,
                store_format,
                runtime_parity,
            };
            match check_prepare(&prepare, &view, &node.id, &facts) {
                Ok(complete) => {
                    node.st.borrow_mut().targets.insert(
                        log_id,
                        TargetMove {
                            source: peer,
                            from,
                            prepared_seq: complete.seq,
                            machine: MoveTarget::new(prepare, now),
                        },
                    );
                    send_place(world, name, peer, &PlaceMessage::Prepared { complete });
                    crash_point(world, name, MovePoint::TargetAfterPrepared);
                }
                Err(refusal) => send_place(
                    world,
                    name,
                    peer,
                    &PlaceMessage::Refused {
                        reason: refusal.code().into(),
                    },
                ),
            }
        }
        PlaceMessage::Prepared { complete } => source_event(SourceEvent::Prepared { complete }),
        PlaceMessage::Refused { reason } => source_event(SourceEvent::Refused(reason)),
        PlaceMessage::Verified { checkpoint } => source_event(SourceEvent::Verified(checkpoint)),
        PlaceMessage::VerifyFailed { .. } => source_event(SourceEvent::VerifyFailed),
        PlaceMessage::Abort { reason } => {
            if source_log(node).is_some() {
                source_event(SourceEvent::PeerAborted(reason));
            } else {
                let log = node
                    .st
                    .borrow()
                    .targets
                    .iter()
                    .find(|(_, t)| t.source == peer)
                    .map(|(log, _)| *log);
                if let Some(log) = log {
                    let mv = node.st.borrow_mut().targets.remove(&log);
                    if let Some(mv) = mv {
                        drive_target(world, name, log, mv, vec![TargetEvent::PeerAborted(reason)]);
                    }
                }
            }
        }
        PlaceMessage::Commit { seal } => {
            let Ok(entry) = SignedEntry::from_bytes(&seal) else {
                return;
            };
            let log = entry.header().log_id;
            let pos = Pos {
                seq: entry.header().seq,
                hash: entry.hash(),
            };
            if node.st.borrow().targets.contains_key(&log) {
                agent_mut(node, log, |a| a.pending_commit = Some(pos));
                target_tick(world, name, log);
            } else {
                recover(world, name, log);
            }
        }
        PlaceMessage::ClaimRequest {
            log_id,
            epoch,
            parent,
            reason,
        } => {
            let request = ClaimRequest {
                log_id,
                epoch,
                parent,
                manual: reason == Reason::Takeover,
            };
            let reply = vote(world, name, &request).map_or(
                PlaceMessage::ClaimRefused {
                    reason: "refused".into(),
                },
                |vote| PlaceMessage::ClaimVote { vote },
            );
            send_place(world, name, peer, &reply);
        }
        PlaceMessage::ClaimVote { vote } => {
            let log = vote.vote().log_id;
            agent_mut(node, log, |a| {
                if let Some(c) = a.claim.as_mut() {
                    c.tally.add(vote);
                }
            });
            try_certified_claim(world, name, log);
        }
        PlaceMessage::ClaimRefused { .. }
        | PlaceMessage::MoveRequest { .. }
        | PlaceMessage::Fenced(_) => {}
    }
}

fn agent_take_source(node: &Node, log: LogId) -> Option<(NodeId, MoveSource)> {
    node.st
        .borrow_mut()
        .agents
        .get_mut(&log)
        .and_then(|a| a.source.take())
}

// ----- the tick -----

/// Runs node `name`'s actor once.
pub(crate) fn tick(world: &Rc<World>, name: u8) {
    let node = world.node(name);
    if !node.is_up() {
        return;
    }
    let now = world.now_ms();
    let Some(view) = view(node) else {
        return;
    };
    {
        let mut st = node.st.borrow_mut();
        let peers: Vec<NodeId> = st.sessions.keys().copied().collect();
        for peer in peers {
            st.heard.insert(peer, now);
        }
    }
    startup_probe(world, name, &view, now);
    let logs = read(node, |txn| Ok(txn.logs()?)).unwrap_or_default();
    for log in logs {
        if !node.is_up() {
            return;
        }
        if !node.st.borrow().recovered {
            recover(world, name, log);
        }
        agent_tick(world, name, log, &view, now);
    }
    if node.is_up() {
        let mut st = node.st.borrow_mut();
        st.recovered = true;
        st.all_dirty = false;
    }
}

fn startup_probe(world: &World, name: u8, view: &MembershipView, now: u64) {
    let node = world.node(name);
    let mut st = node.st.borrow_mut();
    if st.resumed {
        return;
    }
    let policy = view.policy();
    let settled = now.saturating_sub(st.started_ms) >= 2 * TICK_MS;
    let answered = settled && !st.sessions.is_empty();
    let probe = match &policy.quorum {
        Some(quorum) => {
            let heard = quorum
                .voters
                .iter()
                .filter(|v| **v == node.id || st.sessions.contains_key(v))
                .count() as u64;
            if settled && heard >= quorum.required {
                StartupProbe::MajorityAnswered
            } else if answered {
                StartupProbe::PeerAnswered
            } else {
                StartupProbe::NoAnswer
            }
        }
        None if answered => StartupProbe::PeerAnswered,
        None => StartupProbe::NoAnswer,
    };
    let timed_out = now.saturating_sub(st.started_ms) >= STARTUP_PROBE_MS;
    if (probe != StartupProbe::NoAnswer || timed_out) && failover::may_resume(policy, probe) {
        st.resumed = true;
    }
}

/// Restart recovery from the log alone (C1 §5.5 crash table): a target that finds a seal addressed
/// to it writes its `epoch_open`.
fn recover(world: &World, name: u8, log: LogId) {
    let node = world.node(name);
    let Some(record) = record(node, log) else {
        return;
    };
    if let core_placement::TargetRecovery::WriteOpen {
        prior_epoch, seal, ..
    } = recover_target(&record, &node.id)
    {
        let _ = claim(world, name, log, &move_open(prior_epoch), seal);
    }
}

fn target_tick(world: &World, name: u8, log: LogId) {
    let node = world.node(name);
    let Some(mv) = node.st.borrow_mut().targets.remove(&log) else {
        return;
    };
    let mut events = vec![TargetEvent::Tick];
    if matches!(mv.machine.phase(), TargetPhase::AwaitingCheckpoint { .. })
        && let Some(checkpoint) = find_checkpoint(node, log, mv.from, mv.prepared_seq)
    {
        events.push(TargetEvent::CheckpointComplete(checkpoint));
    }
    let pending = node
        .st
        .borrow()
        .agents
        .get(&log)
        .and_then(|a| a.pending_commit);
    if let Some(seal) = pending {
        let stored = read(node, |txn| Ok(txn.entry(&seal.hash)?))
            .flatten()
            .and_then(|e| payload(node, &e));
        if let Some(Payload::EpochSeal(s)) = stored
            && s.next_epoch.is_some_and(|e| e.claimant == node.id)
        {
            events.push(TargetEvent::Commit { seal });
        }
    }
    drive_target(world, name, log, mv, events);
}

#[allow(clippy::too_many_lines)]
fn agent_tick(world: &Rc<World>, name: u8, log: LogId, view: &MembershipView, now: u64) {
    let node = world.node(name);
    let Some(record) = record(node, log) else {
        return;
    };
    let policy = view.policy().clone();
    let me = node.id;

    // The move source.
    if let Some((target, source)) = agent_take_source(node, log) {
        let mut events = VecDeque::new();
        let proposed = source.prepare().proposed;
        if source.committed() {
            if record.max_n_seen >= proposed.n {
                events.push_back(SourceEvent::TargetOpened);
            }
        } else if record.epoch != source.prepare().from || record.max_n_seen > record.epoch.n {
            events.push_back(SourceEvent::HigherEpochSeen);
        }
        events.push_back(SourceEvent::Tick);
        drive_source(world, name, log, target, source, Vec::new(), events);
        if !node.is_up() {
            return;
        }
    }

    // A sealed source keeps sending `commit` until the target opens (also after a restart).
    if let PlacementState::SealedTo(next) = record.state
        && record.epoch.claimant == me
        && record.max_n_seen < next.n
    {
        let last = node
            .st
            .borrow()
            .agents
            .get(&log)
            .and_then(|a| a.last_commit_ms);
        if last.is_none_or(|t| now >= t + core_placement::pipeline::COMMIT_RESEND_MS) {
            send_commit(world, name, log, next.claimant, record.tip);
        }
    }

    // The move target.
    target_tick(world, name, log);
    if !node.is_up() {
        return;
    }

    // Holder duties.
    let holding = record.may_append(&me);
    let heard = node.st.borrow().heard.clone();
    let fenced = holding && failover::self_fenced(&policy, &me, &heard, now);
    agent_mut(node, log, |a| a.self_fenced = fenced);
    if holding {
        let ready = {
            let st = node.st.borrow();
            st.resumed && !st.agents.get(&log).is_some_and(|a| a.moving)
        };
        let dirty = {
            let mut st = node.st.borrow_mut();
            st.all_dirty || st.dirty.remove(&log)
        };
        if ready && dirty {
            record_divergences(world, name, log, view);
        }
        return;
    }

    // Quorum claims in flight.
    let pending_deadline = node
        .st
        .borrow()
        .agents
        .get(&log)
        .and_then(|a| a.claim.as_ref().map(|c| c.deadline_ms));
    if let Some(deadline) = pending_deadline {
        if now >= deadline {
            agent_mut(node, log, |a| {
                a.claim = None;
                a.claim_attempt += 1;
                a.claim_retry_at = failover::claim_retry_at(&policy, a.claim_attempt, now)
                    .unwrap_or(now + policy.after_ms);
            });
        }
        return;
    }
    let busy = node
        .st
        .borrow()
        .agents
        .get(&log)
        .is_some_and(|a| a.source.is_some() || now < a.claim_retry_at)
        || node.st.borrow().targets.contains_key(&log);
    if busy {
        return;
    }

    // Failover.
    let watched = match record.state {
        PlacementState::SealedTo(next) => next.claimant,
        _ => record.epoch.claimant,
    };
    let facts = PeerFacts {
        classes: world.nodes.values().map(|n| (n.id, n.spec.class)).collect(),
        complete_seq: BTreeMap::new(),
    };
    let (last_heard_ms, last_dial_failure_ms, connected) = {
        let st = node.st.borrow();
        (
            st.heard.get(&watched).copied().unwrap_or(st.started_ms),
            st.dial_failures.get(&watched).copied(),
            st.sessions.keys().copied().collect::<Vec<_>>(),
        )
    };
    let peer_heard_ms = connected
        .iter()
        .filter_map(|peer| world.name_of(peer))
        .filter_map(|peer| world.node(peer).st.borrow().heard.get(&watched).copied())
        .max();
    let decision = failover::evaluate(&FailoverInput {
        view,
        placement: &record,
        me,
        facts: &facts,
        liveness: Liveness {
            last_heard_ms,
            last_dial_failure_ms,
            peer_heard_ms,
        },
        runtime_parity_matches: true,
        now_ms: now,
    });
    match decision {
        FailoverDecision::Probe { .. } => {
            let due = node
                .st
                .borrow()
                .last_probe
                .get(&watched)
                .is_none_or(|t| now >= t + PROBE_INTERVAL_MS);
            if due {
                node.st.borrow_mut().last_probe.insert(watched, now);
                probe(world, name, watched);
            }
        }
        FailoverDecision::Claim { .. } => {
            if policy.quorum.is_some() {
                start_quorum_claim(world, name, log, &record, Reason::Failover, &policy);
            } else {
                let open = takeover_open(node, log, &record, Reason::Failover, Vec::new());
                if let Err(error) = claim(world, name, log, &open, record.tip) {
                    note(world, name, format!("failover claim refused: {error}"));
                }
            }
        }
        FailoverDecision::Wait { .. } | FailoverDecision::Idle(_) => {}
    }
}

fn record_divergences(world: &World, name: u8, log: LogId, view: &MembershipView) {
    let node = world.node(name);
    let judge = Rc::clone(&node.judge);
    let Some(analysis) = read(node, |txn| AgentLog::new(log, &*judge).analyze(txn)) else {
        return;
    };
    let Some(agent) = analysis.agent_id.clone() else {
        return;
    };
    // A divergence another holder already recorded on the line is not recorded twice.
    let recorded: Vec<core_store::DivergenceId> = analysis
        .line
        .iter()
        .filter(|e| e.kind == kind::DIVERGENCE)
        .filter_map(|e| match payload(node, e) {
            Some(Payload::Divergence(d)) => Some(d.divergence_id),
            _ => None,
        })
        .collect();
    for divergence in &analysis.divergences {
        let id = divergence.inputs.id();
        if recorded.contains(&id) {
            continue;
        }
        let tail: Vec<StoredEntry> = divergence
            .tail
            .iter()
            .filter_map(|h| read(node, |txn| Ok(txn.entry(h)?)).flatten())
            .collect();
        let mut conversations: BTreeMap<String, TouchedConversation> = BTreeMap::new();
        for entry in &tail {
            let conv = match payload(node, entry) {
                Some(Payload::FeedAppend {
                    conversation_id, ..
                }) => Some(conversation_id.as_str().to_owned()),
                Some(Payload::FileAppend { path, .. } | Payload::FilePut { path, .. }) => path
                    .strip_prefix("conversations/")
                    .and_then(|rest| rest.split('/').next())
                    .map(str::to_owned),
                _ => None,
            };
            if let Some(conv) = conv {
                conversations
                    .entry(conv.clone())
                    .or_insert_with(|| TouchedConversation {
                        conversation_id: ConversationId::new(conv),
                        last_common_message_id: None,
                        owner: agent.clone(),
                    });
            }
        }
        let stranded = divergence.inputs.stranded_epoch.claimant;
        let projection = DivergenceProjection {
            conversations: conversations.into_values().collect(),
            memfs_branches: Vec::new(),
            uncertain: uncertain(node, &tail),
            from_revoked: view.status(&stranded) == Some(MemberStatus::Revoked),
        };
        let now = world.now_ms();
        let written = node.store().write(|txn| {
            AgentLog::new(log, &*judge).record_divergence(
                txn,
                &node.key,
                divergence,
                &projection,
                now,
            )
        });
        match written {
            Ok(entry) => {
                publish(node, log, vec![entry.hash()]);
                note(world, name, format!("recorded divergence {id:?} at {now}"));
            }
            Err(error) => note(world, name, format!("divergence not recorded: {error}")),
        }
    }
}
