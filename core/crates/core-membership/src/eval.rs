//! Causal evaluation of a membership DAG (`membership-v1.md` section 6).
//!
//! The result is a pure function of the op set: arrival order, the evaluating node and the order of
//! the input never matter. Rules, for a down-closed set E:
//!
//! - A revoke R is valid iff it is authorized in `S(past(R))`. Its validity never depends on E.
//! - Any other op X is valid in E iff no valid revoke R in E names X's issuer with X outside
//!   `past(R)`, and X is authorized in `S_E(past(X))`: the state of the ops of `past(X)` that are
//!   valid in E. Evaluating the past against E's revokes is what makes the exclusion cascade: an op
//!   whose issuer was admitted by an excluded op is unauthorized too.
//! - `S` applies valid ops: sticky tombstones, remove-wins capabilities, rotation, last-writer-wins
//!   names and policy in the linear order, and the catalog root chain.

use std::collections::{BTreeMap, BTreeSet};

use core_proto::NodeId;
use fixedbitset::FixedBitSet;

use crate::capability::{self, CapSet, Capability};
use crate::crypto::OpId;
use crate::op::{self, CatalogRoot, OpKind, OpPayload, SignedOp};
use crate::policy::FailoverPolicy;
use crate::view::{MemberStatus, MembershipView};

/// Why a held op has no effect. [`Rejection::code`] gives the fixture code.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Rejection {
    /// The op broke its kind's authorization rule in the state of its causal past.
    Unauthorized(Reason),
    /// A valid revoke of the op's issuer did not have the op in its causal past.
    ExcludedByRevoke {
        /// The revoke.
        revoke: OpId,
    },
    /// Some ancestor of the op is not in the evaluated set.
    Incomplete,
    /// The op belongs to another constellation, or is a second genesis.
    ForeignConstellation,
}

impl Rejection {
    /// The stable code the fixtures use: the reason's code, `excluded_by_revoke`, `incomplete` or
    /// `foreign_constellation`.
    #[must_use]
    pub const fn code(&self) -> &'static str {
        match self {
            Self::Unauthorized(reason) => reason.code(),
            Self::ExcludedByRevoke { .. } => "excluded_by_revoke",
            Self::Incomplete => "incomplete",
            Self::ForeignConstellation => "foreign_constellation",
        }
    }
}

/// The authorization rule an op broke.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Reason {
    /// A genesis whose capabilities lack `admin.full`.
    GenesisWithoutAdmin,
    /// No active admin is left, so no op is authorized.
    Locked,
    /// The issuer is not an active member.
    IssuerNotActive,
    /// The issuer lacks the capability the op needs.
    IssuerLacksCapability,
    /// A `devices.approve` add naming capabilities outside the device ceiling.
    CeilingExceeded,
    /// An add whose subject is the issuer.
    SelfAdd,
    /// The subject is tombstoned.
    SubjectTombstoned,
    /// An add whose subject is or was a member.
    SubjectAlreadyMember,
    /// A grant whose subject is not an active member.
    SubjectNotActive,
    /// A grant that adds and removes nothing.
    EmptyGrant,
    /// A grant that adds and removes the same capability.
    OverlappingGrant,
    /// The op would leave no active admin.
    LastAdmin,
    /// A revoke of an id that is already tombstoned.
    AlreadyTombstoned,
    /// A rotate to a key that is or was a member or is tombstoned.
    RotateKeyReused,
    /// A rotate whose proof of possession does not verify.
    BadProofOfPossession,
    /// A policy that does not decode, or a quorum voter without `store` and `node.replicate`.
    PolicyInvalid,
    /// A catalog root move whose `prev_root` is not the current root.
    PrevRootMismatch,
    /// A catalog root move to a root that is or was a root.
    RootReused,
}

impl Reason {
    /// The stable code the fixtures use.
    #[must_use]
    pub const fn code(self) -> &'static str {
        match self {
            Self::GenesisWithoutAdmin => "genesis_without_admin",
            Self::Locked => "locked",
            Self::IssuerNotActive => "issuer_not_active",
            Self::IssuerLacksCapability => "issuer_lacks_capability",
            Self::CeilingExceeded => "ceiling_exceeded",
            Self::SelfAdd => "self_add",
            Self::SubjectTombstoned => "subject_tombstoned",
            Self::SubjectAlreadyMember => "subject_already_member",
            Self::SubjectNotActive => "subject_not_active",
            Self::EmptyGrant => "empty_grant",
            Self::OverlappingGrant => "overlapping_grant",
            Self::LastAdmin => "last_admin",
            Self::AlreadyTombstoned => "already_tombstoned",
            Self::RotateKeyReused => "rotate_key_reused",
            Self::BadProofOfPossession => "bad_proof_of_possession",
            Self::PolicyInvalid => "policy_invalid",
            Self::PrevRootMismatch => "prev_root_mismatch",
            Self::RootReused => "root_reused",
        }
    }
}

