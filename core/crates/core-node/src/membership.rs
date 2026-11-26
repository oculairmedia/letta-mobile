//! The node's membership: the signed op log it holds, the view evaluated from it, the claim judge
//! over the current view, and the access query the carrier's hook asks.

use std::sync::{Arc, Mutex, PoisonError};

use async_trait::async_trait;
use core_carrier::MembershipQuery;
use core_log::{ClaimContext, ClaimJudge};
use core_membership::{Inserted, MembershipLog, MembershipView, OpId, SignedOp};
use core_placement::MembershipJudge;
use core_proto::{AgentId, NodeId};
use tokio::sync::watch;

/// The membership state of one node.
#[derive(Debug)]
pub struct Membership {
    log: Mutex<MembershipLog>,
    view: watch::Sender<Arc<MembershipView>>,
}

impl Membership {
    /// Membership of `constellation` from the ops the node holds.
    #[must_use]
    pub fn new(constellation: OpId, ops: impl IntoIterator<Item = SignedOp>) -> Self {
        let mut log = MembershipLog::new(constellation);
        for op in ops {
            let _ = log.insert(op);
        }
        let view = Arc::new(log.evaluate().view);
        Self {
            log: Mutex::new(log),
            view: watch::Sender::new(view),
        }
    }

    fn log(&self) -> std::sync::MutexGuard<'_, MembershipLog> {
        self.log.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// The current view.
    #[must_use]
    pub fn view(&self) -> Arc<MembershipView> {
        Arc::clone(&self.view.borrow())
    }

    /// Changes of the view.
    #[must_use]
    pub fn subscribe(&self) -> watch::Receiver<Arc<MembershipView>> {
        self.view.subscribe()
    }

    /// The constellation id.
    #[must_use]
    pub fn constellation(&self) -> OpId {
        self.view().constellation()
    }

    /// The heads of the op DAG.
    #[must_use]
    pub fn heads(&self) -> Vec<OpId> {
        self.log().heads().into_iter().collect()
    }

    /// Every op held.
    #[must_use]
    pub fn ops(&self) -> Vec<SignedOp> {
        self.log().ops().cloned().collect()
    }

    /// Whether the node lacks some of `heads` (or their past).
    #[must_use]
    pub fn lacks_any(&self, heads: &[OpId]) -> bool {
        let log = self.log();
        heads.iter().any(|head| !log.contains(head))
    }

    /// Whether `heads` (a peer's) miss one of this node's heads.
    #[must_use]
    pub fn peer_lacks(&self, heads: &[OpId]) -> bool {
        let log = self.log();
        let mut known = std::collections::BTreeSet::new();
        for head in heads {
            if log.contains(head) {
                known.insert(*head);
                known.extend(log.causal_past(head));
            }
        }
        log.heads().iter().any(|head| !known.contains(head))
    }

    /// Inserts ops; returns whether any was new (the view is then re-evaluated and published).
    pub fn insert(&self, ops: impl IntoIterator<Item = SignedOp>) -> bool {
        let mut log = self.log();
        let mut fresh = false;
        for op in ops {
            if matches!(log.insert(op), Ok(Inserted::Stored(_))) {
                fresh = true;
            }
        }
        if fresh {
            let view = Arc::new(log.evaluate().view);
            drop(log);
            self.view.send_replace(view);
        }
        fresh
    }
}

/// core-log's claim judge over the node's current view (placement's [`MembershipJudge`]).
#[derive(Clone, Debug)]
pub struct LiveJudge {
    view: watch::Receiver<Arc<MembershipView>>,
    agent: Option<AgentId>,
}

impl LiveJudge {
    /// A judge for any log (constellation-wide policy), or for `agent`'s log (per-agent overrides).
    #[must_use]
    pub fn new(membership: &Membership, agent: Option<AgentId>) -> Self {
        Self {
            view: membership.subscribe(),
            agent,
        }
    }
}

impl ClaimJudge for LiveJudge {
    fn claim_valid(&self, claim: &ClaimContext<'_>) -> bool {
        let view = Arc::clone(&self.view.borrow());
        MembershipJudge::new(&view, self.agent.as_ref()).claim_valid(claim)
    }
}

/// The carrier's membership query over the node's current view.
#[derive(Clone, Debug)]
pub struct MemberQuery(pub watch::Receiver<Arc<MembershipView>>);

#[async_trait]
impl MembershipQuery for MemberQuery {
    async fn is_member(&self, node: &NodeId) -> bool {
        self.0.borrow().is_member(node)
    }
}
