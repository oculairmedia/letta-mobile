//! The simulator's handle and its scenario DSL.
//!
//! [`run`] builds a tokio current-thread runtime with the clock paused, so every timer (session
//! pings, the failover window, delays the network injects) fires in virtual time and a run is a
//! function of its seed and its steps. [`Sim`] is the handle a scenario drives; [`Step`] is the
//! same vocabulary as data, for the scripted and generated schedules. Every step that changes the
//! world is followed by the safety check; [`Sim::quiesce`] lets the network settle and then checks
//! the convergence invariants.

use std::cell::{Cell, RefCell};
use std::collections::{BTreeMap, BTreeSet};
use std::future::Future;
use std::rc::Rc;
use std::sync::Arc;
use std::time::Duration;

use core_carrier::VerifiedPeer;
use core_log::hashes::log_id;
use core_log::payload::ActionState;
use core_log::{Analysis, LogError, Payload, Pos, Reason};
use core_membership::{
    CapSet, CatalogRoot, MembershipView, Op, OpHeader, OpPayload, Preset, SignedOp,
};
use core_placement::PlacementRecord;
use core_proto::{AgentId, ConversationId, NodeId};
use core_store::{LogId, MemoryStore, Store, StoreError};
use tokio::task::{AbortHandle, LocalSet, spawn_local};

use crate::actor::{self, MoveRefusal, Refusal};
use crate::check::{self, Checker, Violation};
use crate::model::{NodeSpec, Policy, TICK_MS, key, node_id};
use crate::stream::FrameCounts;
use crate::world::{self, MovePoint, World};

/// Runs `scenario` on a fresh simulator with `specs` as its nodes, node `founder` holding the
/// constellation's genesis (every capability). The runtime is current-thread with tokio's clock
/// paused, and the network's loss draws start from `seed`.
///
/// # Panics
///
/// When the runtime cannot be built, or the scenario panics (a violated invariant).
pub fn run<F, Fut>(seed: u64, specs: &[NodeSpec], founder: u8, scenario: F)
where
    F: FnOnce(Sim) -> Fut,
    Fut: Future<Output = ()>,
{
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_time()
        .start_paused(true)
        .build()
        .expect("a current-thread runtime");
    LocalSet::new().block_on(&runtime, async {
        let sim = Sim::new(seed, specs, founder);
        scenario(sim).await;
    });
}

/// One step of a scenario, as data (C1 §9.3 step verbs).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Step {
    /// `add`: `issuer` adds `subject` with a preset.
    Add {
        /// The admin.
        issuer: u8,
        /// The new member.
        subject: u8,
        /// Its capabilities.
        preset: Preset,
    },
    /// `revoke`.
    Revoke {
        /// The admin.
        issuer: u8,
        /// The revoked member.
        subject: u8,
    },
    /// `policy`.
    Policy {
        /// The admin.
        issuer: u8,
        /// The policy.
        policy: Policy,
    },
    /// `create_agent`.
    CreateAgent {
        /// The node creating it (its first holder).
        node: u8,
        /// The agent.
        agent: String,
    },
    /// `turn(agent, conv, n_entries)` at `node` (refused unless it holds the agent).
    Turn {
        /// The node.
        node: u8,
        /// The agent.
        agent: String,
        /// The conversation.
        conv: String,
        /// Messages.
        n: usize,
    },
    /// `tool_call(agent, key, finish)` at `node`.
    ToolCall {
        /// The node.
        node: u8,
        /// The agent.
        agent: String,
        /// The idempotency key.
        key: String,
        /// Whether it finishes.
        finish: bool,
    },
    /// `move(agent, to)`, started at `from`.
    Move {
        /// The holder.
        from: u8,
        /// The agent.
        agent: String,
        /// The target.
        to: u8,
    },
    /// `claim(node, agent, reason)`: a manual claim.
    Claim {
        /// The claimant.
        node: u8,
        /// The agent.
        agent: String,
        /// `takeover` or `takeover_forced`.
        reason: Reason,
    },
    /// `crash(node)`.
    Crash(u8),
    /// `crash(node, at)`: arm a crash at a move point.
    CrashAt(u8, MovePoint),
    /// `restart(node)`.
    Restart(u8),
    /// `partition([[...],[...]])`.
    Partition(Vec<Vec<u8>>),
    /// Sever the open links between two nodes once.
    Sever(u8, u8),
    /// `heal`.
    Heal,
    /// `advance(ms)`.
    Advance(u64),
    /// `drop_rate(p)`, per million frames.
    DropRate(u32),
}