/// The result of evaluating a DAG: the view, the valid ops, and why every other op has no effect.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Evaluation {
    /// The evaluated membership state.
    pub view: MembershipView,
    /// The ops that took effect.
    pub valid: BTreeSet<OpId>,
    /// The ops that did not, with the reason.
    pub rejections: BTreeMap<OpId, Rejection>,
    /// Every evaluated op in the linear order (Kahn's order, ties broken by kind class, then id).
    pub linear_order: Vec<OpId>,
}

/// Evaluates `ops` for the constellation whose genesis id is `constellation`.
///
/// The input need not be clean: ops of other constellations and ops with missing ancestors are
/// reported in [`Evaluation::rejections`] and otherwise ignored.
pub fn evaluate<'a>(
    constellation: OpId,
    ops: impl IntoIterator<Item = &'a SignedOp>,
) -> Evaluation {
    let mut rejections = BTreeMap::new();
    let graph = Graph::build(constellation, ops, &mut rejections);
    let mut evaluator = Evaluator {
        graph: &graph,
        revoke_memo: BTreeMap::new(),
    };
    let everything = BitSet::full(graph.len());
    let outcome = evaluator.eval_set(&everything);
    let state = evaluator.build_state(&outcome);

    let mut valid = BTreeSet::new();
    for index in outcome.valid.iter() {
        valid.insert(graph.ops[index].id());
    }
    for (index, rejection) in &outcome.rejections {
        rejections.insert(graph.ops[*index].id(), *rejection);
    }
    let heads = graph.heads();
    Evaluation {
        view: state.into_view(constellation, heads),
        valid,
        rejections,
        linear_order: graph.ops.iter().map(|op| op.id()).collect(),
    }
}

/// A fixed-size set of op indices, over `fixedbitset`.
#[derive(Clone, Debug, PartialEq, Eq)]
struct BitSet(FixedBitSet);

impl BitSet {
    fn empty(len: usize) -> Self {
        Self(FixedBitSet::with_capacity(len))
    }

    fn full(len: usize) -> Self {
        let mut set = FixedBitSet::with_capacity(len);
        set.insert_range(..);
        Self(set)
    }

    fn insert(&mut self, index: usize) {
        self.0.insert(index);
    }

    fn contains(&self, index: usize) -> bool {
        self.0.contains(index)
    }

    fn union_with(&mut self, other: &Self) {
        self.0.union_with(&other.0);
    }

    fn iter(&self) -> impl Iterator<Item = usize> + '_ {
        self.0.ones()
    }
}

/// The evaluated ops in linear order, with their causal pasts.
struct Graph<'a> {
    ops: Vec<&'a SignedOp>,
    past: Vec<BitSet>,
}

impl<'a> Graph<'a> {
    fn build(
        constellation: OpId,
        input: impl IntoIterator<Item = &'a SignedOp>,
        rejections: &mut BTreeMap<OpId, Rejection>,
    ) -> Self {
        let mut candidates: BTreeMap<OpId, &'a SignedOp> = BTreeMap::new();
        for op in input {
            let header = &op.op().header;
            let ours = if op.op().kind() == OpKind::Genesis {
                op.id() == constellation
            } else {
                header.constellation == Some(constellation)
            };
            if ours {
                candidates.insert(op.id(), op);
            } else {
                rejections.insert(op.id(), Rejection::ForeignConstellation);
            }
        }
        // Kahn's algorithm, ready ops ordered by (kind class, id). Ops never reached have an
        // ancestor outside the set.
        let mut waiting: BTreeMap<OpId, usize> = BTreeMap::new();
        let mut children: BTreeMap<OpId, Vec<OpId>> = BTreeMap::new();
        let mut ready: BTreeSet<(u8, OpId)> = BTreeSet::new();
        for (id, op) in &candidates {
            let parents = &op.op().header.parents;
            waiting.insert(*id, parents.len());
            for parent in parents {
                children.entry(*parent).or_default().push(*id);
            }
            if parents.is_empty() {
                ready.insert((op.op().kind().linear_class(), *id));
            }
        }
        let mut ops = Vec::new();
        let mut index_of: BTreeMap<OpId, usize> = BTreeMap::new();
        let mut past: Vec<BitSet> = Vec::new();
        let mut order = Vec::new();
        while let Some(next) = ready.pop_first() {
            let id = next.1;
            order.push(id);
            for child in children.get(&id).into_iter().flatten() {
                if let Some(count) = waiting.get_mut(child) {
                    *count -= 1;
                    if *count == 0 {
                        ready.insert((candidates[child].op().kind().linear_class(), *child));
                    }
                }
            }
        }
        let len = order.len();
        for id in &order {
            let op = candidates[id];
            let mut ancestors = BitSet::empty(len);
            for parent in &op.op().header.parents {
                let parent_index = index_of[parent];
                ancestors.insert(parent_index);
                ancestors.union_with(&past[parent_index]);
            }
            index_of.insert(*id, ops.len());
            ops.push(op);
            past.push(ancestors);
        }
        for id in candidates.keys() {
            if !index_of.contains_key(id) {
                rejections.insert(*id, Rejection::Incomplete);
            }
        }
        Self { ops, past }
    }

