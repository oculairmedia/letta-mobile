//! Shared helpers: test keys, a membership builder, log entry builders, a replica judged by
//! membership, and the runner for the placement evaluation fixture's scripts.

#![allow(dead_code, unreachable_pub)]

use std::collections::{BTreeMap, BTreeSet};

use core_log::hashes::{log_id, payload_hash};
use core_log::payload::kind;
use core_log::{
    AgentLog, ClaimContext, EntryHeader, EpochOpen, EpochSeal, Evaluation, Incoming, Payload, Pos,
    Reason, Received, SignedEntry,
};
use core_membership::op::rotate_proof;
use core_membership::{
    CatalogRoot, MembershipLog, MembershipView, NodeKey, Op, OpHeader, OpId, OpPayload, Preset,
    SignedOp,
};
use core_placement::vote::{ClaimVote, SignedVote};
use core_placement::{ClaimVerdict, MembershipJudge};
use core_proto::bcbor::{self, Value};
use core_proto::{AgentId, Epoch, NodeId};
use core_store::{EntryHash, LogId, LogStore, MemoryStore, Store, StoreError};
use serde_json::Value as Json;

pub const AGENT: &str = "agent-alpha";

/// Test key `Nk`: seed `k` repeated 32 times.
pub fn key(k: u8) -> NodeKey {
    NodeKey::from_seed(&[k; 32])
}

pub fn node(k: u8) -> NodeId {
    key(k).node_id()
}

/// `"N3"` → 3.
pub fn num(name: &str) -> u8 {
    name.trim_start_matches('N').parse().expect("node name")
}

pub fn named(name: &str) -> NodeId {
    node(num(name))
}

pub fn agent() -> AgentId {
    AgentId::new(AGENT)
}

pub fn log() -> LogId {
    log_id(&agent())
}

pub fn epoch(n: u64, k: u8) -> Epoch {
    Epoch {
        n,
        claimant: node(k),
    }
}

pub fn preset(name: &str) -> Preset {
    match name {
        "first_device" => Preset::FirstDevice,
        "desktop_node" => Preset::DesktopNode,
        "always_on_linux" => Preset::AlwaysOnLinux,
        "phone" => Preset::Phone { execution: false },
        "phone_exec" => Preset::Phone { execution: true },
        "read_only" => Preset::ReadOnly,
        other => panic!("unknown preset {other}"),
    }
}

/// A linear chain of membership ops; N1 founds the constellation with every capability.
pub struct Members {
    log: MembershipLog,
    constellation: OpId,
    last: OpId,
    ts: u64,
}

impl Default for Members {
    fn default() -> Self {
        Self::new()
    }
}

impl Members {
    pub fn new() -> Self {
        let op = Op {
            header: OpHeader {
                constellation: None,
                issuer: node(1),
                parents: BTreeSet::new(),
                ts_ms: 1,
            },
            payload: OpPayload::Genesis {
                caps: Preset::FirstDevice.capabilities(),
                name: None,
                label: None,
                catalog_root: CatalogRoot([0; 16]),
            },
            extensions: BTreeMap::new(),
        };
        let signed = SignedOp::sign(&op, &key(1)).expect("genesis");
        let id = signed.id();
        let mut log = MembershipLog::new(id);
        log.insert(signed).expect("insert genesis");
        Self {
            log,
            constellation: id,
            last: id,
            ts: 1,
        }
    }

    fn push(&mut self, issuer: u8, payload: OpPayload) -> &mut Self {
        self.ts += 1;
        let op = Op {
            header: OpHeader {
                constellation: Some(self.constellation),
                issuer: node(issuer),
                parents: BTreeSet::from([self.last]),
                ts_ms: self.ts,
            },
            payload,
            extensions: BTreeMap::new(),
        };
        let signed = SignedOp::sign(&op, &key(issuer)).expect("sign");
        self.last = signed.id();
        self.log.insert(signed).expect("insert");
        self
    }

    pub fn add(&mut self, k: u8, preset: Preset) -> &mut Self {
        self.push(
            1,
            OpPayload::Add {
                subject: node(k),
                caps: preset.capabilities(),
                name: None,
            },
        )
    }

    pub fn revoke(&mut self, k: u8) -> &mut Self {
        self.push(
            1,
            OpPayload::Revoke {
                subject: node(k),
                reason: None,
            },
        )
    }

