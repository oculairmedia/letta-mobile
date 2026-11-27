//! Placement, handoff and failover (X1/X6) over the replicated agent log.
//!
//! The normative text is `docs/reference/contracts/placement/placement-v1.md`; the fixtures beside
//! it are run by this crate's tests. Everything is a pure state machine or a pure function: no I/O,
//! no tasks, no clock reads (time is passed in), so the crate builds for `wasm32-unknown-unknown`.
//!
//! - [`judge`]: [`MembershipJudge`], core-log's `ClaimJudge` over a membership view (claim rules 2
//!   and 3: active claimant with `node.execute`, `node.takeover`, the policy mode and candidates,
//!   quorum certificates, `admin.full`, rotation).
//! - [`vote`] and [`voter`]: claim votes, certificates, the voter's rules with a promise persisted
//!   in the node store, and the claimant's tally.
//! - [`record`]: the [`PlacementRecord`] derived from the log, and the input [`Gate`].
//! - [`pipeline`]: the move as two state machines ([`MoveSource`], [`MoveTarget`]) and crash
//!   recovery.
//! - [`failover`]: the failover policy evaluator, self-fence and startup rules.
//! - [`fenced`]: the fenced-holder report and its assessment.
//! - [`feed`]: the feed v1 placement amendment.
//! - [`wire`]: `baken/place/1` messages.
//! - [`backoff`]: retry schedules behind [`RetrySchedule`].

pub mod backoff;
pub mod failover;
pub mod feed;
pub mod fenced;
pub mod judge;
pub mod pipeline;
pub mod record;
pub mod vote;
pub mod voter;
pub mod wire;

pub use backoff::RetrySchedule;
pub use failover::{FailoverDecision, FailoverInput, Liveness, NodeClass, PeerFacts};
pub use fenced::{FencedAssessment, FencedReport};
pub use judge::{ClaimVerdict, MembershipJudge};
pub use pipeline::{MoveSource, MoveTarget, SourceRecovery, TargetRecovery};
pub use record::{Gate, PlacementRecord};
pub use vote::{ClaimVote, SignedVote, VoteError};
pub use voter::{ClaimRequest, Refusal, Tally};
pub use wire::PlaceMessage;
