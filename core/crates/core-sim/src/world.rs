//! The simulated nodes and how they reach each other.
//!
//! Each node has a [`MemoryStore`] (its durable state: it survives a crash), a membership log, a
//! claim judge over its current view, a log [`Hub`], and tasks: an inbox that serves accepted
//! links, one `baken/log/1` dialler per higher-named peer, the sessions with their pumps, and
//! one-shot `baken/place/1` and `baken/member/1` exchanges. Every task is local to the simulator's
//! tokio `LocalSet`, so a crash aborts them and the node's in-memory state (a move in progress, a
//! vote tally) is lost while its store is kept.
//!
//! Every exchange crosses core-carrier's [`InProcessNetwork`], so partitions, severed links, loss
//! and delay apply to the log, to placement messages and to membership gossip alike.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet};
use std::future::Future;
use std::rc::Rc;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use async_trait::async_trait;
use core_carrier::in_process::{InProcessCarrier, InProcessNetwork};
use core_carrier::{
    AcceptedLink, AppClose, Carrier, CarrierError, Frame, MembershipAccess, MembershipQuery,
    NodeAddr, ProtocolHandler, Protocols, VerifiedPeer,
};
use core_log::driver::{Clock, Hub, run_session};
use core_log::{AgentLog, Mode, SessionConfig, SessionEvent};
use core_membership::{Inserted, MembershipLog, NodeKey, OpId, SignedOp};
use core_placement::{MoveSource, PlaceMessage, PlacementRecord, Tally};
use core_proto::{Epoch, NodeId};
use core_store::{LogId, MemoryStore, Segment, Store, StoreError};
use tokio::sync::{mpsc, oneshot};
use tokio::task::{AbortHandle, spawn_local};
use tokio::time::Instant;

use crate::judge::SimJudge;
use crate::model::{
    LOG_ALPN, MEMBER_ALPN, NodeSpec, PLACE_ALPN, REDIAL_MS, STORE_FORMAT, key, node_id,
};
use crate::stream::{self, FrameCounts, Tap};

/// A point of the move pipeline (C1 §5.5) at which a crash can be injected.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum MovePoint {
    /// The source, right after sending `prepare`.
    SourceAfterPrepare,
    /// The target, right after replying `prepared`.
    TargetAfterPrepared,
    /// The source, after setting its gate to `moving`, before the checkpoint.
    SourceAfterQuiesce,
    /// The source, after appending the checkpoint.
    SourceAfterCheckpoint,
    /// The target, after sending `verified`.
    TargetAfterVerified,
    /// The source, after appending the seal (the commit point), before sending `commit`.
    SourceAfterSeal,
    /// The source, after sending `commit`.
    SourceAfterCommit,
    /// The target, after `commit` arrived, before its `epoch_open`.
    TargetAfterCommit,
    /// The target, after appending its `epoch_open`.
    TargetAfterOpen,
}

impl MovePoint {
    /// The nine points, in pipeline order.
    pub const ALL: [Self; 9] = [
        Self::SourceAfterPrepare,
        Self::TargetAfterPrepared,
        Self::SourceAfterQuiesce,
        Self::SourceAfterCheckpoint,
        Self::TargetAfterVerified,
        Self::SourceAfterSeal,
        Self::SourceAfterCommit,
        Self::TargetAfterCommit,
        Self::TargetAfterOpen,
    ];

    /// Whether the point is on the source side.
    #[must_use]
    pub const fn on_source(self) -> bool {
        matches!(
            self,
            Self::SourceAfterPrepare
                | Self::SourceAfterQuiesce
                | Self::SourceAfterCheckpoint
                | Self::SourceAfterSeal
                | Self::SourceAfterCommit
        )
    }
}

/// A quorum claim waiting for votes.
#[derive(Clone, Debug)]
pub(crate) struct PendingClaim {
    pub(crate) tally: Tally,
    pub(crate) reason: core_log::Reason,
    pub(crate) prior: Epoch,
    pub(crate) parent: core_log::Pos,
    pub(crate) deadline_ms: u64,
}

/// One node's in-memory state for one agent (lost on a crash).
#[derive(Debug, Default)]
pub(crate) struct AgentState {
    pub(crate) source: Option<(NodeId, MoveSource)>,
    pub(crate) pending_commit: Option<core_log::Pos>,
    pub(crate) moving: bool,
    pub(crate) claim: Option<PendingClaim>,
    pub(crate) claim_attempt: u32,
    pub(crate) claim_retry_at: u64,
    pub(crate) last_commit_ms: Option<u64>,
    pub(crate) self_fenced: bool,
}

