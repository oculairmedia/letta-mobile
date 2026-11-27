//! The membership DAG a node holds: verified ops whose ancestry is complete, plus a bounded buffer
//! of ops still waiting for parents.

use std::collections::{BTreeMap, BTreeSet};

use thiserror::Error;

use crate::crypto::OpId;
use crate::eval::{self, Evaluation};
use crate::op::{OpKind, SignedOp};

/// How many ops wait for missing parents before new ones are refused.
pub const DEFAULT_PENDING_LIMIT: usize = 1_024;

/// What [`MembershipLog::insert`] did with an op.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Inserted {
    /// The op was already held.
    Duplicate,
    /// The op's parents are not all held yet; it waits.
    Pending {
        /// The parents not yet held.
        missing: BTreeSet<OpId>,
    },
    /// The op, and any pending ops it completed, joined the DAG, parents before children.
    Stored(Vec<OpId>),
}

/// Why an op is not accepted into this constellation's log.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
pub enum InsertError {
    /// A genesis op whose id is not this log's constellation id (the trust anchor).
    #[error("genesis does not match the constellation id")]
    ForeignGenesis,
    /// A non-genesis op that names another constellation.
    #[error("op belongs to another constellation")]
    ConstellationMismatch,
    /// The pending buffer is full.
    #[error("too many ops waiting for parents")]
    PendingFull,
}

impl InsertError {
    /// The stable code the fixtures use.
    #[must_use]
    pub const fn code(&self) -> &'static str {
        match self {
            Self::ForeignGenesis => "foreign_genesis",
            Self::ConstellationMismatch => "constellation_mismatch",
            Self::PendingFull => "pending_full",
        }
    }
}

/// A constellation's membership DAG. It holds only ops whose signatures verified and whose every
/// ancestor is held, so the held set is always down-closed and descends from the one genesis.
#[derive(Clone, Debug)]
pub struct MembershipLog {
    constellation: OpId,
    ops: BTreeMap<OpId, SignedOp>,
    pending: BTreeMap<OpId, SignedOp>,
    pending_limit: usize,
}

impl MembershipLog {
    /// An empty log that will accept only the genesis whose op id is `constellation`.
    #[must_use]
    pub fn new(constellation: OpId) -> Self {
        Self {
            constellation,
            ops: BTreeMap::new(),
            pending: BTreeMap::new(),
            pending_limit: DEFAULT_PENDING_LIMIT,
        }
    }

    /// A log anchored at, and holding, `genesis` (the creating node's path).
    ///
    /// # Panics
    ///
    /// Never: a genesis always matches the constellation it anchors.
    #[must_use]
    pub fn from_genesis(genesis: SignedOp) -> Self {
        let mut log = Self::new(genesis.id());
        log.insert(genesis)
            .expect("a genesis matches its own constellation id");
        log
    }

    /// Sets how many ops may wait for parents.
    #[must_use]
    pub const fn with_pending_limit(mut self, limit: usize) -> Self {
        self.pending_limit = limit;
        self
    }

    /// The constellation id (the genesis op id).
    #[must_use]
    pub const fn constellation(&self) -> OpId {
        self.constellation
    }

    /// Adds a verified op.
    ///
    /// # Errors
    ///
    /// [`InsertError`] when the op belongs to another constellation or the pending buffer is full.
    pub fn insert(&mut self, op: SignedOp) -> Result<Inserted, InsertError> {
        let id = op.id();
        if self.ops.contains_key(&id) || self.pending.contains_key(&id) {
            return Ok(Inserted::Duplicate);
        }
        self.check_constellation(&op)?;
        let missing: BTreeSet<OpId> = op
            .op()
            .header
            .parents
            .iter()
            .filter(|parent| !self.ops.contains_key(parent))
            .copied()
            .collect();
        if !missing.is_empty() {
            if self.pending.len() >= self.pending_limit {
                return Err(InsertError::PendingFull);
            }
            self.pending.insert(id, op);
            return Ok(Inserted::Pending { missing });
        }
        self.ops.insert(id, op);
        let mut stored = vec![id];
        self.promote_pending(&mut stored);
        Ok(Inserted::Stored(stored))
    }