    pub fn rotate(&mut self, from: u8, to: u8) -> &mut Self {
        let sig = rotate_proof(&key(to), &node(from), &self.constellation);
        self.push(
            from,
            OpPayload::Rotate {
                new_key: node(to),
                new_key_sig: sig,
            },
        )
    }

    pub fn policy(&mut self, policy: Value) -> &mut Self {
        self.push(1, OpPayload::PolicySet { policy })
    }

    pub fn view(&self) -> MembershipView {
        let evaluation = self.log.evaluate();
        assert!(
            evaluation.rejections.is_empty(),
            "every test op takes effect: {:?}",
            evaluation.rejections
        );
        evaluation.view
    }

    /// Applies one fixture op.
    pub fn apply(&mut self, op: &Json) -> &mut Self {
        match op["op"].as_str().expect("op") {
            "add" => self.add(
                num(op["node"].as_str().expect("node")),
                preset(op["preset"].as_str().expect("preset")),
            ),
            "revoke" => self.revoke(num(op["node"].as_str().expect("node"))),
            "rotate" => self.rotate(
                num(op["node"].as_str().expect("node")),
                num(op["to"].as_str().expect("to")),
            ),
            "policy" => self.policy(policy_value(&op["policy"])),
            other => panic!("unknown op {other}"),
        }
    }
}

/// A policy map from the fixture's JSON form.
pub fn policy_value(json: &Json) -> Value {
    let mut map = BTreeMap::new();
    let names = |list: &Json| -> Vec<Value> {
        list.as_array()
            .expect("list")
            .iter()
            .map(|n| Value::Bytes(named(n.as_str().expect("name")).as_bytes().to_vec()))
            .collect()
    };
    if let Some(mode) = json.get("mode") {
        map.insert(0, Value::Uint(u64::from(mode.as_str() != Some("manual"))));
    }
    for (key, field) in [(1, "after_ms"), (2, "stagger_ms")] {
        if let Some(v) = json.get(field) {
            map.insert(key, Value::Uint(v.as_u64().expect("uint")));
        }
    }
    if let Some(list) = json.get("candidates") {
        map.insert(3, Value::Array(names(list)));
    }
    if let Some(v) = json.get("allow_phone_auto") {
        map.insert(4, Value::Bool(v.as_bool().expect("bool")));
    }
    if let Some(quorum) = json.get("quorum") {
        let mut q = BTreeMap::new();
        q.insert(0, bcbor::sorted_set(names(&quorum["voters"])));
        if let Some(required) = quorum.get("required") {
            q.insert(1, Value::Uint(required.as_u64().expect("required")));
        }
        map.insert(5, Value::Map(q));
    }
    if let Some(v) = json.get("indirect_probe") {
        map.insert(6, Value::Bool(v.as_bool().expect("bool")));
    }
    if let Some(v) = json.get("runtime_mismatch_failover") {
        map.insert(10, Value::Bool(v.as_bool().expect("bool")));
    }
    Value::Map(map)
}

/// A built entry with its payload bytes.
#[derive(Clone, Debug)]
pub struct Built {
    pub entry: SignedEntry,
    pub payload: Vec<u8>,
}

impl Built {
    pub fn pos(&self) -> Pos {
        Pos {
            seq: self.entry.header().seq,
            hash: self.entry.hash(),
        }
    }

    pub fn epoch(&self) -> Epoch {
        self.entry.header().epoch
    }

    pub fn is_open(&self) -> bool {
        self.entry.header().kind == kind::EPOCH_OPEN
    }
}

/// Signs an entry by key `k` in epoch `(n, k)` following `prev`.
pub fn build(k: u8, n: u64, prev: Option<&Built>, payload: &Payload, ts_ms: u64) -> Built {
    let bytes = payload.encode();
    let header = EntryHeader {
        log_id: log(),
        epoch: epoch(n, k),
        seq: prev.map_or(0, |p| p.entry.header().seq + 1),
        prev: prev.map_or(EntryHash::ZERO, |p| p.entry.hash()),
        kind: payload.kind(),
        flags: payload.default_flags(),
        ts_ms,
        payload_len: bytes.len() as u64,
        payload_hash: payload_hash(&bytes),
    };
    Built {
        entry: SignedEntry::sign(header, &key(k)).expect("signs"),
        payload: bytes,
    }
}

pub const PARITY: &str = "letta-code@0.0.0+lcp:00";