/// One node's mutable state.
#[derive(Debug)]
#[allow(
    clippy::struct_excessive_bools,
    reason = "independent flags (up, resumed, recovered, a fault, a dirty mark), not a hidden state machine"
)]
pub(crate) struct NodeState {
    pub(crate) up: bool,
    pub(crate) members: MembershipLog,
    pub(crate) tasks: Vec<AbortHandle>,
    pub(crate) inbox: Option<mpsc::UnboundedSender<Inbound>>,
    pub(crate) sessions: BTreeMap<NodeId, u32>,
    pub(crate) heard: BTreeMap<NodeId, u64>,
    pub(crate) dial_failures: BTreeMap<NodeId, u64>,
    pub(crate) last_probe: BTreeMap<NodeId, u64>,
    pub(crate) started_ms: u64,
    pub(crate) resumed: bool,
    pub(crate) recovered: bool,
    pub(crate) agents: BTreeMap<LogId, AgentState>,
    pub(crate) targets: BTreeMap<LogId, crate::actor::TargetMove>,
    pub(crate) corrupt_materialize: bool,
    pub(crate) crash_at: Option<MovePoint>,
    pub(crate) rejected: Vec<&'static str>,
    pub(crate) move_log: Vec<String>,
    /// Logs whose entries or judgement changed since the holder last looked for divergences.
    pub(crate) dirty: BTreeSet<LogId>,
    pub(crate) all_dirty: bool,
}

/// One simulated node.
pub(crate) struct Node {
    pub(crate) spec: NodeSpec,
    pub(crate) key: NodeKey,
    pub(crate) id: NodeId,
    pub(crate) judge: Rc<SimJudge>,
    pub(crate) frames: Tap,
    pub(crate) store: RefCell<Arc<MemoryStore>>,
    pub(crate) hub: RefCell<Rc<Hub>>,
    pub(crate) st: RefCell<NodeState>,
    pub(crate) record_cache: RefCell<BTreeMap<LogId, (RecordKey, Option<PlacementRecord>)>>,
    access: Arc<Mutex<BTreeSet<NodeId>>>,
}

/// What a placement record derives from: segment rows, `max_n_seen`, the view's generation.
pub(crate) type RecordKey = (Vec<Segment>, Option<u64>, u64);

impl Node {
    pub(crate) fn store(&self) -> Arc<MemoryStore> {
        Arc::clone(&self.store.borrow())
    }

    pub(crate) fn hub(&self) -> Rc<Hub> {
        Rc::clone(&self.hub.borrow())
    }

    pub(crate) fn is_up(&self) -> bool {
        self.st.borrow().up
    }

    pub(crate) fn frame_counts(&self) -> FrameCounts {
        *self.frames.borrow()
    }
}

/// What a node's protocol handlers hand its inbox.
pub(crate) enum Inbound {
    /// An accepted `baken/log/1` link; dropping `done` lets the handler return (closing it).
    Log(AcceptedLink, oneshot::Sender<()>),
    /// A `baken/place/1` message from a verified peer.
    Place(NodeId, String),
    /// Membership ops pushed by a peer.
    Member(NodeId, String),
}

impl std::fmt::Debug for Inbound {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(match self {
            Self::Log(..) => "Inbound::Log",
            Self::Place(..) => "Inbound::Place",
            Self::Member(..) => "Inbound::Member",
        })
    }
}

/// The world: the network, the nodes and the virtual clock's origin.
pub(crate) struct World {
    pub(crate) net: InProcessNetwork,
    pub(crate) nodes: BTreeMap<u8, Node>,
    pub(crate) origin: Instant,
    pub(crate) constellation: OpId,
}