/// The simulator: N nodes on an in-process network, on a paused clock.
pub struct Sim {
    world: Rc<World>,
    checker: RefCell<Checker>,
    partitions: RefCell<BTreeSet<(u8, u8)>>,
    topology_ms: Cell<u64>,
    backups: RefCell<Vec<MemoryStore>>,
    ticker: AbortHandle,
}

impl Drop for Sim {
    fn drop(&mut self) {
        self.ticker.abort();
        for name in self.world.nodes.keys() {
            world::crash(&self.world, *name);
        }
    }
}

impl Sim {
    fn new(seed: u64, specs: &[NodeSpec], founder: u8) -> Self {
        let op = Op {
            header: OpHeader {
                constellation: None,
                issuer: node_id(founder),
                parents: BTreeSet::new(),
                ts_ms: 1,
            },
            payload: OpPayload::Genesis {
                caps: Preset::FirstDevice.capabilities(),
                name: None,
                label: None,
                catalog_root: CatalogRoot([7; 16]),
            },
            extensions: BTreeMap::new(),
        };
        let genesis = SignedOp::sign(&op, &key(founder)).expect("the genesis signs");
        let world = Rc::new(World::new(seed, specs, &genesis));
        for name in world.nodes.keys() {
            world::start(&world, *name, true);
        }
        let w = Rc::clone(&world);
        let ticker = spawn_local(async move {
            let mut interval = tokio::time::interval(Duration::from_millis(TICK_MS));
            loop {
                interval.tick().await;
                for name in w.nodes.keys() {
                    actor::tick(&w, *name);
                }
            }
        })
        .abort_handle();
        Self {
            world,
            checker: RefCell::new(Checker::default()),
            partitions: RefCell::new(BTreeSet::new()),
            topology_ms: Cell::new(0),
            backups: RefCell::new(Vec::new()),
            ticker,
        }
    }

    // ----- time and checks -----

    /// Virtual milliseconds since the simulation began.
    #[must_use]
    pub fn now_ms(&self) -> u64 {
        self.world.now_ms()
    }

    /// Lets `ms` of virtual time pass, then checks the safety invariants.
    pub async fn advance(&self, ms: u64) {
        tokio::time::sleep(Duration::from_millis(ms)).await;
        self.check();
    }

    /// Whether `a` and `b` are up, not partitioned from each other, and admit each other.
    #[must_use]
    pub fn connected(&self, a: u8, b: u8) -> bool {
        let key = (a.min(b), a.max(b));
        let (na, nb) = (self.world.node(a), self.world.node(b));
        na.is_up()
            && nb.is_up()
            && !self.partitions.borrow().contains(&key)
            && [na, nb].iter().all(|n| {
                n.judge
                    .view()
                    .is_some_and(|v| v.is_member(&na.id) && v.is_member(&nb.id))
            })
    }

    /// The safety invariants on every node.
    ///
    /// # Panics
    ///
    /// When one is violated.
    pub fn check(&self) {
        let violations = self.violations();
        assert!(
            violations.is_empty(),
            "invariants violated at {} ms:\n{}",
            self.now_ms(),
            violations
                .iter()
                .map(ToString::to_string)
                .collect::<Vec<_>>()
                .join("\n")
        );
    }

    /// The safety violations now (the fuzz runner reports them instead of panicking).
    pub fn violations(&self) -> Vec<Violation> {
        let now = self.now_ms();
        let topology = self.topology_ms.get();
        let connected = |a: u8, b: u8| {
            self.connected(a, b) && now.saturating_sub(topology) > check::P1_GRACE_MS
        };
        self.checker
            .borrow_mut()
            .safety(&self.world, now, &connected)
    }