pub fn genesis_payload() -> Payload {
    Payload::EpochOpen(EpochOpen {
        agent_id: Some(agent()),
        reason: Reason::Genesis,
        prior_epoch: None,
        votes: Vec::new(),
        store_format: 1,
        runtime_parity: Some(PARITY.into()),
        prior_head_seen: None,
        uncertain: Vec::new(),
    })
}

pub fn open_payload(reason: Reason, prior: Epoch, votes: Vec<Value>) -> Payload {
    Payload::EpochOpen(EpochOpen {
        agent_id: None,
        reason,
        prior_epoch: Some(prior),
        votes,
        store_format: 1,
        runtime_parity: Some(PARITY.into()),
        prior_head_seen: None,
        uncertain: Vec::new(),
    })
}

pub fn seal_payload(next: Option<Epoch>, checkpoint: Pos) -> Payload {
    Payload::EpochSeal(EpochSeal {
        next_epoch: next,
        checkpoint,
    })
}

pub fn append_payload(offset: u64, text: &str) -> Payload {
    Payload::FileAppend {
        path: "conversations/conv-1/messages.jsonl".into(),
        offset,
        bytes: text.as_bytes().to_vec(),
    }
}

/// A vote by `voter` for `(log, epoch, parent)`.
pub fn vote(voter: u8, epoch: Epoch, parent: Pos) -> Value {
    SignedVote::sign(
        ClaimVote {
            log_id: log(),
            epoch,
            parent,
            voter: node(voter),
            ts_ms: 7,
        },
        &key(voter),
    )
    .expect("vote")
    .to_value()
}

pub fn to_store(error: core_log::LogError) -> StoreError {
    match error {
        core_log::LogError::Store(error) => error,
        other => StoreError::Invalid(other.to_string()),
    }
}

/// A replica: a store plus a pending buffer, judged by membership.
pub struct Replica {
    pub store: MemoryStore,
    pending: BTreeMap<EntryHash, Vec<Built>>,
}

impl Default for Replica {
    fn default() -> Self {
        Self::new()
    }
}

impl Replica {
    pub fn new() -> Self {
        Self {
            store: MemoryStore::new(),
            pending: BTreeMap::new(),
        }
    }

    /// Delivers one entry (and any waiting on it), then evaluates under `view`.
    pub fn deliver(&mut self, built: &Built, view: &MembershipView) -> Evaluation {
        let agent = agent();
        let judge = MembershipJudge::new(view, Some(&agent));
        let pending = &mut self.pending;
        self.store
            .write(|txn| {
                let log = AgentLog::new(log(), &judge);
                let mut stack = vec![built.clone()];
                while let Some(b) = stack.pop() {
                    match log
                        .receive(txn, &b.entry, &Incoming::Inline(b.payload.clone()))
                        .map_err(to_store)?
                    {
                        Received::Pending { missing } => {
                            pending.entry(missing).or_default().push(b);
                        }
                        Received::Stored { .. } => {
                            if let Some(children) = pending.remove(&b.entry.hash()) {
                                stack.extend(children);
                            }
                        }
                        _ => {}
                    }
                }
                log.evaluate(txn).map_err(to_store)
            })
            .expect("deliver")
    }

    /// Re-evaluates under a (changed) view.
    pub fn evaluate(&self, view: &MembershipView) -> Evaluation {
        let agent = agent();
        let judge = MembershipJudge::new(view, Some(&agent));
        self.store
            .write(|txn| AgentLog::new(log(), &judge).evaluate(txn).map_err(to_store))
            .expect("evaluate")
    }

    pub fn record(&self, view: &MembershipView) -> Option<core_placement::PlacementRecord> {
        let agent = agent();
        let judge = MembershipJudge::new(view, Some(&agent));
        self.store
            .read(|txn| {
                core_placement::PlacementRecord::derive(&AgentLog::new(log(), &judge), txn)
                    .map_err(to_store)
            })
            .expect("record")
    }

    pub fn analysis(&self, view: &MembershipView) -> core_log::Analysis {
        let agent = agent();
        let judge = MembershipJudge::new(view, Some(&agent));
        self.store
            .read(|txn| AgentLog::new(log(), &judge).analyze(txn).map_err(to_store))
            .expect("analysis")
    }
}

