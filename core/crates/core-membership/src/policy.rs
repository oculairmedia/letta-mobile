//! The failover policy carried by `policy_set` ops (C1 section 5.7), as a typed view with defaults.
//!
//! The membership crate only decodes and validates the policy; placement interprets it.

use std::collections::BTreeMap;

use core_proto::{AgentId, NodeId};
use thiserror::Error;

use core_proto::bcbor::{self, Value};

/// Whether failover happens automatically.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FailoverMode {
    /// Only a user-initiated takeover moves a placement.
    Manual,
    /// A candidate claims after the holder has been unreachable for `after_ms`.
    Auto,
}

/// An optional majority-acknowledgement voter set.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Quorum {
    /// The voters, sorted by node id.
    pub voters: Vec<NodeId>,
    /// Acknowledgements required; at least a majority of `voters`.
    pub required: u64,
}

/// A per-agent override of the mode and candidate list.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct AgentOverride {
    /// The agent's mode, when overridden.
    pub mode: Option<FailoverMode>,
    /// The agent's candidates, when overridden.
    pub candidates: Option<Vec<NodeId>>,
}

/// The failover policy, with every absent key at its default.
#[derive(Clone, Debug, PartialEq, Eq)]
// One field per wire knob; the booleans are independent switches, not a hidden state machine.
#[allow(clippy::struct_excessive_bools)]
pub struct FailoverPolicy {
    /// Key 0. Default auto.
    pub mode: FailoverMode,
    /// Key 1: how long the holder must be unreachable before an automatic claim. Default 300,000.
    pub after_ms: u64,
    /// Key 2: added per candidate rank. Default 30,000.
    pub stagger_ms: u64,
    /// Key 3: ordered candidates; empty means derived from node classes. Default empty.
    pub candidates: Vec<NodeId>,
    /// Key 4: a phone may be an automatic candidate. Default false.
    pub allow_phone_auto: bool,
    /// Key 5: optional voter set. Default none.
    pub quorum: Option<Quorum>,
    /// Key 6: require that no reachable peer heard from the holder within `after_ms`. Default true.
    pub indirect_probe: bool,
    /// Key 7: with a quorum, the holder stops new turns after this long without a majority.
    /// Default `after_ms - 60,000` (saturating).
    pub self_fence_ms: u64,
    /// Key 8. Default 120,000.
    pub quiesce_timeout_ms: u64,
    /// Key 9: default true without a quorum, false with one.
    pub resume_alone: bool,
    /// Key 10: a candidate with different runtime parity may still claim by failover. Default false.
    pub runtime_mismatch_failover: bool,
    /// Key 11: per-agent overrides. Default empty.
    pub per_agent: BTreeMap<AgentId, AgentOverride>,
}

impl Default for FailoverPolicy {
    fn default() -> Self {
        Self {
            mode: FailoverMode::Auto,
            after_ms: 300_000,
            stagger_ms: 30_000,
            candidates: Vec::new(),
            allow_phone_auto: false,
            quorum: None,
            indirect_probe: true,
            self_fence_ms: 240_000,
            quiesce_timeout_ms: 120_000,
            resume_alone: true,
            runtime_mismatch_failover: false,
            per_agent: BTreeMap::new(),
        }
    }
}

/// Why a policy map does not decode.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
pub enum PolicyError {
    /// The policy is not a map.
    #[error("policy is not a map")]
    NotAMap,
    /// A reserved key (below 64) that this version does not define.
    #[error("unknown reserved policy key {0}")]
    UnknownKey(u64),
    /// A key holds a value of the wrong type or out of range.
    #[error("policy key {0} has a bad value")]
    BadValue(u64),
    /// A quorum with fewer than three voters, or `required` below a majority or above the voters.
    #[error("quorum needs three or more voters and a majority or more required")]
    BadQuorum,
}

