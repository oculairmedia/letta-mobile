//! The runtime host: how a node supervises letta-code. Kotlin implements it on phones and the
//! desktop app; a Rust process supervisor can implement it on headless nodes. The node never talks
//! to letta-code's internals: it starts and stops the runtime for an agent, asks it to quiesce and
//! to commit memory before a checkpoint, and reads its files through the projection.

use std::collections::BTreeMap;
use std::path::PathBuf;
use std::time::Duration;

use async_trait::async_trait;
use core_proto::AgentId;
use thiserror::Error;

/// Where letta-code's App Server listens for this node's shells.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AppServerEndpoint {
    /// The App Server URL (for example `ws://127.0.0.1:8283`).
    pub url: String,
}

/// What the runtime is, for node.info and I6 parity.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct RuntimeInfo {
    /// letta-code's version, or `None` before the App Server reported it.
    pub letta_code_version: Option<String>,
    /// The lcp runtime patch ids applied, when the host can see them.
    pub patch_ids: Option<Vec<String>>,
    /// `lcp-patches applied=N skipped=M`, when the host read the boot line.
    pub patches_applied: Option<(u32, u32)>,
    /// The model handles `model.list` shows by default.
    pub models: Vec<String>,
}

/// Starting the runtime for one agent.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AgentRuntime {
    /// The agent (the tree agent; its subagents run in the same runtime).
    pub agent_id: AgentId,
    /// The letta-code local backend directory (`LETTA_LOCAL_BACKEND_DIR`).
    pub backend_dir: PathBuf,
    /// The node's hooks directory for memfs (`core.hooksPath`, I3).
    pub hooks_dir: PathBuf,
    /// Node-local environment from the bindings (I2, I5): never replicated.
    pub env: BTreeMap<String, String>,
}

/// The outcome of a quiesce request.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Quiesce {
    /// No turn is running and none will start (the gate is shut).
    Idle,
    /// A turn is still running when the timeout passed.
    Busy,
}

/// A runtime host call failed.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
pub enum RuntimeError {
    /// The runtime is not running for this agent.
    #[error("not running")]
    NotRunning,
    /// The runtime failed.
    #[error("runtime: {0}")]
    Failed(String),
}

/// Supervises letta-code for a node.
#[async_trait]
pub trait RuntimeHost: Send + Sync + 'static {
    /// The runtime's version, patch set and models.
    fn info(&self) -> RuntimeInfo;

    /// Starts (or attaches to) the runtime for an agent the node now holds, after its store was
    /// materialized. Returns the App Server endpoint.
    ///
    /// # Errors
    ///
    /// [`RuntimeError`] when it cannot start.
    async fn start(&self, spec: &AgentRuntime) -> Result<AppServerEndpoint, RuntimeError>;

    /// Stops the runtime for an agent the node no longer holds.
    ///
    /// # Errors
    ///
    /// [`RuntimeError`].
    async fn stop(&self, agent: &AgentId) -> Result<(), RuntimeError>;

    /// Waits up to `timeout` for the agent's in-flight turn to finish (the node already refuses new
    /// input).
    ///
    /// # Errors
    ///
    /// [`RuntimeError`].
    async fn quiesce(&self, agent: &AgentId, timeout: Duration) -> Result<Quiesce, RuntimeError>;

    /// Lets letta-code commit memory and flush its files, so the next export is complete.
    ///
    /// # Errors
    ///
    /// [`RuntimeError`].
    async fn checkpoint(&self, agent: &AgentId) -> Result<(), RuntimeError>;

    /// Aborts the agent's in-flight turn (fencing, or "stop the turn and move").
    ///
    /// # Errors
    ///
    /// [`RuntimeError`].
    async fn abort_turn(&self, agent: &AgentId) -> Result<(), RuntimeError>;

    /// The App Server endpoint, while the runtime runs.
    fn endpoint(&self) -> Option<AppServerEndpoint>;
}
