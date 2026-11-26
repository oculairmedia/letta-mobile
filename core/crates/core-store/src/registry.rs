//! The catalog doc registry: the documents this node knows the constellation lists, and so the
//! only documents its catalog gate serves.

use core_proto::AgentId;

use crate::error::StoreError;
use crate::ids::DocId;

/// Lifecycle of a registered document.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum DocState {
    /// Current.
    Live,
    /// Rolled over; its successor is live.
    Superseded,
    /// Dropped from local storage after the rollover grace period.
    Retired,
}

impl DocState {
    /// The stored name: `live`, `superseded` or `retired`.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Live => "live",
            Self::Superseded => "superseded",
            Self::Retired => "retired",
        }
    }

    /// Parses a stored name.
    #[must_use]
    pub fn parse(name: &str) -> Option<Self> {
        match name {
            "live" => Some(Self::Live),
            "superseded" => Some(Self::Superseded),
            "retired" => Some(Self::Retired),
            _ => None,
        }
    }
}

/// One registered catalog document.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CatalogDoc {
    /// The document id.
    pub doc_id: DocId,
    /// The catalog's document class (`constellation`, `shell`, `agent`, `notebook`, ...).
    pub class: String,
    /// The agent an agent document belongs to.
    pub agent_id: Option<AgentId>,
    /// Lifecycle state.
    pub state: DocState,
    /// When this node registered it (informational).
    pub registered_at_ms: u64,
}

/// The registry area of a transaction.
pub trait DocRegistry {
    /// The registered document with this id.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn catalog_doc(&self, doc_id: &DocId) -> Result<Option<CatalogDoc>, StoreError>;

    /// Every registered document, ordered by id.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn catalog_docs(&self) -> Result<Vec<CatalogDoc>, StoreError>;

    /// Inserts or replaces a registration.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn register_doc(&mut self, doc: &CatalogDoc) -> Result<(), StoreError>;

    /// Changes a registered document's state.
    ///
    /// # Errors
    ///
    /// [`StoreError::NotFound`] when the document is not registered.
    fn set_doc_state(&mut self, doc_id: &DocId, state: DocState) -> Result<(), StoreError>;

    /// Removes a registration. Returns `true` when it existed.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn remove_doc(&mut self, doc_id: &DocId) -> Result<bool, StoreError>;
}