    /// The components of up, mutually connected nodes.
    #[must_use]
    pub fn components(&self) -> Vec<Vec<u8>> {
        let names: Vec<u8> = self
            .world
            .nodes
            .keys()
            .copied()
            .filter(|n| self.world.node(*n).is_up())
            .collect();
        let mut seen = BTreeSet::new();
        let mut components = Vec::new();
        for start in &names {
            if !seen.insert(*start) {
                continue;
            }
            let mut component = vec![*start];
            let mut stack = vec![*start];
            while let Some(at) = stack.pop() {
                for other in &names {
                    if !seen.contains(other) && self.connected(at, *other) {
                        seen.insert(*other);
                        component.push(*other);
                        stack.push(*other);
                    }
                }
            }
            component.sort_unstable();
            components.push(component);
        }
        components
    }

    /// Lets the network settle (no new entries, ops or placement changes for 5 virtual seconds,
    /// at most `max_ms`), then checks the safety invariants and the convergence invariants per
    /// component. Returns the violations instead of panicking when `report` is set.
    pub async fn settle(&self, max_ms: u64) -> Vec<Violation> {
        let deadline = self.now_ms() + max_ms;
        let mut last = self.fingerprint();
        let mut stable = 0;
        while self.now_ms() < deadline && stable < 5 {
            tokio::time::sleep(Duration::from_millis(TICK_MS)).await;
            let now = self.fingerprint();
            if now == last {
                stable += 1;
            } else {
                stable = 0;
                last = now;
            }
        }
        let mut violations = self.violations();
        for component in self.components() {
            violations.extend(check::quiescent(&self.world, &component));
        }
        violations
    }

    /// [`settle`](Self::settle) for up to two virtual minutes, asserting every invariant.
    ///
    /// # Panics
    ///
    /// When an invariant is violated.
    pub async fn quiesce(&self) {
        let violations = self.settle(120_000).await;
        assert!(
            violations.is_empty(),
            "invariants violated at quiescence ({} ms):\n{}",
            self.now_ms(),
            violations
                .iter()
                .map(ToString::to_string)
                .collect::<Vec<_>>()
                .join("\n")
        );
    }

    fn fingerprint(&self) -> Vec<(u8, usize, usize, Option<PlacementRecord>)> {
        let mut out = Vec::new();
        for (name, node) in &self.world.nodes {
            let entries = node
                .store()
                .read(|txn| {
                    let mut n = 0;
                    for log in txn.logs()? {
                        for segment in txn.segments(&log)? {
                            n += txn
                                .segment_entries(&log, &segment.epoch, 0, usize::MAX)?
                                .len();
                            n += usize::try_from(segment.complete_seq.unwrap_or(0)).unwrap_or(0);
                        }
                    }
                    Ok::<_, StoreError>(n)
                })
                .unwrap_or(0);
            let ops = node.st.borrow().members.len();
            let logs = node.store().read(|t| t.logs()).unwrap_or_default();
            for log in logs {
                out.push((*name, entries, ops, actor::record(node, log)));
            }
            if out.last().is_none_or(|(n, ..)| n != name) {
                out.push((*name, entries, ops, None));
            }
        }
        out
    }

    // ----- membership -----

    fn issue(&self, issuer: u8, payload: OpPayload) -> Result<(), String> {
        let node = self.world.node(issuer);
        if !node.is_up() {
            return Err(format!("node {issuer} is down"));
        }
        let parents = node.st.borrow().members.heads();
        let op = Op {
            header: OpHeader {
                constellation: Some(self.world.constellation),
                issuer: node.id,
                parents,
                ts_ms: self.now_ms().max(2),
            },
            payload,
            extensions: BTreeMap::new(),
        };
        let signed = SignedOp::sign(&op, &node.key).map_err(|e| e.to_string())?;
        world::insert_ops(&self.world, issuer, vec![signed]);
        self.check();
        Ok(())
    }