impl World {
    pub(crate) fn new(seed: u64, specs: &[NodeSpec], genesis: &SignedOp) -> Self {
        let constellation = genesis.id();
        let nodes = specs
            .iter()
            .map(|spec| {
                let mut members = MembershipLog::new(constellation);
                if genesis.op().header.issuer == node_id(spec.name) {
                    let _ = members.insert(genesis.clone());
                }
                let node = Node {
                    spec: *spec,
                    key: key(spec.name),
                    id: node_id(spec.name),
                    judge: Rc::new(SimJudge::new()),
                    frames: Rc::new(RefCell::new(FrameCounts::default())),
                    store: RefCell::new(Arc::new(MemoryStore::new())),
                    hub: RefCell::new(Rc::new(Hub::new())),
                    st: RefCell::new(NodeState {
                        up: false,
                        members,
                        tasks: Vec::new(),
                        inbox: None,
                        sessions: BTreeMap::new(),
                        heard: BTreeMap::new(),
                        dial_failures: BTreeMap::new(),
                        last_probe: BTreeMap::new(),
                        started_ms: 0,
                        resumed: true,
                        recovered: true,
                        agents: BTreeMap::new(),
                        targets: BTreeMap::new(),
                        corrupt_materialize: false,
                        crash_at: None,
                        rejected: Vec::new(),
                        move_log: Vec::new(),
                        dirty: BTreeSet::new(),
                        all_dirty: true,
                    }),
                    record_cache: RefCell::new(BTreeMap::new()),
                    access: Arc::new(Mutex::new(BTreeSet::new())),
                };
                (spec.name, node)
            })
            .collect();
        Self {
            net: InProcessNetwork::with_seed(seed),
            nodes,
            origin: Instant::now(),
            constellation,
        }
    }

    pub(crate) fn node(&self, name: u8) -> &Node {
        &self.nodes[&name]
    }

    pub(crate) fn name_of(&self, id: &NodeId) -> Option<u8> {
        self.nodes
            .iter()
            .find(|(_, node)| node.id == *id)
            .map(|(name, _)| *name)
    }

    pub(crate) fn now_ms(&self) -> u64 {
        u64::try_from(self.origin.elapsed().as_millis()).unwrap_or(u64::MAX)
    }

    pub(crate) fn carrier(&self, name: u8) -> InProcessCarrier {
        self.net.carrier(VerifiedPeer::Node(self.node(name).id))
    }
}

/// The simulator's clock for the log driver: milliseconds since the world began, on tokio's
/// (paused) clock.
#[derive(Clone, Copy, Debug)]
pub(crate) struct SimClock {
    origin: Instant,
}

impl Clock for SimClock {
    fn now_ms(&self) -> u64 {
        u64::try_from(self.origin.elapsed().as_millis()).unwrap_or(u64::MAX)
    }

    fn sleep_until(&self, at_ms: u64) -> impl Future<Output = ()> {
        tokio::time::sleep_until(self.origin + Duration::from_millis(at_ms))
    }
}

/// Spawns a task owned by node `name`: a crash aborts it.
pub(crate) fn spawn_owned(world: &World, name: u8, task: impl Future<Output = ()> + 'static) {
    let handle = spawn_local(task).abort_handle();
    let mut st = world.node(name).st.borrow_mut();
    st.tasks.retain(|task| !task.is_finished());
    st.tasks.push(handle);
}

// ----- membership -----

/// The members the access hook admits: the node's active members, kept current by
/// [`refresh_view`].
#[derive(Clone, Debug)]
struct Admitted(Arc<Mutex<BTreeSet<NodeId>>>);

#[async_trait]
impl MembershipQuery for Admitted {
    async fn is_member(&self, node: &NodeId) -> bool {
        self.0
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .contains(node)
    }
}

/// Re-evaluates node `name`'s membership: the judge's view, the access set, and every agent log
/// (a revoke may move a canonical segment, C1 §5.3).
pub(crate) fn refresh_view(world: &World, name: u8) {
    let node = world.node(name);
    let evaluation = {
        let st = node.st.borrow();
        if st.members.is_empty() {
            return;
        }
        st.members.evaluate()
    };
    *node.access.lock().unwrap_or_else(PoisonError::into_inner) =
        evaluation.view.members().copied().collect();
    node.st.borrow_mut().all_dirty = true;
    node.judge.set_view(evaluation.view);
    let judge = Rc::clone(&node.judge);
    let _ = node.store().write(|txn| {
        for log in txn.logs()? {
            AgentLog::new(log, &*judge)
                .evaluate(txn)
                .map_err(crate::to_store)?;
        }
        Ok::<_, StoreError>(())
    });
}

/// Inserts membership ops at node `name`; new ones are re-evaluated and gossiped to every other
/// node. Returns whether any op was new.
pub(crate) fn insert_ops(world: &Rc<World>, name: u8, ops: Vec<SignedOp>) -> bool {
    let node = world.node(name);
    let mut fresh = Vec::new();
    {
        let mut st = node.st.borrow_mut();
        for op in ops {
            if let Ok(Inserted::Stored(ids)) = st.members.insert(op) {
                fresh.extend(ids);
            }
        }
    }
    if fresh.is_empty() {
        return false;
    }
    refresh_view(world, name);
    let envelopes: Vec<Vec<u8>> = {
        let st = node.st.borrow();
        fresh
            .iter()
            .filter_map(|id| st.members.get(id).map(SignedOp::envelope))
            .collect()
    };
    for (peer, other) in &world.nodes {
        if *peer != name {
            push_ops(world, name, other.id, &envelopes);
        }
    }
    true
}