    fn len(&self) -> usize {
        self.ops.len()
    }

    fn heads(&self) -> BTreeSet<OpId> {
        let mut heads: BTreeSet<OpId> = self.ops.iter().map(|op| op.id()).collect();
        for op in &self.ops {
            for parent in &op.op().header.parents {
                heads.remove(parent);
            }
        }
        heads
    }
}

/// What a valid rotate passes to its new key: the subject's caps and name in its causal past.
#[derive(Clone, Debug, Default)]
struct Inheritance {
    caps: CapSet,
    name: Option<String>,
}

/// The evaluation of one down-closed set.
struct SetOutcome {
    valid: BitSet,
    rejections: BTreeMap<usize, Rejection>,
    inheritance: BTreeMap<usize, Inheritance>,
}

struct Evaluator<'g, 'a> {
    graph: &'g Graph<'a>,
    /// Revoke validity depends only on the revoke's own past, so it is computed once.
    revoke_memo: BTreeMap<usize, Result<(), Reason>>,
}

impl Evaluator<'_, '_> {
    fn eval_set(&mut self, set: &BitSet) -> SetOutcome {
        let graph = self.graph;
        let members: Vec<usize> = set.iter().collect();
        let mut valid_revokes: Vec<(usize, NodeId)> = Vec::new();
        for &index in &members {
            if let OpPayload::Revoke { subject, .. } = &graph.ops[index].op().payload
                && self.revoke_authorized(index).is_ok()
            {
                valid_revokes.push((index, *subject));
            }
        }
        let mut outcome = SetOutcome {
            valid: BitSet::empty(graph.len()),
            rejections: BTreeMap::new(),
            inheritance: BTreeMap::new(),
        };
        for &index in &members {
            let op = graph.ops[index].op();
            if op.kind() == OpKind::Revoke {
                match self.revoke_authorized(index) {
                    Ok(()) => outcome.valid.insert(index),
                    Err(reason) => {
                        outcome
                            .rejections
                            .insert(index, Rejection::Unauthorized(reason));
                    }
                }
                continue;
            }
            if let Some((revoke, _)) = valid_revokes.iter().find(|(revoke, subject)| {
                *subject == op.header.issuer && !graph.past[*revoke].contains(index)
            }) {
                outcome.rejections.insert(
                    index,
                    Rejection::ExcludedByRevoke {
                        revoke: graph.ops[*revoke].id(),
                    },
                );
                continue;
            }
            let state = self.state_of_past(index, &outcome);
            match authorize(graph.ops[index], &state) {
                Ok(()) => {
                    if op.kind() == OpKind::Rotate {
                        outcome.inheritance.insert(
                            index,
                            Inheritance {
                                caps: state.caps_of(&op.header.issuer).clone(),
                                name: state.names.get(&op.header.issuer).cloned(),
                            },
                        );
                    }
                    outcome.valid.insert(index);
                }
                Err(reason) => {
                    outcome
                        .rejections
                        .insert(index, Rejection::Unauthorized(reason));
                }
            }
        }
        outcome
    }

