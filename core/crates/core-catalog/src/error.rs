//! Catalog failures.

use core_store::StoreError;
use thiserror::Error;

/// Why a catalog operation failed.
#[derive(Debug, Error)]
pub enum CatalogError {
    /// Text that is not an `automerge:<bs58check>` URL or a bs58check id of 16 bytes.
    #[error("bad document id: {0}")]
    BadDocumentId(String),
    /// This node may only read the document.
    #[error("not permitted: this node may not write {0}")]
    NotPermitted(String),
    /// A value failed the secrets and paths check before it reached Automerge.
    #[error("refused value at {path}: {reason}")]
    Refused {
        /// The key path of the value.
        path: String,
        /// Which check it failed.
        reason: &'static str,
    },
    /// A value breaks the schema (a bad enum, a missing container, a mismatched id).
    #[error("schema: {0}")]
    Schema(String),
    /// The document is at the hard cap and only rollover may write it.
    #[error("document at the hard cap ({0} bytes); roll it over first")]
    HardCap(usize),
    /// The document is not registered, or the repository has no copy of it.
    #[error("unknown document {0}")]
    UnknownDocument(String),
    /// Automerge refused an operation.
    #[error("automerge: {0}")]
    Automerge(String),
    /// The node store failed.
    #[error(transparent)]
    Store(#[from] StoreError),
    /// The samod repository has stopped.
    #[error("the repository stopped")]
    Stopped,
}

impl From<automerge::AutomergeError> for CatalogError {
    fn from(error: automerge::AutomergeError) -> Self {
        Self::Automerge(error.to_string())
    }
}