/// Pushes every op node `name` holds to `peer` (anti-entropy when membership heads differ).
pub(crate) fn push_all_ops(world: &Rc<World>, name: u8, peer: NodeId) {
    let envelopes: Vec<Vec<u8>> = world
        .node(name)
        .st
        .borrow()
        .members
        .ops()
        .map(SignedOp::envelope)
        .collect();
    push_ops(world, name, peer, &envelopes);
}

/// The frame: this node's heads, then the op envelopes (`heads|ops`, hex, comma and dot
/// separated). The heads let the receiver tell whether the pusher lacks ops it holds.
fn push_ops(world: &Rc<World>, name: u8, peer: NodeId, envelopes: &[Vec<u8>]) {
    if envelopes.is_empty() || !world.node(name).is_up() {
        return;
    }
    let heads = world
        .node(name)
        .st
        .borrow()
        .members
        .heads()
        .iter()
        .map(|h| hex::encode(h.as_bytes()))
        .collect::<Vec<_>>()
        .join(",");
    let ops = envelopes
        .iter()
        .map(hex::encode)
        .collect::<Vec<_>>()
        .join(".");
    let text = format!("{heads}|{ops}");
    let carrier = world.carrier(name);
    spawn_owned(world, name, async move {
        let _ = one_shot(&carrier, peer, MEMBER_ALPN, Frame::new(text)).await;
    });
}

fn decode_ops(text: &str) -> (Vec<OpId>, Vec<SignedOp>) {
    let (heads, ops) = text.split_once('|').unwrap_or(("", text));
    let heads = heads
        .split(',')
        .filter_map(|part| hex::decode(part).ok())
        .filter_map(|bytes| <[u8; 32]>::try_from(bytes).ok())
        .map(OpId::from_bytes)
        .collect();
    let ops = ops
        .split('.')
        .filter_map(|part| hex::decode(part).ok())
        .filter_map(|bytes| SignedOp::decode_envelope(&bytes).ok())
        .collect();
    (heads, ops)
}

/// Whether a pusher whose heads are `heads` lacks an op node `name` holds.
fn pusher_lacks(world: &World, name: u8, heads: &[OpId]) -> bool {
    let st = world.node(name).st.borrow();
    let mut known: BTreeSet<OpId> = heads.iter().copied().collect();
    for head in heads {
        known.extend(st.members.causal_past(head));
    }
    st.members.heads().iter().any(|h| !known.contains(h))
}

// ----- placement messages -----

/// Sends one `baken/place/1` message from node `name` to `to`, fire and forget.
pub(crate) fn send_place(world: &World, name: u8, to: NodeId, message: &PlaceMessage) {
    if !world.node(name).is_up() {
        return;
    }
    let frame = Frame::new(hex::encode(message.encode_frame()));
    let carrier = world.carrier(name);
    spawn_owned(world, name, async move {
        let _ = one_shot(&carrier, to, PLACE_ALPN, frame).await;
    });
}

/// One exchange on its own link: dial, open a call lane carrying `frame`, and wait until the
/// node's handler has read it and closed the link.
async fn one_shot(carrier: &InProcessCarrier, to: NodeId, alpn: &[u8], frame: Frame) -> bool {
    let Ok(dialed) = carrier.dial(&NodeAddr::new(to), alpn).await else {
        return false;
    };
    if dialed.link.open_call(frame).await.is_err() {
        return false;
    }
    let _ = dialed.link.closed().await;
    true
}

/// A dial to `peer` that only proves reachability (the failover probe). Records the outcome.
pub(crate) fn probe(world: &Rc<World>, name: u8, peer: NodeId) {
    let carrier = world.carrier(name);
    let w = Rc::clone(world);
    spawn_owned(world, name, async move {
        let reached = carrier.dial(&NodeAddr::new(peer), LOG_ALPN).await;
        let now = w.now_ms();
        let mut st = w.node(name).st.borrow_mut();
        match reached {
            Ok(dialed) => {
                st.heard.insert(peer, now);
                dialed.link.close(AppClose::closed());
            }
            Err(_) => {
                st.dial_failures.insert(peer, now);
            }
        }
    });
}

