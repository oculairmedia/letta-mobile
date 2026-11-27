//! The two coarse traits the platform implements, and the object the platform reports its events
//! to.

use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use async_trait::async_trait;
use core_carrier_iroh::IrohNode;
use core_node::{AgentRuntime, AppServerEndpoint, Quiesce, RuntimeError};
use core_proto::AgentId;
use tokio::runtime::Handle;
use tokio::sync::broadcast;

use crate::error::HostError;
use crate::records::{AgentRuntimeSpec, NodeEvent, QuiesceOutcome, RuntimeInfo};

/// Supervises letta-code for a node (the platform's launcher on desktop, libnode on Android). The
/// node never talks to letta-code's internals: it starts and stops the runtime for an agent, asks
/// it to quiesce and to commit memory before a checkpoint, and reads its files itself.
///
/// Calls arrive on the node's runtime threads; an async method may suspend as long as it needs.
#[uniffi::export(foreign)]
#[async_trait]
pub trait RuntimeHost: Send + Sync {
    /// The runtime's version, patch set and models. Called often: answer from memory.
    fn info(&self) -> RuntimeInfo;

    /// Starts (or attaches to) the runtime for an agent the node now holds. Returns the App Server
    /// URL.
    ///
    /// # Errors
    ///
    /// [`HostError`] when it cannot start.
    async fn start(&self, spec: AgentRuntimeSpec) -> Result<String, HostError>;

    /// Stops the runtime for an agent the node no longer holds.
    ///
    /// # Errors
    ///
    /// [`HostError`].
    async fn stop(&self, agent: String) -> Result<(), HostError>;

    /// Waits up to `timeout_ms` for the agent's in-flight turn to finish (the node already refuses
    /// new input).
    ///
    /// # Errors
    ///
    /// [`HostError`].
    async fn quiesce(&self, agent: String, timeout_ms: u64) -> Result<QuiesceOutcome, HostError>;

    /// Lets letta-code commit memory and flush its files, so the next export is complete.
    ///
    /// # Errors
    ///
    /// [`HostError`].
    async fn checkpoint(&self, agent: String) -> Result<(), HostError>;

    /// Aborts the agent's in-flight turn.
    ///
    /// # Errors
    ///
    /// [`HostError`].
    async fn abort_turn(&self, agent: String) -> Result<(), HostError>;

    /// The App Server URL while the runtime runs.
    fn endpoint(&self) -> Option<String>;
}

/// core-node's runtime host over the platform's.
pub(crate) struct HostAdapter(pub(crate) Arc<dyn RuntimeHost>);

#[async_trait]
impl core_node::RuntimeHost for HostAdapter {
    fn info(&self) -> core_node::RuntimeInfo {
        self.0.info().into()
    }

    async fn start(&self, spec: &AgentRuntime) -> Result<AppServerEndpoint, RuntimeError> {
        let url = self.0.start(AgentRuntimeSpec::from(spec)).await?;
        Ok(AppServerEndpoint { url })
    }

    async fn stop(&self, agent: &AgentId) -> Result<(), RuntimeError> {
        Ok(self.0.stop(agent.as_str().to_owned()).await?)
    }

    async fn quiesce(&self, agent: &AgentId, timeout: Duration) -> Result<Quiesce, RuntimeError> {
        let timeout_ms = u64::try_from(timeout.as_millis()).unwrap_or(u64::MAX);
        Ok(self
            .0
            .quiesce(agent.as_str().to_owned(), timeout_ms)
            .await?
            .into())
    }

    async fn checkpoint(&self, agent: &AgentId) -> Result<(), RuntimeError> {
        Ok(self.0.checkpoint(agent.as_str().to_owned()).await?)
    }

    async fn abort_turn(&self, agent: &AgentId) -> Result<(), RuntimeError> {
        Ok(self.0.abort_turn(agent.as_str().to_owned()).await?)
    }

    fn endpoint(&self) -> Option<AppServerEndpoint> {
        self.0.endpoint().map(|url| AppServerEndpoint { url })
    }
}

/// What the node needs from the platform besides the runtime: its key, and the platform's
/// lifecycle and connectivity events.
#[uniffi::export(foreign)]
#[async_trait]
pub trait PlatformHooks: Send + Sync {
    /// The node's 32-byte Ed25519 secret key from the platform's secure storage, created there on
    /// first use. Its public half is the node id.
    ///
    /// # Errors
    ///
    /// [`HostError`] when the storage cannot be read.
    async fn node_secret_key(&self) -> Result<Vec<u8>, HostError>;

    /// The node started: report foreground/background and network changes to `events` until
    /// [`detach`](Self::detach).
    fn attach(&self, events: Arc<PlatformEvents>);

    /// The node is stopping: stop reporting.
    fn detach(&self);
}

/// Where the platform reports its events while a node runs.
#[derive(uniffi::Object)]
pub struct PlatformEvents {
    events: broadcast::Sender<NodeEvent>,
    runtime: Handle,
    endpoint: Mutex<Option<Arc<IrohNode>>>,
}

impl PlatformEvents {
    pub(crate) fn new(
        events: broadcast::Sender<NodeEvent>,
        runtime: Handle,
        endpoint: Option<Arc<IrohNode>>,
    ) -> Self {
        Self {
            events,
            runtime,
            endpoint: Mutex::new(endpoint),
        }
    }

    /// Forgets the endpoint once the node stopped; later reports only reach listeners.
    pub(crate) fn close(&self) {
        self.endpoint
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .take();
    }
}

#[uniffi::export]
impl PlatformEvents {
    /// The app moved to (`true`) or from the foreground.
    pub fn foreground_changed(&self, foreground: bool) {
        let _ = self
            .events
            .send(NodeEvent::ForegroundChanged { foreground });
    }

    /// The platform saw a network change: the transport re-probes its paths at once.
    pub fn network_changed(&self) {
        let endpoint = self
            .endpoint
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clone();
        if let Some(endpoint) = endpoint {
            self.runtime
                .spawn(async move { endpoint.network_change().await });
        }
        let _ = self.events.send(NodeEvent::NetworkChanged);
    }
}