impl FailoverPolicy {
    /// Decodes a policy map.
    ///
    /// # Errors
    ///
    /// [`PolicyError`] when a key is unknown, mistyped or out of range.
    pub fn from_value(value: &Value) -> Result<Self, PolicyError> {
        let Value::Map(map) = value else {
            return Err(PolicyError::NotAMap);
        };
        let mut policy = Self::default();
        let mut self_fence = None;
        let mut resume_alone = None;
        for (&key, item) in map {
            match key {
                0 => policy.mode = mode(item).ok_or(PolicyError::BadValue(0))?,
                1 => policy.after_ms = uint(item, 1)?,
                2 => policy.stagger_ms = uint(item, 2)?,
                3 => policy.candidates = node_list(item, 3)?,
                4 => policy.allow_phone_auto = boolean(item, 4)?,
                5 => policy.quorum = quorum(item)?,
                6 => policy.indirect_probe = boolean(item, 6)?,
                7 => self_fence = Some(uint(item, 7)?),
                8 => policy.quiesce_timeout_ms = uint(item, 8)?,
                9 => resume_alone = Some(boolean(item, 9)?),
                10 => policy.runtime_mismatch_failover = boolean(item, 10)?,
                11 => policy.per_agent = per_agent(item)?,
                64.. => {}
                _ => return Err(PolicyError::UnknownKey(key)),
            }
        }
        policy.self_fence_ms = self_fence.unwrap_or(policy.after_ms.saturating_sub(60_000));
        policy.resume_alone = resume_alone.unwrap_or(policy.quorum.is_none());
        Ok(policy)
    }
}

fn mode(item: &Value) -> Option<FailoverMode> {
    match item {
        Value::Uint(0) => Some(FailoverMode::Manual),
        Value::Uint(1) => Some(FailoverMode::Auto),
        _ => None,
    }
}

fn uint(item: &Value, key: u64) -> Result<u64, PolicyError> {
    match item {
        Value::Uint(n) => Ok(*n),
        _ => Err(PolicyError::BadValue(key)),
    }
}

fn boolean(item: &Value, key: u64) -> Result<bool, PolicyError> {
    match item {
        Value::Bool(b) => Ok(*b),
        _ => Err(PolicyError::BadValue(key)),
    }
}

/// An ordered list of distinct node ids.
fn node_list(item: &Value, key: u64) -> Result<Vec<NodeId>, PolicyError> {
    let Value::Array(items) = item else {
        return Err(PolicyError::BadValue(key));
    };
    let mut nodes = Vec::with_capacity(items.len());
    for entry in items {
        let node = node_id(entry).ok_or(PolicyError::BadValue(key))?;
        if nodes.contains(&node) {
            return Err(PolicyError::BadValue(key));
        }
        nodes.push(node);
    }
    Ok(nodes)
}

pub(crate) fn node_id(item: &Value) -> Option<NodeId> {
    match item {
        Value::Bytes(bytes) => <[u8; 32]>::try_from(bytes.as_slice())
            .ok()
            .map(NodeId::from_bytes),
        _ => None,
    }
}

fn quorum(item: &Value) -> Result<Option<Quorum>, PolicyError> {
    let map = match item {
        Value::Null => return Ok(None),
        Value::Map(map) => map,
        _ => return Err(PolicyError::BadValue(5)),
    };
    if map.keys().any(|key| (2..64).contains(key)) {
        return Err(PolicyError::BadValue(5));
    }
    let voters_value = map.get(&0).ok_or(PolicyError::BadValue(5))?;
    let Value::Array(voter_items) = voters_value else {
        return Err(PolicyError::BadValue(5));
    };
    bcbor::check_sorted_set(voter_items).map_err(|_| PolicyError::BadValue(5))?;
    let voters = node_list(voters_value, 5)?;
    let count = u64::try_from(voters.len()).map_err(|_| PolicyError::BadQuorum)?;
    let majority = count / 2 + 1;
    let required = match map.get(&1) {
        None => majority,
        Some(value) => uint(value, 5)?,
    };
    let required_in_range = (majority..=count).contains(&required);
    if count < 3 || !required_in_range {
        return Err(PolicyError::BadQuorum);
    }
    Ok(Some(Quorum { voters, required }))
}

