//! Shared helpers for the log tests: fixed test keys, entry builders, and a replica that buffers
//! out-of-order entries.

#![allow(dead_code, unreachable_pub)]

pub mod scenarios;

use std::collections::BTreeMap;

use core_log::hashes::{log_id, payload_hash};
use core_log::payload::kind;
use core_log::{
    AgentLog, EntryHeader, EpochOpen, EpochSeal, Evaluation, Incoming, Payload, Pos, Reason,
    Received, SignedEntry, StructuralJudge,
};
use core_proto::crypto::NodeKey;
use core_proto::{AgentId, ConversationId, Epoch, NodeId};
use core_store::{EntryHash, LogId, MemoryStore, Store, StoreError};

pub const AGENT: &str = "agent-alpha";

/// Test key `Nk`: seed `k` repeated 32 times.
pub fn key(k: u8) -> NodeKey {
    NodeKey::from_seed(&[k; 32])
}

pub fn node(k: u8) -> NodeId {
    key(k).node_id()
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
}

/// Signs an entry by key `k` in epoch `(n, k)` following `prev`, with `payload`.
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

pub fn genesis_payload() -> Payload {
    Payload::EpochOpen(EpochOpen {
        agent_id: Some(agent()),
        reason: Reason::Genesis,
        prior_epoch: None,
        votes: Vec::new(),
        store_format: 1,
        runtime_parity: Some("letta-code@0.0.0+lcp:00".into()),
        prior_head_seen: None,
        uncertain: Vec::new(),
    })
}

pub fn open_payload(reason: Reason, prior: Epoch) -> Payload {
    Payload::EpochOpen(EpochOpen {
        agent_id: None,
        reason,
        prior_epoch: Some(prior),
        votes: Vec::new(),
        store_format: 1,
        runtime_parity: Some("letta-code@0.0.0+lcp:00".into()),
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

pub fn append_payload(conversation: &str, offset: u64, text: &str) -> Payload {
    Payload::FileAppend {
        path: format!("conversations/{conversation}/messages.jsonl"),
        offset,
        bytes: text.as_bytes().to_vec(),
    }
}

pub fn feed_payload(conversation: &str, feed_seq: u64, turn: &str, entry_kind: &str) -> Payload {
    Payload::FeedAppend {
        conversation_id: ConversationId::new(conversation),
        feed_seq,
        turn: turn.into(),
        entry_kind: entry_kind.into(),
        entry: format!("{{\"seq\":{feed_seq}}}").into_bytes(),
        replaces_feed_seq: None,
    }
}

/// A replica: a store plus a pending buffer, fed entries in any order.
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

    /// Delivers one entry (and any entries waiting on it), then evaluates.
    pub fn deliver(&mut self, built: &Built) -> Evaluation {
        self.deliver_with(built, &Incoming::Inline(built.payload.clone()))
    }

    pub fn deliver_with(&mut self, built: &Built, incoming: &Incoming) -> Evaluation {
        let judge = StructuralJudge;
        let pending = &mut self.pending;
        self.store
            .write(|txn| {
                let log = AgentLog::new(log(), &judge);
                let mut stack = vec![(built.clone(), incoming.clone())];
                while let Some((b, inc)) = stack.pop() {
                    match log.receive(txn, &b.entry, &inc).map_err(to_store)? {
                        Received::Pending { missing } => {
                            pending.entry(missing).or_default().push(b);
                        }
                        Received::Stored { .. } => {
                            if let Some(children) = pending.remove(&b.entry.hash()) {
                                stack.extend(children.into_iter().map(|c| {
                                    let p = Incoming::Inline(c.payload.clone());
                                    (c, p)
                                }));
                            }
                        }
                        _ => {}
                    }
                }
                log.evaluate(txn).map_err(to_store)
            })
            .expect("deliver")
    }

    pub fn pending_len(&self) -> usize {
        self.pending.values().map(Vec::len).sum()
    }
}

pub fn to_store(error: core_log::LogError) -> StoreError {
    match error {
        core_log::LogError::Store(error) => error,
        other => StoreError::Invalid(other.to_string()),
    }
}

pub fn is_open(built: &Built) -> bool {
    built.entry.header().kind == kind::EPOCH_OPEN
}
