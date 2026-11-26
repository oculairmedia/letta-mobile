//! Constellation membership (M1): a DAG of signed ops that every node evaluates the same way.
//!
//! The normative text is `docs/reference/contracts/membership/membership-v1.md`; the fixtures
//! beside it are run by this crate's tests. The crate is pure: no I/O, no clock reads, no tasks, so
//! it builds for `wasm32-unknown-unknown` and the browser can verify membership itself.
//!
//! - [`crypto`]: op ids and the rotate proof of possession, over [`core_proto::crypto`]. BCBOR is
//!   [`core_proto::bcbor`].
//! - [`op`]: the seven op kinds, their canonical bodies and the signed envelope.
//! - [`capability`]: the ten capabilities, the device ceiling and the UI presets.
//! - [`log`]: the held DAG with its pending buffer and trust anchor.
//! - [`eval`]: causal validity, revoke-wins tombstones, remove-wins capabilities, the last-admin
//!   rule and the catalog root chain.
//! - [`view`]: the [`MembershipView`] query API.
//! - [`proof`]: the joiner's bounded proof push.
//! - [`store`]: persistence through the node store's membership area.
//! - [`policy`]: the failover policy `policy_set` carries.

pub mod capability;
pub mod crypto;
pub mod eval;
pub mod log;
pub mod op;
pub mod policy;
pub mod proof;
pub mod store;
pub mod view;

pub use capability::{CapSet, Capability, Preset};
pub use crypto::{NodeKey, OpId, Signature};
pub use eval::{Evaluation, Reason, Rejection, evaluate};
pub use log::{InsertError, Inserted, MembershipLog};
pub use op::{CatalogRoot, Op, OpError, OpHeader, OpKind, OpPayload, SignedOp};
pub use proof::{PushBudget, PushError, PushLimits, proof_bundle};
pub use store::{PersistError, PersistentLog};
pub use view::{Admission, MemberStatus, MembershipView};

/// The constellation id: the op id of the genesis op.
pub type ConstellationId = OpId;