    fn revoke_authorized(&mut self, index: usize) -> Result<(), Reason> {
        if let Some(result) = self.revoke_memo.get(&index) {
            return *result;
        }
        let past = self.graph.past[index].clone();
        let outcome = self.eval_set(&past);
        let state = self.build_state(&outcome);
        let result = authorize(self.graph.ops[index], &state);
        self.revoke_memo.insert(index, result);
        result
    }

    /// `S_E(past(index))`: the ops of the past that are valid in the set being evaluated. Every
    /// op of the past precedes `index` in the linear order, so its validity is already decided.
    fn state_of_past(&self, index: usize, outcome: &SetOutcome) -> State {
        let mut valid = BitSet::empty(self.graph.len());
        for ancestor in self.graph.past[index].iter() {
            if outcome.valid.contains(ancestor) {
                valid.insert(ancestor);
            }
        }
        self.apply(&valid, &outcome.inheritance)
    }

    fn build_state(&self, outcome: &SetOutcome) -> State {
        self.apply(&outcome.valid, &outcome.inheritance)
    }

    /// Applies the valid ops `valid` in linear order.
    fn apply(&self, valid: &BitSet, inheritance: &BTreeMap<usize, Inheritance>) -> State {
        let graph = self.graph;
        let mut state = State::default();
        let mut sources: BTreeSet<NodeId> = BTreeSet::new();
        let mut rotated: BTreeMap<NodeId, NodeId> = BTreeMap::new();
        let mut adders: Vec<Adder<'_>> = Vec::new();
        let mut removers: Vec<Remover<'_>> = Vec::new();
        let mut moves: Vec<(OpId, CatalogRoot, CatalogRoot)> = Vec::new();
        for index in valid.iter() {
            let signed = graph.ops[index];
            let op = signed.op();
            let issuer = op.header.issuer;
            match &op.payload {
                OpPayload::Genesis {
                    caps,
                    name,
                    label,
                    catalog_root,
                } => {
                    sources.insert(issuer);
                    adders.extend(caps.iter().map(|cap| (index, issuer, cap)));
                    if let Some(name) = name {
                        state.names.insert(issuer, name.clone());
                    }
                    state.label.clone_from(label);
                    state.genesis_root = Some(*catalog_root);
                }
                OpPayload::Add {
                    subject,
                    caps,
                    name,
                } => {
                    sources.insert(*subject);
                    adders.extend(caps.iter().map(|cap| (index, *subject, cap)));
                    if let Some(name) = name {
                        state.names.insert(*subject, name.clone());
                    }
                }
                OpPayload::Revoke { subject, .. } => {
                    state.tombstones.insert(*subject);
                    removers.push((index, *subject, None));
                }
                OpPayload::Grant {
                    subject,
                    caps_add,
                    caps_remove,
                } => {
                    adders.extend(caps_add.iter().map(|cap| (index, *subject, cap)));
                    removers.extend(caps_remove.iter().map(|cap| (index, *subject, Some(cap))));
                }
                OpPayload::Rotate { new_key, .. } => {
                    rotated.entry(issuer).or_insert(*new_key);
                    sources.insert(*new_key);
                    if let Some(inherited) = inheritance.get(&index) {
                        adders.extend(inherited.caps.iter().map(|cap| (index, *new_key, cap)));
                        if let Some(name) = &inherited.name {
                            state.names.insert(*new_key, name.clone());
                        }
                    }
                }
                OpPayload::PolicySet { policy } => {
                    if let Ok(parsed) = FailoverPolicy::from_value(policy) {
                        state.policy = Some((signed.id(), parsed));
                    }
                }
                OpPayload::CatalogRootMove {
                    new_root,
                    prev_root,
                } => moves.push((signed.id(), *prev_root, *new_root)),
            }
        }

        state.resolve_statuses(&sources, &rotated);
        state.resolve_caps(graph, &adders, &removers);
        state.resolve_roots(&moves);
        state
    }
}

/// An op index adding a capability to a node.
type Adder<'c> = (usize, NodeId, &'c Capability);

/// An op index removing a capability from a node; `None` removes all of them (a revoke).
type Remover<'c> = (usize, NodeId, Option<&'c Capability>);

/// The evaluated state of a set of ops.
#[derive(Clone, Debug, Default)]
struct State {
    statuses: BTreeMap<NodeId, MemberStatus>,
    tombstones: BTreeSet<NodeId>,
    caps: BTreeMap<NodeId, CapSet>,
    names: BTreeMap<NodeId, String>,
    label: Option<String>,
    policy: Option<(OpId, FailoverPolicy)>,
    genesis_root: Option<CatalogRoot>,
    catalog_root: Option<CatalogRoot>,
    all_roots: BTreeSet<CatalogRoot>,
}