    /// `issuer` adds `subject` with `caps`.
    ///
    /// # Errors
    ///
    /// When the issuer is down or the op does not sign.
    pub fn add(&self, issuer: u8, subject: u8, caps: CapSet) -> Result<(), String> {
        self.issue(
            issuer,
            OpPayload::Add {
                subject: node_id(subject),
                caps,
                name: None,
            },
        )
    }

    /// `issuer` revokes `subject`.
    ///
    /// # Errors
    ///
    /// As [`add`](Self::add).
    pub fn revoke(&self, issuer: u8, subject: u8) -> Result<(), String> {
        self.issue(
            issuer,
            OpPayload::Revoke {
                subject: node_id(subject),
                reason: None,
            },
        )
    }

    /// `issuer` sets the failover policy.
    ///
    /// # Errors
    ///
    /// As [`add`](Self::add).
    pub fn policy(&self, issuer: u8, policy: &Policy) -> Result<(), String> {
        self.issue(
            issuer,
            OpPayload::PolicySet {
                policy: policy.to_value(),
            },
        )
    }

    // ----- agents -----

    /// Creates `agent`'s log at `node`.
    ///
    /// # Errors
    ///
    /// The log's refusal.
    pub fn create_agent(&self, node: u8, agent: &str) -> Result<(), LogError> {
        let result = actor::create_agent(&self.world, node, &AgentId::new(agent));
        self.check();
        result
    }

    /// A turn of `n` messages at `node`.
    ///
    /// # Errors
    ///
    /// The input gate's refusal.
    pub fn turn(&self, node: u8, agent: &str, conv: &str, n: usize) -> Result<Pos, Refusal> {
        let result = actor::turn(&self.world, node, &AgentId::new(agent), conv, n);
        self.check();
        result
    }

    /// A tool call at `node`: `started`, and `finished` too when `finish`.
    ///
    /// # Errors
    ///
    /// The input gate's refusal.
    pub fn tool_call(
        &self,
        node: u8,
        agent: &str,
        conv: &str,
        idempotency_key: &str,
        finish: bool,
    ) -> Result<Pos, Refusal> {
        let status = |state| Payload::ActionStatus {
            idempotency_key: idempotency_key.into(),
            tool_call_id: format!("call-{idempotency_key}"),
            conversation_id: ConversationId::new(conv),
            state,
        };
        let mut payloads = vec![status(ActionState::Started)];
        if finish {
            payloads.push(status(ActionState::Finished));
        }
        let result = actor::write(&self.world, node, &AgentId::new(agent), &payloads);
        self.check();
        result
    }

    /// Writes whole files as holder (an agent record, a memory edit).
    ///
    /// # Errors
    ///
    /// The input gate's refusal.
    pub fn file_put(&self, node: u8, agent: &str, files: &[(&str, &[u8])]) -> Result<Pos, Refusal> {
        let payloads: Vec<Payload> = files
            .iter()
            .map(|(path, content)| Payload::file_put(path, content.to_vec(), false))
            .collect();
        let result = actor::write(&self.world, node, &AgentId::new(agent), &payloads);
        self.check();
        result
    }

    /// Appends a `checkpoint` of the canonical line as holder.
    ///
    /// # Errors
    ///
    /// The input gate's refusal.
    pub fn checkpoint(&self, node: u8, agent: &str) -> Result<Pos, Refusal> {
        let payload = actor::checkpoint_payload(self.world.node(node), Self::log_id(agent));
        let result = actor::write(&self.world, node, &AgentId::new(agent), &[payload]);
        self.check();
        result
    }

    /// Compacts `agent`'s log at `node` (core-log's `prune`); returns the entries pruned.
    ///
    /// # Errors
    ///
    /// The log's error.
    pub fn compact(
        &self,
        node: u8,
        agent: &str,
        retention: core_log::prune::FeedRetention,
    ) -> Result<Vec<core_store::EntryHash>, LogError> {
        let n = self.world.node(node);
        let judge = Rc::clone(&n.judge);
        let log = Self::log_id(agent);
        let result = n.store().write(|txn| {
            core_log::prune::prune(&core_log::AgentLog::new(log, &*judge), txn, retention)
        });
        self.check();
        result
    }

