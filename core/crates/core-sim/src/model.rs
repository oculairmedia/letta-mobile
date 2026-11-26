//! The simulated world's vocabulary: test keys, node classes, the failover policy as the map a
//! `policy_set` op carries, and the constants every node shares.

use std::collections::BTreeMap;

use core_membership::NodeKey;
use core_placement::NodeClass;
use core_proto::NodeId;
use core_proto::bcbor::{self, Value};

/// The runtime parity every simulated node reports (I6 matches everywhere).
pub const PARITY: &str = "letta-code@0.0.0+lcp:00";

/// The store format every simulated node reports.
pub const STORE_FORMAT: u64 = 1;

/// The ALPN of the replication protocol.
pub const LOG_ALPN: &[u8] = b"baken/log/1";

/// The ALPN membership ops travel on. Non-members reach it (the bounded push path).
pub const MEMBER_ALPN: &[u8] = b"baken/member/1";

/// The ALPN of the placement protocol.
pub const PLACE_ALPN: &[u8] = b"baken/place/1";

/// How often every node's actor runs (virtual milliseconds).
pub const TICK_MS: u64 = 1_000;

/// How long a dialler waits before redialling a peer after a session ended or a dial failed.
pub const REDIAL_MS: u64 = 1_000;

/// The holder's startup probe window (C1 §5.6).
pub const STARTUP_PROBE_MS: u64 = 10_000;

/// How often a candidate may probe an unreachable holder.
pub const PROBE_INTERVAL_MS: u64 = 5_000;

/// A node of the simulation: test key `name` (seed `name` repeated 32 times), and its class.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct NodeSpec {
    /// The node's name; its key is seeded with this byte.
    pub name: u8,
    /// The self-reported class (node.info).
    pub class: NodeClass,
}

impl NodeSpec {
    /// A desktop node.
    #[must_use]
    pub const fn desktop(name: u8) -> Self {
        Self {
            name,
            class: NodeClass::Desktop,
        }
    }

    /// An always-on node.
    #[must_use]
    pub const fn always_on(name: u8) -> Self {
        Self {
            name,
            class: NodeClass::AlwaysOn,
        }
    }

    /// A phone.
    #[must_use]
    pub const fn phone(name: u8) -> Self {
        Self {
            name,
            class: NodeClass::Phone,
        }
    }
}

/// The key of node `name`.
#[must_use]
pub fn key(name: u8) -> NodeKey {
    NodeKey::from_seed(&[name; 32])
}

/// The id of node `name`.
#[must_use]
pub fn node_id(name: u8) -> NodeId {
    key(name).node_id()
}

/// A failover policy, built as the BCBOR map a `policy_set` op carries (C1 §5.7). Unset knobs take
/// the protocol defaults.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Policy {
    /// Key 0: `false` is manual.
    pub manual: bool,
    /// Key 1.
    pub after_ms: Option<u64>,
    /// Key 2.
    pub stagger_ms: Option<u64>,
    /// Key 3, by node name.
    pub candidates: Vec<u8>,
    /// Key 4.
    pub allow_phone_auto: bool,
    /// Key 5: the voters by node name, and `required`.
    pub quorum: Option<(Vec<u8>, Option<u64>)>,
    /// Key 6.
    pub indirect_probe: Option<bool>,
    /// Key 7.
    pub self_fence_ms: Option<u64>,
    /// Key 9.
    pub resume_alone: Option<bool>,
}

impl Policy {
    /// An automatic policy with this window and stagger.
    #[must_use]
    pub fn auto(after_ms: u64, stagger_ms: u64) -> Self {
        Self {
            after_ms: Some(after_ms),
            stagger_ms: Some(stagger_ms),
            ..Self::default()
        }
    }

    /// The `policy_set` value.
    #[must_use]
    pub fn to_value(&self) -> Value {
        let names = |list: &[u8]| -> Vec<Value> {
            list.iter()
                .map(|n| Value::Bytes(node_id(*n).as_bytes().to_vec()))
                .collect()
        };
        let mut map = BTreeMap::new();
        map.insert(0, Value::Uint(u64::from(!self.manual)));
        if let Some(v) = self.after_ms {
            map.insert(1, Value::Uint(v));
        }
        if let Some(v) = self.stagger_ms {
            map.insert(2, Value::Uint(v));
        }
        if !self.candidates.is_empty() {
            map.insert(3, Value::Array(names(&self.candidates)));
        }
        if self.allow_phone_auto {
            map.insert(4, Value::Bool(true));
        }
        if let Some((voters, required)) = &self.quorum {
            let mut q = BTreeMap::new();
            q.insert(0, bcbor::sorted_set(names(voters)));
            if let Some(required) = required {
                q.insert(1, Value::Uint(*required));
            }
            map.insert(5, Value::Map(q));
        }
        if let Some(v) = self.indirect_probe {
            map.insert(6, Value::Bool(v));
        }
        if let Some(v) = self.self_fence_ms {
            map.insert(7, Value::Uint(v));
        }
        if let Some(v) = self.resume_alone {
            map.insert(9, Value::Bool(v));
        }
        Value::Map(map)
    }
}