static NO_CAPS: CapSet = BTreeSet::new();

impl State {
    fn is_active(&self, node: &NodeId) -> bool {
        self.statuses.get(node) == Some(&MemberStatus::Active)
    }

    fn caps_of(&self, node: &NodeId) -> &CapSet {
        self.caps.get(node).unwrap_or(&NO_CAPS)
    }

    fn holds(&self, node: &NodeId, capability: &str) -> bool {
        self.is_active(node) && self.caps_of(node).iter().any(|c| c.as_str() == capability)
    }

    fn admins(&self) -> impl Iterator<Item = &NodeId> {
        self.caps
            .keys()
            .filter(|node| self.holds(node, Capability::ADMIN_FULL))
    }

    /// Statuses: revoked beats rotated beats active.
    fn resolve_statuses(&mut self, sources: &BTreeSet<NodeId>, rotated: &BTreeMap<NodeId, NodeId>) {
        for node in sources.iter().chain(rotated.keys()) {
            let status = if self.tombstones.contains(node) {
                MemberStatus::Revoked
            } else if let Some(to) = rotated.get(node) {
                MemberStatus::Rotated { to: *to }
            } else {
                MemberStatus::Active
            };
            self.statuses.insert(*node, status);
        }
        for node in &self.tombstones {
            self.statuses.insert(*node, MemberStatus::Revoked);
        }
    }

    /// Remove-wins: an add of (s, c) survives iff every remover of (s, c) is in its past. Only
    /// active members hold capabilities.
    fn resolve_caps(&mut self, graph: &Graph<'_>, adders: &[Adder<'_>], removers: &[Remover<'_>]) {
        for (adder, subject, cap) in adders {
            if self.statuses.get(subject) != Some(&MemberStatus::Active) {
                continue;
            }
            let survives = removers.iter().all(|(remover, target, removed)| {
                target != subject
                    || removed.is_some_and(|removed| removed != *cap)
                    || graph.past[*adder].contains(*remover)
            });
            if survives {
                self.caps
                    .entry(*subject)
                    .or_default()
                    .insert((*cap).clone());
            }
        }
    }

    fn require_admin(&self, issuer: &NodeId) -> Result<(), Reason> {
        if !self.is_active(issuer) {
            Err(Reason::IssuerNotActive)
        } else if !self.holds(issuer, Capability::ADMIN_FULL) {
            Err(Reason::IssuerLacksCapability)
        } else {
            Ok(())
        }
    }

    fn authorize_add(
        &self,
        issuer: &NodeId,
        subject: &NodeId,
        caps: &CapSet,
    ) -> Result<(), Reason> {
        if !self.is_active(issuer) {
            return Err(Reason::IssuerNotActive);
        }
        if !self.holds(issuer, Capability::ADMIN_FULL) {
            if !self.holds(issuer, Capability::DEVICES_APPROVE) {
                return Err(Reason::IssuerLacksCapability);
            }
            if !capability::within_device_ceiling(caps) {
                return Err(Reason::CeilingExceeded);
            }
        }
        if subject == issuer {
            Err(Reason::SelfAdd)
        } else if self.tombstones.contains(subject) {
            Err(Reason::SubjectTombstoned)
        } else if self.statuses.contains_key(subject) {
            Err(Reason::SubjectAlreadyMember)
        } else {
            Ok(())
        }
    }

    fn authorize_grant(
        &self,
        subject: &NodeId,
        caps_add: &CapSet,
        caps_remove: &CapSet,
    ) -> Result<(), Reason> {
        if !self.is_active(subject) {
            Err(Reason::SubjectNotActive)
        } else if caps_add.is_empty() && caps_remove.is_empty() {
            Err(Reason::EmptyGrant)
        } else if !caps_add.is_disjoint(caps_remove) {
            Err(Reason::OverlappingGrant)
        } else if caps_remove
            .iter()
            .any(|c| c.as_str() == Capability::ADMIN_FULL)
            && !self.other_admin_exists(subject)
        {
            Err(Reason::LastAdmin)
        } else {
            Ok(())
        }
    }

    fn authorize_policy(&self, policy: &core_proto::bcbor::Value) -> Result<(), Reason> {
        let parsed = FailoverPolicy::from_value(policy).map_err(|_| Reason::PolicyInvalid)?;
        let voters_ok = parsed.quorum.iter().flat_map(|q| &q.voters).all(|voter| {
            self.holds(voter, Capability::STORE) && self.holds(voter, Capability::NODE_REPLICATE)
        });
        if voters_ok {
            Ok(())
        } else {
            Err(Reason::PolicyInvalid)
        }
    }