// ----- the log sessions -----

/// Dials `peer` on `baken/log/1` whenever both are members in this node's view and no session
/// with it is open (in either direction), for as long as node `name` runs.
async fn log_dialer(world: Rc<World>, name: u8, peer: u8) {
    loop {
        let wanted = {
            let node = world.node(name);
            let other = world.node(peer).id;
            !node.st.borrow().sessions.contains_key(&other)
                && node
                    .judge
                    .view()
                    .is_some_and(|view| view.is_member(&node.id) && view.is_member(&other))
        };
        if wanted {
            dial_log(&world, name, peer).await;
        }
        tokio::time::sleep(Duration::from_millis(REDIAL_MS)).await;
    }
}

async fn dial_log(world: &Rc<World>, name: u8, peer: u8) {
    let peer_id = world.node(peer).id;
    match world
        .carrier(name)
        .dial(&NodeAddr::new(peer_id), LOG_ALPN)
        .await
    {
        Err(CarrierError::Closed(_)) => {
            // Refused by the peer's access hook: it may know an op this node lacks (a revoke).
            // Pushing ours makes it push back what we are missing.
            push_all_ops(world, name, peer_id);
        }
        Err(_) => {
            let now = world.now_ms();
            world
                .node(name)
                .st
                .borrow_mut()
                .dial_failures
                .insert(peer_id, now);
        }
        Ok(dialed) => {
            let link = Arc::clone(&dialed.link);
            if let Ok(lane) = link.open_call(Frame::new("log")).await {
                run_log(world, name, peer_id, lane).await;
            }
            link.close(AppClose::closed());
        }
    }
}

async fn serve_log(world: Rc<World>, name: u8, accepted: AcceptedLink, done: oneshot::Sender<()>) {
    let Some(peer) = accepted.link.remote().node_id().copied() else {
        return;
    };
    let mut calls = accepted.calls;
    if let Some(mut lane) = calls.accept().await {
        let _request = lane.source.recv().await;
        run_log(&world, name, peer, lane).await;
    }
    drop(done);
}

/// Runs one session with `peer` until it ends.
async fn run_log(world: &Rc<World>, name: u8, peer: NodeId, lane: core_carrier::CallLane) {
    let node = world.node(name);
    let store = node.store();
    let hub = node.hub();
    let judge = Rc::clone(&node.judge);
    let config = SessionConfig {
        node_id: node.id,
        constellation_id: *world.constellation.as_bytes(),
        member_heads: node
            .st
            .borrow()
            .members
            .heads()
            .iter()
            .map(|id| *id.as_bytes())
            .collect(),
        mode: Mode::Live,
        store_format: STORE_FORMAT,
    };
    let stream = stream::bind(lane, Rc::clone(&node.frames));
    {
        let now = world.now_ms();
        let mut st = node.st.borrow_mut();
        st.tasks.extend(stream.pumps.iter().cloned());
        *st.sessions.entry(peer).or_default() += 1;
        st.heard.insert(peer, now);
    }
    let clock = SimClock {
        origin: world.origin,
    };
    let observer = Rc::clone(world);
    let _ = run_session(
        stream.reader,
        stream.writer,
        &*store,
        &*judge,
        &hub,
        &clock,
        config,
        move |event| on_session_event(&observer, name, peer, event),
    )
    .await;
    let now = world.now_ms();
    let mut st = node.st.borrow_mut();
    if let Some(count) = st.sessions.get_mut(&peer) {
        *count = count.saturating_sub(1);
        if *count == 0 {
            st.sessions.remove(&peer);
        }
    }
    st.heard.insert(peer, now);
}

fn on_session_event(world: &Rc<World>, name: u8, peer: NodeId, event: &SessionEvent) {
    let now = world.now_ms();
    {
        let mut st = world.node(name).st.borrow_mut();
        st.heard.insert(peer, now);
        match event {
            SessionEvent::Rejected { code, .. } => st.rejected.push(code),
            SessionEvent::Stored { log_id, .. } | SessionEvent::Evaluated { log_id, .. } => {
                st.dirty.insert(*log_id);
            }
            _ => {}
        }
    }
    if matches!(event, SessionEvent::MembershipDiffers) {
        push_all_ops(world, name, peer);
    }
}

// ----- the inbox and the node lifecycle -----

#[derive(Clone, Copy)]
enum Kind {
    Log,
    Member,
    Place,
}

