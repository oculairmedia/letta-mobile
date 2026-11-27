//! The catalog of the Baken core (C2, `catalog-v1`): Automerge documents on samod for the
//! constellation, the shell and each agent, behind a three-layer access gate.
//!
//! The normative text is `docs/reference/contracts/catalog-v1/catalog-v1.md`; the fixtures beside it
//! are run by this crate's tests. The catalog holds nothing authoritative: membership, placement and
//! security settings are C1 signed records, and the catalog carries only verifiable copies.
//!
//! - [`ids`]: document URLs (`automerge:<bs58check>`) and classes.
//! - [`model`], [`write`]: the exact catalog-v1 keys, genesis per class and every allowed write,
//!   with the [`secrets`] check before anything reaches Automerge. Divergence node ids reuse
//!   core-log's derivation.
//! - [`canonical`], [`view`]: the canonical JSON of a raw document and the typed views with the app
//!   conflict rules (layer 3).
//! - [`policy`], [`gate`], [`wire`]: `may(principal, doc)`, the per-message gate (layer 2) and the
//!   samod message fields it reads.
//! - [`registry`], [`storage`], [`quarantine`]: the doc registry on core-store's `DocRegistry`, samod
//!   chunks on core-store's `KvStore`, and the quarantine ring.
//! - [`rollover`]: rollover at 2 MiB with C1's `catalog_root_move`, the late-change sweep and
//!   container repair.
//! - [`protocol`]: the samod repository, the link driver (layer 1), the access hook (layer 0) and the
//!   `meridian/automerge-repo/1` stream handler on core-carrier.
//!
//! This is the only crate in the core that names `automerge` or `samod`. Both are pinned to the
//! versions the JVM binding `org.automerge:automerge` 0.0.9 bundles: automerge 0.8.0 and samod-core
//! 0.9.0, with samod 0.9.0 (built on samod-core 0.9.0) as the async repository.

pub mod canonical;
pub mod error;
pub mod gate;
pub mod ids;
pub mod model;
pub mod policy;
pub mod protocol;
pub mod quarantine;
pub mod registry;
pub mod rollover;
pub mod secrets;
pub mod storage;
pub mod view;
pub mod wire;
pub mod write;

pub use error::CatalogError;
pub use gate::{DocGate, GateAccess, Inbound, QuarantineReason, Quarantined, Verdict};
pub use ids::{DocClass, DocUrl};
pub use policy::{Access, CatalogPolicy, DocPolicy, NotebookGrant, NotebookGrants, Standing};
pub use protocol::{ALPN, CatalogAccess, CatalogEvent, CatalogNode, CatalogProtocol, Clock};
pub use view::{CatalogView, ViewContext, Violation, view};