    /// A genesis must be the trust anchor; any other op must name it.
    fn check_constellation(&self, op: &SignedOp) -> Result<(), InsertError> {
        if op.op().kind() == OpKind::Genesis {
            if op.id() == self.constellation {
                Ok(())
            } else {
                Err(InsertError::ForeignGenesis)
            }
        } else if op.op().header.constellation == Some(self.constellation) {
            Ok(())
        } else {
            Err(InsertError::ConstellationMismatch)
        }
    }

    fn promote_pending(&mut self, stored: &mut Vec<OpId>) {
        loop {
            let ready: Vec<OpId> = self
                .pending
                .iter()
                .filter(|(_, op)| {
                    op.op()
                        .header
                        .parents
                        .iter()
                        .all(|parent| self.ops.contains_key(parent))
                })
                .map(|(id, _)| *id)
                .collect();
            if ready.is_empty() {
                return;
            }
            for id in ready {
                if let Some(op) = self.pending.remove(&id) {
                    self.ops.insert(id, op);
                    stored.push(id);
                }
            }
        }
    }

    /// Undoes the insert that returned [`Inserted::Stored`] with `stored`: the inserted op (the
    /// first id) leaves the log and the pending ops it completed wait for it again. Call it right
    /// after that insert, before any other change.
    pub(crate) fn undo_stored(&mut self, stored: &[OpId]) {
        let Some((inserted, completed)) = stored.split_first() else {
            return;
        };
        self.ops.remove(inserted);
        for id in completed {
            if let Some(op) = self.ops.remove(id) {
                self.pending.insert(*id, op);
            }
        }
    }

    /// The held op with id `id`.
    #[must_use]
    pub fn get(&self, id: &OpId) -> Option<&SignedOp> {
        self.ops.get(id)
    }

    /// Whether the op is held (not pending).
    #[must_use]
    pub fn contains(&self, id: &OpId) -> bool {
        self.ops.contains_key(id)
    }

    /// Every held op, by id.
    pub fn ops(&self) -> impl Iterator<Item = &SignedOp> {
        self.ops.values()
    }

    /// How many ops are held.
    #[must_use]
    pub fn len(&self) -> usize {
        self.ops.len()
    }

    /// Whether no op is held.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.ops.is_empty()
    }

    /// The ops waiting for parents.
    pub fn pending(&self) -> impl Iterator<Item = &SignedOp> {
        self.pending.values()
    }

    /// The held ops that no held op names as a parent: the parents of the next op this node writes.
    #[must_use]
    pub fn heads(&self) -> BTreeSet<OpId> {
        let mut heads: BTreeSet<OpId> = self.ops.keys().copied().collect();
        for op in self.ops.values() {
            for parent in &op.op().header.parents {
                heads.remove(parent);
            }
        }
        heads
    }

    /// The causal past of `id`: the transitive closure of its parents (not including `id`).
    #[must_use]
    pub fn causal_past(&self, id: &OpId) -> BTreeSet<OpId> {
        let mut past = BTreeSet::new();
        let mut stack: Vec<OpId> = self
            .ops
            .get(id)
            .map(|op| op.op().header.parents.iter().copied().collect())
            .unwrap_or_default();
        while let Some(next) = stack.pop() {
            if past.insert(next)
                && let Some(op) = self.ops.get(&next)
            {
                stack.extend(op.op().header.parents.iter().copied());
            }
        }
        past
    }

    /// A log holding only `ids` and their causal past.
    #[must_use]
    pub fn restricted_to(&self, ids: &BTreeSet<OpId>) -> Self {
        let mut keep = BTreeSet::new();
        for id in ids {
            if self.ops.contains_key(id) {
                keep.insert(*id);
                keep.extend(self.causal_past(id));
            }
        }
        Self {
            constellation: self.constellation,
            ops: keep
                .iter()
                .filter_map(|id| self.ops.get(id).map(|op| (*id, op.clone())))
                .collect(),
            pending: BTreeMap::new(),
            pending_limit: self.pending_limit,
        }
    }

    /// Evaluates the held DAG into a membership view and the list of ops that have no effect.
    #[must_use]
    pub fn evaluate(&self) -> Evaluation {
        eval::evaluate(self.constellation, self.ops.values())
    }
}
