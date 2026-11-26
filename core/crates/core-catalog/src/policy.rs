//! Who may read or write which catalog document (catalog-v1 §3.1), and the closures the access gate
//! ends a link with.

use std::collections::{BTreeMap, BTreeSet};

use core_membership::{Capability, MemberStatus, MembershipView};
use core_proto::NodeId;
use core_proto::closure::Closure;
use core_store::{CatalogDoc, DocId, DocState};

use crate::ids::DocClass;

/// The closures of `meridian/automerge-repo/1` (catalog-v1 §3).
pub mod closures {
    use core_proto::closure::Closure;

    /// The admission check refused the peer, or it was revoked mid-link.
    pub const PEER_NOT_ALLOWED: Closure = Closure::PEER_NOT_ALLOWED;
    /// The samod peer id is not the authenticated endpoint id.
    pub const PEER_ID_MISMATCH: Closure = Closure {
        code: 4403,
        reason: "peer_id_mismatch",
    };
    /// A read-only principal sent changes three times, or (Kotlin) connected at all.
    pub const WRITE_NOT_PERMITTED: Closure = Closure {
        code: 4403,
        reason: "write_not_permitted",
    };
    /// The principal's capabilities changed: re-dial for fresh sync state.
    pub const CAPS_CHANGED: Closure = Closure {
        code: 4409,
        reason: "caps_changed",
    };
    /// Bad CBOR, no `type`, a sender or target mismatch, or a malformed change.
    pub const MALFORMED_FRAME: Closure = Closure {
        code: 4400,
        reason: "malformed_frame",
    };
    /// A frame over 4 MiB.
    pub const FRAME_TOO_LARGE: Closure = Closure {
        code: 4413,
        reason: "frame_too_large",
    };
    /// More than 32 denials in 60 s.
    pub const TOO_MANY_DENIALS: Closure = Closure {
        code: 4429,
        reason: "too_many_denials",
    };
}

/// What a principal may do with one document.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Access {
    /// Nothing: the document does not exist for it.
    None,
    /// Sync without sending changes.
    Read,
    /// Sync and send changes.
    Write,
}

impl Access {
    /// The fixture name: `none`, `read` or `write`.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::None => "none",
            Self::Read => "read",
            Self::Write => "write",
        }
    }

    /// Parses a fixture name.
    #[must_use]
    pub fn parse(name: &str) -> Option<Self> {
        match name {
            "none" => Some(Self::None),
            "read" => Some(Self::Read),
            "write" => Some(Self::Write),
            _ => None,
        }
    }
}

/// `may(principal, doc)`: a pure function of the principal's capabilities, the registry and the
/// notebook grants. Keyhive replaces exactly this trait later.
pub trait DocPolicy: Send + Sync {
    /// The registered class of `doc`, or `None` when the registry does not list it (the gate then
    /// drops messages about it without a reply).
    fn class(&self, doc: &DocId) -> Option<DocClass>;

    /// What `principal` may do with `doc` (`None` for unregistered documents).
    fn may(&self, principal: &NodeId, doc: &DocId) -> Access;
}

/// One guest's grant on one notebook, from the notebook grants file.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct NotebookGrant {
    /// May sync the notebook.
    pub may_read: bool,
    /// May send changes to it.
    pub may_edit: bool,
}

/// The notebook grants: guest principal to notebook to grant.
pub type NotebookGrants = BTreeMap<NodeId, BTreeMap<DocId, NotebookGrant>>;

/// How a principal stands, compared at admission and on every membership change.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Standing {
    /// Not a member and holds no notebook grant.
    Refused,
    /// A guest with these notebook grants.
    Guest(BTreeMap<DocId, (bool, bool)>),
    /// An active member; `true` when it holds `catalog.write`.
    Member(bool),
}

/// The policy over one membership evaluation, a registry snapshot and the notebook grants.
#[derive(Clone, Debug)]
pub struct CatalogPolicy {
    membership: MembershipView,
    registry: BTreeMap<DocId, (DocClass, DocState)>,
    grants: NotebookGrants,
}

impl CatalogPolicy {
    /// A policy over `membership`, the registered documents and the notebook grants.
    #[must_use]
    pub fn new(
        membership: MembershipView,
        registry: &[CatalogDoc],
        grants: NotebookGrants,
    ) -> Self {
        let registry = registry
            .iter()
            .filter_map(|doc| Some((doc.doc_id, (DocClass::parse(&doc.class)?, doc.state))))
            .collect();
        Self {
            membership,
            registry,
            grants,
        }
    }

    /// The membership evaluation this policy uses.
    #[must_use]
    pub const fn membership(&self) -> &MembershipView {
        &self.membership
    }

    /// The notebook grants.
    #[must_use]
    pub const fn grants(&self) -> &NotebookGrants {
        &self.grants
    }

    /// Layer 0: whether `principal` may connect at all (an active member, or a notebook grant
    /// principal).
    #[must_use]
    pub fn admits(&self, principal: &NodeId) -> bool {
        !matches!(self.standing(principal), Standing::Refused)
    }

    /// How `principal` stands.
    #[must_use]
    pub fn standing(&self, principal: &NodeId) -> Standing {
        if self.membership.status(principal) == Some(MemberStatus::Active) {
            return Standing::Member(
                self.membership
                    .has_capability(principal, Capability::CATALOG_WRITE),
            );
        }
        if self.membership.status(principal) == Some(MemberStatus::Revoked) {
            return Standing::Refused;
        }
        match self.grants.get(principal) {
            Some(grants)
                if grants
                    .values()
                    .any(|grant| grant.may_read || grant.may_edit) =>
            {
                Standing::Guest(
                    grants
                        .iter()
                        .map(|(doc, grant)| (*doc, (grant.may_read, grant.may_edit)))
                        .collect(),
                )
            }
            _ => Standing::Refused,
        }
    }

    /// The registered documents of `class`.
    #[must_use]
    pub fn docs_of(&self, class: DocClass) -> BTreeSet<DocId> {
        self.registry
            .iter()
            .filter(|(_, (c, _))| *c == class)
            .map(|(id, _)| *id)
            .collect()
    }
}

impl DocPolicy for CatalogPolicy {
    fn class(&self, doc: &DocId) -> Option<DocClass> {
        match self.registry.get(doc) {
            Some((class, state)) if *state != DocState::Retired => Some(*class),
            _ => None,
        }
    }

    fn may(&self, principal: &NodeId, doc: &DocId) -> Access {
        let Some(class) = self.class(doc) else {
            return Access::None;
        };
        match self.standing(principal) {
            Standing::Refused => Access::None,
            Standing::Member(write) => {
                if write {
                    Access::Write
                } else {
                    Access::Read
                }
            }
            Standing::Guest(grants) => {
                if class.is_catalog() {
                    return Access::None;
                }
                match grants.get(doc) {
                    Some((_, true)) => Access::Write,
                    Some((true, false)) => Access::Read,
                    _ => Access::None,
                }
            }
        }
    }
}

/// The closure for a principal whose standing moved from `before` to `after`, or `None` when the
/// link may stay open.
#[must_use]
pub fn standing_change(before: &Standing, after: &Standing) -> Option<Closure> {
    if before == after {
        return None;
    }
    Some(match after {
        Standing::Refused => closures::PEER_NOT_ALLOWED,
        _ => closures::CAPS_CHANGED,
    })
}