    /// Every node's stored entry hashes, sorted, and its notes: equal for equal runs.
    #[must_use]
    pub fn digest(&self) -> Vec<(u8, Vec<core_store::EntryHash>, Vec<String>)> {
        self.world
            .nodes
            .iter()
            .map(|(name, node)| {
                let mut hashes: Vec<core_store::EntryHash> = node
                    .store()
                    .read(|txn| {
                        let mut out = Vec::new();
                        for log in txn.logs()? {
                            for segment in txn.segments(&log)? {
                                out.extend(
                                    txn.segment_entries(&log, &segment.epoch, 0, usize::MAX)?
                                        .into_iter()
                                        .map(|e| e.entry_hash),
                                );
                            }
                        }
                        Ok::<_, StoreError>(out)
                    })
                    .unwrap_or_default();
                hashes.sort();
                (*name, hashes, node.st.borrow().move_log.clone())
            })
            .collect()
    }

    /// Starts moving `agent` from `from` to `to`.
    ///
    /// # Errors
    ///
    /// When the request is refused before anything starts.
    pub fn move_agent(&self, from: u8, agent: &str, to: u8) -> Result<(), MoveRefusal> {
        let result = actor::start_move(&self.world, from, &AgentId::new(agent), node_id(to));
        self.check();
        result
    }

    /// A manual claim (`takeover` or `takeover_forced`) at `node`.
    ///
    /// # Errors
    ///
    /// When the claim is refused.
    pub fn claim(&self, node: u8, agent: &str, reason: Reason) -> Result<(), String> {
        let result = actor::manual_claim(&self.world, node, &AgentId::new(agent), reason);
        self.check();
        result
    }

    // ----- faults -----

    fn topology_changed(&self) {
        self.topology_ms.set(self.now_ms());
    }

    /// Partitions the nodes into `groups`: links between groups are lost and dials fail. Nodes in
    /// no group are cut off from every group.
    pub fn partition(&self, groups: &[&[u8]]) {
        let names: Vec<u8> = self.world.nodes.keys().copied().collect();
        let group_of = |n: u8| groups.iter().position(|g| g.contains(&n));
        for a in &names {
            for b in &names {
                if a < b && (group_of(*a).is_none() || group_of(*a) != group_of(*b)) {
                    self.partitions.borrow_mut().insert((*a, *b));
                    self.world.net.partition(
                        VerifiedPeer::Node(node_id(*a)),
                        VerifiedPeer::Node(node_id(*b)),
                    );
                }
            }
        }
        self.topology_changed();
        self.check();
    }

    /// Heals every partition.
    pub fn heal(&self) {
        self.world.net.heal_all();
        self.partitions.borrow_mut().clear();
        self.topology_changed();
        self.check();
    }

    /// Drops the transport under the links between `a` and `b` once.
    pub fn sever(&self, a: u8, b: u8) {
        let _ = self.world.net.sever(
            VerifiedPeer::Node(node_id(a)),
            VerifiedPeer::Node(node_id(b)),
        );
        self.topology_changed();
        self.check();
    }

    /// Loses a link with probability `per_million` per frame, between every pair.
    pub fn drop_rate(&self, per_million: u32) {
        for a in self.world.nodes.keys() {
            for b in self.world.nodes.keys().filter(|b| *b > a) {
                self.world.net.set_loss(
                    VerifiedPeer::Node(node_id(*a)),
                    VerifiedPeer::Node(node_id(*b)),
                    per_million,
                );
            }
        }
    }

    /// Delays every frame between `a` and `b` by `ms`.
    pub fn delay(&self, a: u8, b: u8, ms: u64) {
        self.world.net.set_delay(
            VerifiedPeer::Node(node_id(a)),
            VerifiedPeer::Node(node_id(b)),
            Duration::from_millis(ms),
        );
    }

    /// Crashes `node`: tasks stop, links are lost, in-memory state is gone; the store stays.
    pub fn crash(&self, node: u8) {
        world::crash(&self.world, node);
        self.topology_changed();
        self.check();
    }