/// Per-agent overrides: an array of `[agent_id, {0: mode, 3: candidates}]`, strictly ascending by
/// agent id. (A BCBOR map cannot be keyed by text, so the spec's map is an array of pairs.)
fn per_agent(item: &Value) -> Result<BTreeMap<AgentId, AgentOverride>, PolicyError> {
    let bad = PolicyError::BadValue(11);
    let Value::Array(pairs) = item else {
        return Err(bad);
    };
    let mut overrides = BTreeMap::new();
    let mut previous: Option<&str> = None;
    for pair in pairs {
        let Value::Array(parts) = pair else {
            return Err(bad);
        };
        let [Value::Text(agent), Value::Map(fields)] = parts.as_slice() else {
            return Err(bad);
        };
        if previous.is_some_and(|last| last.as_bytes() >= agent.as_bytes()) {
            return Err(bad);
        }
        previous = Some(agent);
        let mut entry = AgentOverride::default();
        for (&key, value) in fields {
            match key {
                0 => entry.mode = Some(mode(value).ok_or(PolicyError::BadValue(11))?),
                3 => entry.candidates = Some(node_list(value, 11)?),
                64.. => {}
                _ => return Err(PolicyError::BadValue(11)),
            }
        }
        overrides.insert(AgentId::new(agent.clone()), entry);
    }
    Ok(overrides)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn map(entries: Vec<(u64, Value)>) -> Value {
        Value::Map(entries.into_iter().collect())
    }

    #[test]
    fn an_empty_map_is_the_default() {
        assert_eq!(
            FailoverPolicy::from_value(&map(vec![])),
            Ok(FailoverPolicy::default())
        );
    }

    #[test]
    fn derived_defaults_follow_their_inputs() {
        let voters: Vec<Value> = (1..=3_u8).map(|b| Value::Bytes(vec![b; 32])).collect();
        let policy = FailoverPolicy::from_value(&map(vec![
            (1, Value::Uint(100_000)),
            (5, map(vec![(0, Value::Array(voters))])),
        ]))
        .expect("valid");
        assert_eq!(policy.self_fence_ms, 40_000);
        assert!(!policy.resume_alone);
        assert_eq!(policy.quorum.expect("quorum").required, 2);
    }

    #[test]
    fn explicit_values_override_derived_defaults() {
        let voters: Vec<Value> = (1..=3_u8).map(|b| Value::Bytes(vec![b; 32])).collect();
        let policy = FailoverPolicy::from_value(&map(vec![
            (1, Value::Uint(100_000)),
            (5, map(vec![(0, Value::Array(voters))])),
            (7, Value::Uint(12_345)),
            (9, Value::Bool(true)),
        ]))
        .expect("valid");
        // Key 7 wins over after_ms - 60000, and key 9 wins over "resume alone only without a quorum".
        assert_eq!(policy.self_fence_ms, 12_345);
        assert!(policy.resume_alone);
    }

    #[test]
    fn the_derived_self_fence_floors_at_zero() {
        for after_ms in [0, 59_999, 60_000] {
            let policy =
                FailoverPolicy::from_value(&map(vec![(1, Value::Uint(after_ms))])).expect("valid");
            assert_eq!(policy.self_fence_ms, 0, "after_ms {after_ms}");
        }
    }

    #[test]
    fn bad_policies_are_rejected() {
        let two_voters: Vec<Value> = (1..=2_u8).map(|b| Value::Bytes(vec![b; 32])).collect();
        assert_eq!(
            FailoverPolicy::from_value(&map(vec![(5, map(vec![(0, Value::Array(two_voters))]))])),
            Err(PolicyError::BadQuorum)
        );
        assert_eq!(
            FailoverPolicy::from_value(&map(vec![(12, Value::Uint(1))])),
            Err(PolicyError::UnknownKey(12))
        );
        assert_eq!(
            FailoverPolicy::from_value(&map(vec![(0, Value::Uint(2))])),
            Err(PolicyError::BadValue(0))
        );
        assert!(FailoverPolicy::from_value(&map(vec![(64, Value::Null)])).is_ok());
    }

    #[test]
    fn per_agent_pairs_must_ascend() {
        let pair = |agent: &str| {
            Value::Array(vec![
                Value::Text(agent.into()),
                map(vec![(0, Value::Uint(0))]),
            ])
        };
        let ok =
            FailoverPolicy::from_value(&map(vec![(11, Value::Array(vec![pair("a"), pair("b")]))]))
                .expect("valid");
        assert_eq!(
            ok.per_agent[&AgentId::new("a")].mode,
            Some(FailoverMode::Manual)
        );
        assert!(
            FailoverPolicy::from_value(&map(vec![(11, Value::Array(vec![pair("b"), pair("a")]))]))
                .is_err()
        );
    }
}
