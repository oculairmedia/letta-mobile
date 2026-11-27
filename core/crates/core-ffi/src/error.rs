//! The errors that cross the boundary.

use core_node::{NodeError, RuntimeError};

/// Why a core call failed. Each variant is stable: its name is the error's code on every platform
/// (Kotlin sees `CoreException.<Variant>`).
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    /// An argument does not parse (a hex id, a key, an address, an op envelope).
    #[error("invalid argument: {detail}")]
    InvalidArgument {
        /// What was wrong.
        detail: String,
    },
    /// The call needs a running node and this one is not started (or was stopped).
    #[error("the node is not running")]
    NotRunning,
    /// `start` was called on a running node.
    #[error("the node is already running")]
    AlreadyRunning,
    /// The node store failed.
    #[error("store: {detail}")]
    Store {
        /// The store's message.
        detail: String,
    },
    /// The agent log refused or failed.
    #[error("log: {detail}")]
    Log {
        /// The log's message.
        detail: String,
    },
    /// The projection (export or materialize) failed.
    #[error("projection: {detail}")]
    Projection {
        /// The projection's message.
        detail: String,
    },
    /// The catalog failed.
    #[error("catalog: {detail}")]
    Catalog {
        /// The catalog's message.
        detail: String,
    },
    /// The transport could not bind or dial.
    #[error("transport: {detail}")]
    Transport {
        /// The transport's message.
        detail: String,
    },
    /// The platform's runtime host failed.
    #[error("runtime host: {detail}")]
    RuntimeHost {
        /// The host's message.
        detail: String,
    },
    /// The platform hooks failed (for example the node key could not be loaded).
    #[error("platform: {detail}")]
    Platform {
        /// The hooks' message.
        detail: String,
    },
    /// The node refused the request.
    #[error("refused: {reason}")]
    Refused {
        /// The refusal reason.
        reason: String,
    },
    /// A move ended without committing.
    #[error("move aborted: {reason}")]
    MoveAborted {
        /// The wire reason.
        reason: String,
    },
    /// The node's runtime or the agent's actor stopped while the call ran.
    #[error("stopped")]
    Stopped,
}

impl CoreError {
    pub(crate) fn invalid(message: impl Into<String>) -> Self {
        Self::InvalidArgument {
            detail: message.into(),
        }
    }
}

impl From<NodeError> for CoreError {
    fn from(error: NodeError) -> Self {
        match error {
            NodeError::Log(error) => Self::Log {
                detail: error.to_string(),
            },
            NodeError::Store(error) => Self::Store {
                detail: error.to_string(),
            },
            NodeError::Projection(error) => Self::Projection {
                detail: error.to_string(),
            },
            NodeError::Runtime(error) => Self::RuntimeHost {
                detail: error.to_string(),
            },
            NodeError::MoveAborted(reason) => Self::MoveAborted { reason },
            NodeError::Refused(reason) => Self::Refused { reason },
            NodeError::Stopped => Self::Stopped,
        }
    }
}

impl From<core_store::StoreError> for CoreError {
    fn from(error: core_store::StoreError) -> Self {
        Self::Store {
            detail: error.to_string(),
        }
    }
}

impl From<core_catalog::CatalogError> for CoreError {
    fn from(error: core_catalog::CatalogError) -> Self {
        Self::Catalog {
            detail: error.to_string(),
        }
    }
}

/// Why a foreign implementation ([`crate::RuntimeHost`], [`crate::PlatformHooks`]) failed. Kotlin
/// throws `HostException.<Variant>`; anything else it throws arrives as `Failed`.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum HostError {
    /// The runtime is not running for this agent.
    #[error("not running")]
    NotRunning,
    /// Anything else, with the platform's message.
    #[error("{detail}")]
    Failed {
        /// The platform's message.
        detail: String,
    },
}

impl From<uniffi::UnexpectedUniFFICallbackError> for HostError {
    fn from(error: uniffi::UnexpectedUniFFICallbackError) -> Self {
        Self::Failed {
            detail: error.reason,
        }
    }
}

impl From<HostError> for RuntimeError {
    fn from(error: HostError) -> Self {
        match error {
            HostError::NotRunning => Self::NotRunning,
            HostError::Failed { detail } => Self::Failed(detail),
        }
    }
}