    /// Arms a crash of `node` at a move point.
    pub fn crash_at(&self, node: u8, point: MovePoint) {
        self.world.node(node).st.borrow_mut().crash_at = Some(point);
    }

    /// Restarts a crashed `node`: startup probe, then recovery from its log.
    pub fn restart(&self, node: u8) {
        if !self.world.node(node).is_up() {
            world::start(&self.world, node, false);
            self.topology_changed();
        }
        self.check();
    }

    /// Makes `node`'s materialization differ from the checkpoint (one byte), so its verify fails.
    pub fn corrupt_materialize(&self, node: u8, corrupt: bool) {
        self.world.node(node).st.borrow_mut().corrupt_materialize = corrupt;
    }

    /// Takes a backup of `node`'s store; returns its number.
    pub fn backup(&self, node: u8) -> usize {
        let copy = self.world.node(node).store().backup();
        let mut backups = self.backups.borrow_mut();
        backups.push(copy);
        backups.len() - 1
    }

    /// Restores `node` (which must be down) from backup `number`.
    ///
    /// # Panics
    ///
    /// When the node is up or the backup does not exist.
    pub fn restore(&self, node: u8, number: usize) {
        let n = self.world.node(node);
        assert!(!n.is_up(), "restore needs the node down");
        let copy = self.backups.borrow()[number].backup();
        *n.store.borrow_mut() = Arc::new(copy);
        n.record_cache.borrow_mut().clear();
        self.checker.borrow_mut().reset(node);
    }

    /// Applies one step.
    ///
    /// # Panics
    ///
    /// When a safety invariant is violated afterwards.
    pub async fn step(&self, step: &Step) {
        match step {
            Step::Add {
                issuer,
                subject,
                preset,
            } => drop(self.add(*issuer, *subject, preset.capabilities())),
            Step::Revoke { issuer, subject } => drop(self.revoke(*issuer, *subject)),
            Step::Policy { issuer, policy } => drop(self.policy(*issuer, policy)),
            Step::CreateAgent { node, agent } => drop(self.create_agent(*node, agent)),
            Step::Turn {
                node,
                agent,
                conv,
                n,
            } => drop(self.turn(*node, agent, conv, *n)),
            Step::ToolCall {
                node,
                agent,
                key,
                finish,
            } => drop(self.tool_call(*node, agent, "conv-1", key, *finish)),
            Step::Move { from, agent, to } => drop(self.move_agent(*from, agent, *to)),
            Step::Claim {
                node,
                agent,
                reason,
            } => drop(self.claim(*node, agent, *reason)),
            Step::Crash(node) => self.crash(*node),
            Step::CrashAt(node, point) => self.crash_at(*node, *point),
            Step::Restart(node) => self.restart(*node),
            Step::Partition(groups) => {
                let groups: Vec<&[u8]> = groups.iter().map(Vec::as_slice).collect();
                self.partition(&groups);
            }
            Step::Sever(a, b) => self.sever(*a, *b),
            Step::Heal => self.heal(),
            Step::Advance(ms) => self.advance(*ms).await,
            Step::DropRate(p) => self.drop_rate(*p),
        }
    }

    // ----- inspection -----

    /// The id of node `name`.
    #[must_use]
    pub fn id(&self, name: u8) -> NodeId {
        node_id(name)
    }

    /// Whether `node` is up.
    #[must_use]
    pub fn is_up(&self, node: u8) -> bool {
        self.world.node(node).is_up()
    }

    /// `agent`'s placement record at `node`.
    #[must_use]
    pub fn record(&self, node: u8, agent: &str) -> Option<PlacementRecord> {
        actor::record(self.world.node(node), log_id(&AgentId::new(agent)))
    }

    /// The node names that pass the F4 gate for `agent` (and finished their startup probe).
    #[must_use]
    pub fn holders(&self, agent: &str) -> Vec<u8> {
        let log = log_id(&AgentId::new(agent));
        self.world
            .nodes
            .iter()
            .filter(|(_, n)| n.is_up() && n.st.borrow().resumed)
            .filter(|(_, n)| actor::record(n, log).is_some_and(|r| r.may_append(&n.id)))
            .map(|(name, _)| *name)
            .collect()
    }