    /// Whether some admin other than `node` remains.
    fn other_admin_exists(&self, node: &NodeId) -> bool {
        self.admins().any(|admin| admin != node)
    }

    /// Walks the root chain from the genesis root. At each root the successor is the lowest-id
    /// valid move from it to a root not yet on the chain. Every root named anywhere other than the
    /// current one is superseded.
    fn resolve_roots(&mut self, moves: &[(OpId, CatalogRoot, CatalogRoot)]) {
        let Some(genesis_root) = self.genesis_root else {
            return;
        };
        self.all_roots.insert(genesis_root);
        self.all_roots.extend(moves.iter().map(|(_, _, new)| *new));
        let mut current = genesis_root;
        let mut visited = BTreeSet::from([current]);
        while let Some((_, _, next)) = moves
            .iter()
            .filter(|(_, prev, new)| *prev == current && !visited.contains(new))
            .min_by_key(|(id, _, _)| *id)
        {
            current = *next;
            visited.insert(current);
        }
        self.catalog_root = Some(current);
    }

    fn into_view(self, constellation: OpId, heads: BTreeSet<OpId>) -> MembershipView {
        let current = self.catalog_root;
        let (policy_op, policy) = match self.policy {
            Some((id, policy)) => (Some(id), policy),
            None => (None, FailoverPolicy::default()),
        };
        let caps = self
            .caps
            .into_iter()
            .filter(|(node, caps)| {
                self.statuses.get(node) == Some(&MemberStatus::Active) && !caps.is_empty()
            })
            .collect();
        MembershipView {
            constellation,
            label: self.label,
            statuses: self.statuses,
            caps,
            names: self.names,
            policy,
            policy_op,
            catalog_root: current,
            superseded_roots: self
                .all_roots
                .into_iter()
                .filter(|root| Some(*root) != current)
                .collect(),
            heads,
        }
    }
}

/// Checks `signed`'s authorization rule against `state`, the state of its causal past.
fn authorize(signed: &SignedOp, state: &State) -> Result<(), Reason> {
    let op = signed.op();
    let issuer = op.header.issuer;
    if let OpPayload::Genesis { caps, .. } = &op.payload {
        return if caps.iter().any(|c| c.as_str() == Capability::ADMIN_FULL) {
            Ok(())
        } else {
            Err(Reason::GenesisWithoutAdmin)
        };
    }
    if state.admins().next().is_none() {
        return Err(Reason::Locked);
    }
    let require_admin = || state.require_admin(&issuer);
    match &op.payload {
        OpPayload::Genesis { .. } => Ok(()),
        OpPayload::Add { subject, caps, .. } => state.authorize_add(&issuer, subject, caps),
        OpPayload::Grant {
            subject,
            caps_add,
            caps_remove,
        } => {
            require_admin()?;
            state.authorize_grant(subject, caps_add, caps_remove)
        }
        OpPayload::Revoke { subject, .. } => {
            require_admin()?;
            if state.tombstones.contains(subject) {
                Err(Reason::AlreadyTombstoned)
            } else if !state.other_admin_exists(subject) {
                Err(Reason::LastAdmin)
            } else {
                Ok(())
            }
        }
        OpPayload::Rotate {
            new_key,
            new_key_sig,
        } => {
            if !state.is_active(&issuer) {
                Err(Reason::IssuerNotActive)
            } else if state.statuses.contains_key(new_key) || state.tombstones.contains(new_key) {
                Err(Reason::RotateKeyReused)
            } else if op.header.constellation.is_none_or(|constellation| {
                !op::rotate_proof_valid(&issuer, new_key, &constellation, new_key_sig)
            }) {
                Err(Reason::BadProofOfPossession)
            } else {
                Ok(())
            }
        }
        OpPayload::PolicySet { policy } => {
            require_admin()?;
            state.authorize_policy(policy)
        }
        OpPayload::CatalogRootMove {
            new_root,
            prev_root,
        } => {
            require_admin()?;
            if state.catalog_root != Some(*prev_root) {
                Err(Reason::PrevRootMismatch)
            } else if new_root == prev_root || state.all_roots.contains(new_root) {
                Err(Reason::RootReused)
            } else {
                Ok(())
            }
        }
    }
}
