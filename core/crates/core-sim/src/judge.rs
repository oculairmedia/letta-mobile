//! A claim judge over a node's current membership view.
//!
//! core-placement's [`MembershipJudge`] borrows one view; a session driver holds its judge for the
//! whole session while membership changes underneath (a revoke arrives mid-partition). This judge
//! holds the node's latest view and builds a `MembershipJudge` over it for every verdict, so each
//! verdict uses the membership the node holds at that instant.

use std::cell::{Cell, RefCell};

use core_log::{ClaimContext, ClaimJudge};
use core_membership::MembershipView;
use core_placement::MembershipJudge;

/// The judge of one simulated node.
#[derive(Debug, Default)]
pub struct SimJudge {
    view: RefCell<Option<MembershipView>>,
    generation: Cell<u64>,
}

impl SimJudge {
    /// A judge with no membership yet: every claim is refused until the genesis op arrives.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Replaces the view (after the node inserted membership ops).
    pub fn set_view(&self, view: MembershipView) {
        *self.view.borrow_mut() = Some(view);
        self.generation.set(self.generation.get() + 1);
    }

    /// How many times the view was replaced; caches derived from the view key on it.
    #[must_use]
    pub fn generation(&self) -> u64 {
        self.generation.get()
    }

    /// The current view.
    #[must_use]
    pub fn view(&self) -> Option<MembershipView> {
        self.view.borrow().clone()
    }
}

impl ClaimJudge for SimJudge {
    fn claim_valid(&self, claim: &ClaimContext<'_>) -> bool {
        self.view
            .borrow()
            .as_ref()
            .is_some_and(|view| MembershipJudge::new(view, None).claim_valid(claim))
    }
}