struct Forward {
    kind: Kind,
    inbox: mpsc::UnboundedSender<Inbound>,
}

#[async_trait]
impl ProtocolHandler for Forward {
    async fn serve(&self, link: AcceptedLink) {
        let Some(peer) = link.link.remote().node_id().copied() else {
            return;
        };
        if let Kind::Log = self.kind {
            let (done, wait) = oneshot::channel();
            if self.inbox.send(Inbound::Log(link, done)).is_ok() {
                let _ = wait.await;
            }
            return;
        }
        let mut calls = link.calls;
        let Some(mut lane) = calls.accept().await else {
            return;
        };
        let Some(frame) = lane.source.recv().await else {
            return;
        };
        let item = match self.kind {
            Kind::Place => Inbound::Place(peer, frame.into_string()),
            _ => Inbound::Member(peer, frame.into_string()),
        };
        let _ = self.inbox.send(item);
    }
}

async fn inbox_loop(world: Rc<World>, name: u8, mut inbox: mpsc::UnboundedReceiver<Inbound>) {
    while let Some(item) = inbox.recv().await {
        if !world.node(name).is_up() {
            break;
        }
        match item {
            Inbound::Log(link, done) => {
                let w = Rc::clone(&world);
                spawn_owned(&world, name, serve_log(w, name, link, done));
            }
            Inbound::Place(peer, text) => {
                let message = hex::decode(&text)
                    .ok()
                    .and_then(|bytes| PlaceMessage::decode_frame(&bytes).ok());
                if let Some(message) = message {
                    crate::actor::on_place(&world, name, peer, message);
                }
            }
            Inbound::Member(peer, text) => {
                let (heads, ops) = decode_ops(&text);
                insert_ops(&world, name, ops);
                if pusher_lacks(&world, name, &heads) {
                    push_all_ops(&world, name, peer);
                }
            }
        }
    }
}

/// Starts (or restarts) node `name`: serves its protocols and spawns its inbox and diallers.
/// `fresh` is false for a restart after a crash, which runs the startup probe and recovery.
pub(crate) fn start(world: &Rc<World>, name: u8, fresh: bool) {
    let node = world.node(name);
    let now = world.now_ms();
    let (tx, rx) = mpsc::unbounded_channel();
    {
        let mut st = node.st.borrow_mut();
        st.up = true;
        st.inbox = Some(tx.clone());
        st.started_ms = now;
        st.resumed = fresh;
        st.recovered = fresh;
        st.all_dirty = true;
        st.sessions.clear();
        // A node starts watching every peer when it starts (C1 §5.8 liveness).
        let peers: Vec<NodeId> = world.nodes.values().map(|n| n.id).collect();
        for peer in peers {
            st.heard.insert(peer, now);
        }
    }
    *node.hub.borrow_mut() = Rc::new(Hub::new());
    refresh_view(world, name);
    let protocols = Protocols::builder()
        .access(MembershipAccess::new(Admitted(Arc::clone(&node.access))).open_to_all(MEMBER_ALPN))
        .accept(
            LOG_ALPN,
            Forward {
                kind: Kind::Log,
                inbox: tx.clone(),
            },
        )
        .accept(
            MEMBER_ALPN,
            Forward {
                kind: Kind::Member,
                inbox: tx.clone(),
            },
        )
        .accept(
            PLACE_ALPN,
            Forward {
                kind: Kind::Place,
                inbox: tx,
            },
        )
        .build();
    world.net.serve(node.id, protocols);
    spawn_owned(world, name, inbox_loop(Rc::clone(world), name, rx));
    for peer in world.nodes.keys().copied().filter(|peer| *peer != name) {
        spawn_owned(world, name, log_dialer(Rc::clone(world), name, peer));
    }
    // Membership reaches nodes that joined while this one was down, and this one catches up.
    for other in world.nodes.values().filter(|other| other.id != node.id) {
        push_all_ops(world, name, other.id);
    }
}

/// Crashes node `name`: its tasks stop, its links are lost and its in-memory state is gone. Its
/// store is kept.
pub(crate) fn crash(world: &World, name: u8) {
    let node = world.node(name);
    let tasks = {
        let mut st = node.st.borrow_mut();
        if !st.up {
            return;
        }
        st.up = false;
        st.inbox = None;
        st.sessions.clear();
        st.agents.clear();
        st.targets.clear();
        st.crash_at = None;
        std::mem::take(&mut st.tasks)
    };
    for task in tasks {
        task.abort();
    }
    world.net.stop(&node.id);
}
