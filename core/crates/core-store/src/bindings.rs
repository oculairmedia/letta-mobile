//! Node-local bindings: values that differ per node and are never replicated (H1).
//!
//! A binding maps a replicated, host-neutral name to this node's value: a model profile to the
//! provider handle that serves it here, a workspace id to its path on this disk, the hooks dir.
//! Secrets never go here; credentials stay in the platform's secure storage.
//!
//! [`BindingsStore`] is deliberately not part of [`crate::Txn`], and its keys are a closed enum, so
//! a replicated record has no way in and a binding has no way into the replicated areas.

use crate::error::StoreError;

/// Which node-local value a binding holds.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum BindingKey {
    /// The provider handle that serves a model profile on this node. The name is the profile name
    /// as the replicated records use it (for example `profile/default`).
    ModelProfile(String),
    /// The local path of a workspace, by workspace id.
    WorkspacePath(String),
    /// The local hooks directory.
    HooksDir,
}

const MODEL_PROFILE: &str = "model_profile:";
const WORKSPACE_PATH: &str = "workspace_path:";
const HOOKS_DIR: &str = "hooks_dir";
const MAX_NAME_BYTES: usize = 256;

impl BindingKey {
    /// The stored key: `model_profile:<name>`, `workspace_path:<id>` or `hooks_dir`.
    ///
    /// # Errors
    ///
    /// [`StoreError::Invalid`] when a name is empty, longer than 256 bytes, or holds a control
    /// character.
    pub fn encode(&self) -> Result<String, StoreError> {
        match self {
            Self::ModelProfile(name) => Ok(format!("{MODEL_PROFILE}{}", check_name(name)?)),
            Self::WorkspacePath(id) => Ok(format!("{WORKSPACE_PATH}{}", check_name(id)?)),
            Self::HooksDir => Ok(HOOKS_DIR.to_string()),
        }
    }

    /// Parses a stored key.
    ///
    /// # Errors
    ///
    /// [`StoreError::Corrupt`] for a key no build writes.
    pub fn decode(key: &str) -> Result<Self, StoreError> {
        let parsed = if key == HOOKS_DIR {
            Some(Self::HooksDir)
        } else if let Some(name) = key.strip_prefix(MODEL_PROFILE) {
            Some(Self::ModelProfile(name.to_string()))
        } else {
            key.strip_prefix(WORKSPACE_PATH)
                .map(|id| Self::WorkspacePath(id.to_string()))
        };
        match parsed {
            Some(Self::ModelProfile(name) | Self::WorkspacePath(name))
                if check_name(&name).is_err() =>
            {
                Err(StoreError::Corrupt(format!("bad binding key {key:?}")))
            }
            Some(binding) => Ok(binding),
            None => Err(StoreError::Corrupt(format!("unknown binding key {key:?}"))),
        }
    }
}

fn check_name(name: &str) -> Result<&str, StoreError> {
    if name.is_empty() || name.len() > MAX_NAME_BYTES || name.chars().any(char::is_control) {
        Err(StoreError::Invalid(format!("bad binding name {name:?}")))
    } else {
        Ok(name)
    }
}

/// The node-local bindings. Each call is its own durable write; bindings never join a replicated
/// transaction.
pub trait BindingsStore: Send + Sync {
    /// The value bound to `key`.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn binding(&self, key: &BindingKey) -> Result<Option<String>, StoreError>;

    /// Binds `value` to `key`, replacing any earlier value.
    ///
    /// # Errors
    ///
    /// [`StoreError::Invalid`] for a bad key name.
    fn set_binding(&self, key: &BindingKey, value: &str) -> Result<(), StoreError>;

    /// Removes the binding. Returns `true` when it existed.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn remove_binding(&self, key: &BindingKey) -> Result<bool, StoreError>;

    /// Every binding, ordered by stored key.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn bindings(&self) -> Result<Vec<(BindingKey, String)>, StoreError>;
}