/// The judge's verdict on one built `epoch_open` whose parent is `parent`.
pub fn verdict(view: &MembershipView, open: &Built, parent: Option<&Built>) -> ClaimVerdict {
    let agent = agent();
    let judge = MembershipJudge::new(view, Some(&agent));
    let Ok(Payload::EpochOpen(payload)) = Payload::decode(kind::EPOCH_OPEN, &open.payload) else {
        panic!("not an open");
    };
    let parent_stored = parent.map(|p| p.entry.to_stored(core_store::PayloadState::Stored));
    let parent_payload =
        parent.and_then(|p| Payload::decode(p.entry.header().kind, &p.payload).ok());
    judge.verdict(&ClaimContext {
        log_id: log(),
        open: &open.entry,
        payload: &payload,
        parent: parent_stored.as_ref(),
        parent_payload: parent_payload.as_ref(),
    })
}

/// A fixture script's log: entries by id, in script order.
pub struct Script {
    pub entries: BTreeMap<String, Built>,
    pub order: Vec<String>,
    pub parents: BTreeMap<String, String>,
}

/// Builds the entries of a script's `log` steps.
pub fn build_script(steps: &[Json]) -> Script {
    let mut script = Script {
        entries: BTreeMap::new(),
        order: Vec::new(),
        parents: BTreeMap::new(),
    };
    let mut ts = 100;
    for step in steps {
        ts += 1;
        let id = step["id"].as_str().expect("id").to_owned();
        let built = if let Some(by) = step.get("genesis") {
            build(
                num(by.as_str().expect("genesis")),
                1,
                None,
                &genesis_payload(),
                ts,
            )
        } else if let Some(prev) = step.get("append") {
            let prev_id = prev.as_str().expect("append");
            let p = &script.entries[prev_id];
            let e = p.epoch();
            script.parents.insert(id.clone(), prev_id.to_owned());
            build(
                claimant_num(&e),
                e.n,
                Some(p),
                &append_payload(p.entry.header().seq, &id),
                ts,
            )
        } else if let Some(prev) = step.get("seal") {
            let prev_id = prev.as_str().expect("seal");
            let p = &script.entries[prev_id];
            let e = p.epoch();
            let next = step.get("next").map(|n| {
                epoch(
                    n[0].as_u64().expect("n"),
                    num(n[1].as_str().expect("claimant")),
                )
            });
            script.parents.insert(id.clone(), prev_id.to_owned());
            build(
                claimant_num(&e),
                e.n,
                Some(p),
                &seal_payload(next, p.pos()),
                ts,
            )
        } else {
            let by = num(step["open"].as_str().expect("open"));
            let n = step["n"].as_u64().expect("n");
            let parent_id = step["parent"].as_str().expect("parent");
            let p = &script.entries[parent_id];
            let reason = match step["reason"].as_str().expect("reason") {
                "move" => Reason::Move,
                "failover" => Reason::Failover,
                "takeover" => Reason::Takeover,
                "takeover_forced" => Reason::TakeoverForced,
                "rekey" => Reason::Rekey,
                other => panic!("unknown reason {other}"),
            };
            let vote_parent = step.get("vote_parent").map_or_else(
                || p.pos(),
                |v| script.entries[v.as_str().expect("id")].pos(),
            );
            let votes = step
                .get("votes")
                .and_then(Json::as_array)
                .map(|vs| {
                    vs.iter()
                        .map(|v| vote(num(v.as_str().expect("voter")), epoch(n, by), vote_parent))
                        .collect()
                })
                .unwrap_or_default();
            script.parents.insert(id.clone(), parent_id.to_owned());
            build(by, n, Some(p), &open_payload(reason, p.epoch(), votes), ts)
        };
        script.entries.insert(id.clone(), built);
        script.order.push(id);
    }
    script
}

fn claimant_num(epoch: &Epoch) -> u8 {
    (1..=32)
        .find(|k| node(*k) == epoch.claimant)
        .expect("a test key")
}

pub fn name_of(node_id: &NodeId) -> String {
    format!(
        "N{}",
        (1..=32_u8)
            .find(|k| node(*k) == *node_id)
            .expect("a test key")
    )
}

pub fn epoch_json(e: &Epoch) -> Json {
    serde_json::json!([e.n, name_of(&e.claimant)])
}

/// Runs `f` against `store`'s state through a read transaction.
pub fn read<T>(
    store: &MemoryStore,
    f: impl FnOnce(&dyn core_store::Txn) -> Result<T, StoreError>,
) -> T {
    store.read(f).expect("read")
}

pub fn max_n(store: &MemoryStore) -> u64 {
    read(store, |txn| LogStore::max_n_seen(txn, &log())).unwrap_or(0)
}