    /// The analysis of `agent`'s log at `node` (canonical line, segments, divergences).
    #[must_use]
    pub fn analysis(&self, node: u8, agent: &str) -> Analysis {
        let n = self.world.node(node);
        let judge = Rc::clone(&n.judge);
        let log = log_id(&AgentId::new(agent));
        n.store()
            .read(|txn| core_log::AgentLog::new(log, &*judge).analyze(txn))
            .unwrap_or_default()
    }

    /// The decoded payloads of `agent`'s canonical line at `node`, with their seq.
    #[must_use]
    pub fn canonical_payloads(&self, node: u8, agent: &str) -> Vec<(u64, Option<Payload>)> {
        let n = self.world.node(node);
        self.analysis(node, agent)
            .line
            .iter()
            .map(|e| (e.seq, actor::payload(n, e)))
            .collect()
    }

    /// The payload of one stored entry at `node`.
    #[must_use]
    pub fn payload_of(&self, node: u8, entry: &core_store::StoredEntry) -> Option<Payload> {
        actor::payload(self.world.node(node), entry)
    }

    /// The materialized files of `agent`'s canonical line at `node`.
    #[must_use]
    pub fn materialize(&self, node: u8, agent: &str) -> core_log::fold::StateFold {
        let n = self.world.node(node);
        actor::fold_line(n, &self.analysis(node, agent).line).0
    }

    /// How many entries `node` stores for `agent`.
    #[must_use]
    pub fn entry_count(&self, node: u8, agent: &str) -> usize {
        let log = log_id(&AgentId::new(agent));
        self.world
            .node(node)
            .store()
            .read(|txn| {
                let mut n = 0;
                for segment in txn.segments(&log)? {
                    n += txn
                        .segment_entries(&log, &segment.epoch, 0, usize::MAX)?
                        .len();
                }
                Ok::<_, StoreError>(n)
            })
            .unwrap_or(0)
    }

    /// Runs `f` on `node`'s store.
    ///
    /// # Errors
    ///
    /// Whatever `f` returns.
    pub fn with_store<T>(&self, node: u8, f: impl FnOnce(&MemoryStore) -> T) -> T {
        f(&self.world.node(node).store())
    }

    /// The log id of `agent`.
    #[must_use]
    pub fn log_id(agent: &str) -> LogId {
        log_id(&AgentId::new(agent))
    }

    /// The membership view at `node`.
    #[must_use]
    pub fn view(&self, node: u8) -> Option<MembershipView> {
        self.world.node(node).judge.view()
    }

    /// What `node` sent on its log lanes.
    #[must_use]
    pub fn frames(&self, node: u8) -> FrameCounts {
        self.world.node(node).frame_counts()
    }

    /// The node's notes on moves, claims and divergences, in order.
    #[must_use]
    pub fn notes(&self, node: u8) -> Vec<String> {
        self.world.node(node).st.borrow().move_log.clone()
    }

    /// The wire codes of entries `node` refused on its sessions.
    #[must_use]
    pub fn rejected(&self, node: u8) -> Vec<&'static str> {
        self.world.node(node).st.borrow().rejected.clone()
    }

    /// Whether `node` finished its startup probe.
    #[must_use]
    pub fn resumed(&self, node: u8) -> bool {
        self.world.node(node).st.borrow().resumed
    }

    /// Whether a dial from `from` to `to` on `alpn` is admitted (the access hook, M8).
    pub async fn admitted(&self, from: u8, to: u8, alpn: &[u8]) -> bool {
        use core_carrier::Carrier as _;
        match self
            .world
            .carrier(from)
            .dial(&core_carrier::NodeAddr::new(node_id(to)), alpn)
            .await
        {
            Ok(dialed) => {
                dialed.link.close(core_carrier::AppClose::closed());
                true
            }
            Err(_) => false,
        }
    }
}
