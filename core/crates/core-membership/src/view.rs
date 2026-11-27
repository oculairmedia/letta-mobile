//! The evaluated membership state and its query API.

use std::collections::{BTreeMap, BTreeSet};

use core_proto::NodeId;

use crate::capability::{CapSet, Capability};
use crate::crypto::OpId;
use crate::op::CatalogRoot;
use crate::policy::FailoverPolicy;

/// A node id's membership status.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MemberStatus {
    /// A member, admitted by genesis, add or rotate, and not revoked or rotated away.
    Active,
    /// Tombstoned by a valid revoke. Permanent: the id can never be a member again.
    Revoked,
    /// Moved to a new key by a valid rotate. The old key is retired, not revoked: what it signed
    /// before stays valid.
    Rotated {
        /// The key it moved to (the first in the linear order if it rotated concurrently twice).
        to: NodeId,
    },
}

impl MemberStatus {
    /// The status's snake_case name, as fixtures write it.
    #[must_use]
    pub const fn name(&self) -> &'static str {
        match self {
            Self::Active => "active",
            Self::Revoked => "revoked",
            Self::Rotated { .. } => "rotated",
        }
    }
}

/// How the connection hook treats a dialler on the membership protocol.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Admission {
    /// An active member: full sync, and the other protocols its capabilities allow.
    Member,
    /// Not a member and not tombstoned: bounded push-only on the membership protocol.
    Stranger,
    /// Tombstoned: refused everywhere.
    Refused,
}

static NO_CAPS: CapSet = BTreeSet::new();

/// The membership state a node evaluated from the ops it holds.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MembershipView {
    pub(crate) constellation: OpId,
    pub(crate) label: Option<String>,
    pub(crate) statuses: BTreeMap<NodeId, MemberStatus>,
    pub(crate) caps: BTreeMap<NodeId, CapSet>,
    pub(crate) names: BTreeMap<NodeId, String>,
    pub(crate) policy: FailoverPolicy,
    pub(crate) policy_op: Option<OpId>,
    pub(crate) catalog_root: Option<CatalogRoot>,
    pub(crate) superseded_roots: BTreeSet<CatalogRoot>,
    pub(crate) heads: BTreeSet<OpId>,
}

impl MembershipView {
    /// The constellation id.
    #[must_use]
    pub const fn constellation(&self) -> OpId {
        self.constellation
    }

    /// The constellation's display name from genesis.
    #[must_use]
    pub fn label(&self) -> Option<&str> {
        self.label.as_deref()
    }

    /// Whether `node` is an active member.
    #[must_use]
    pub fn is_member(&self, node: &NodeId) -> bool {
        self.statuses.get(node) == Some(&MemberStatus::Active)
    }

    /// `node`'s status, or `None` for an id the log never named as a member or revoke subject.
    #[must_use]
    pub fn status(&self, node: &NodeId) -> Option<MemberStatus> {
        self.statuses.get(node).copied()
    }

    /// Every id with a status.
    #[must_use]
    pub const fn statuses(&self) -> &BTreeMap<NodeId, MemberStatus> {
        &self.statuses
    }

    /// How the membership protocol's connection hook treats `node`.
    #[must_use]
    pub fn admission(&self, node: &NodeId) -> Admission {
        match self.statuses.get(node) {
            Some(MemberStatus::Active) => Admission::Member,
            Some(MemberStatus::Revoked) => Admission::Refused,
            Some(MemberStatus::Rotated { .. }) | None => Admission::Stranger,
        }
    }

    /// The active members.
    pub fn members(&self) -> impl Iterator<Item = &NodeId> {
        self.statuses
            .iter()
            .filter(|(_, status)| **status == MemberStatus::Active)
            .map(|(node, _)| node)
    }

    /// `node`'s capabilities; empty unless it is an active member.
    #[must_use]
    pub fn capabilities_of(&self, node: &NodeId) -> &CapSet {
        self.caps.get(node).unwrap_or(&NO_CAPS)
    }

    /// Whether active member `node` holds `capability`.
    #[must_use]
    pub fn has_capability(&self, node: &NodeId, capability: &str) -> bool {
        self.capabilities_of(node)
            .iter()
            .any(|cap| cap.as_str() == capability)
    }

    /// The active members holding `admin.full`.
    #[must_use]
    pub fn admins(&self) -> BTreeSet<NodeId> {
        self.members()
            .filter(|node| self.has_capability(node, Capability::ADMIN_FULL))
            .copied()
            .collect()
    }

    /// Whether the constellation is locked: it has a genesis but no active admin, so no further op
    /// is authorized. Recovery is a new genesis.
    #[must_use]
    pub fn is_locked(&self) -> bool {
        self.catalog_root.is_some() && self.admins().is_empty()
    }

    /// A node's display name (last writer wins in the linear order).
    #[must_use]
    pub fn name_of(&self, node: &NodeId) -> Option<&str> {
        self.names.get(node).map(String::as_str)
    }

    /// The failover policy: the last valid `policy_set` in the linear order, or the defaults.
    #[must_use]
    pub const fn policy(&self) -> &FailoverPolicy {
        &self.policy
    }

    /// The op that set the policy, if any did.
    #[must_use]
    pub const fn policy_op(&self) -> Option<OpId> {
        self.policy_op
    }

    /// The current catalog root document, or `None` before the genesis is held.
    #[must_use]
    pub const fn catalog_root(&self) -> Option<CatalogRoot> {
        self.catalog_root
    }

    /// Every earlier or losing catalog root, which C2 reads once and retires.
    #[must_use]
    pub const fn superseded_roots(&self) -> &BTreeSet<CatalogRoot> {
        &self.superseded_roots
    }

    /// The DAG heads the view was evaluated at.
    #[must_use]
    pub const fn heads(&self) -> &BTreeSet<OpId> {
        &self.heads
    }
}
