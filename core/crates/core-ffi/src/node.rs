//! [`CoreNode`]: one Baken node behind the boundary.

use std::future::Future;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use core_carrier::{Carrier, NodeAddr, VerifiedPeer};
use core_carrier_iroh::{IrohConfig, IrohNode};
use core_catalog::{DocUrl, ViewContext};
use core_membership::{OpId, SignedOp};
use core_node::{Node, NodeConfig};
use core_proto::AgentId;
use core_proto::crypto::NodeKey;
use core_store::Store as _;
use core_store_sqlite::SqliteStore;
use tokio::runtime::Handle;
use tokio::sync::broadcast;

use crate::error::CoreError;
use crate::hooks::{HostAdapter, PlatformEvents, PlatformHooks, RuntimeHost};
use crate::records::{
    CatalogDocument, MembershipView, NodeEvent, NodeInfo, NodeOptions, PeerAddress,
    PlacementStatus, parse_hex32,
};
use crate::stream::{
    CatalogEvent, CatalogEventListener, MembershipListener, NodeEventListener, TaskHandle, forward,
};
use crate::transport::{LateCarrier, Transport};

/// The node's SQLite store, inside its data directory.
const STORE_FILE: &str = "node.sqlite";

/// How long `stop` waits for calls still in flight before it lets the last of them stop the node.
const STOP_DRAIN: Duration = Duration::from_secs(5);

/// A running node and what its transport bound.
struct Running {
    node: Node<SqliteStore>,
    runtime: Handle,
    endpoint: Option<Arc<IrohNode>>,
    network: Option<core_carrier::in_process::InProcessNetwork>,
    platform: Arc<PlatformEvents>,
}

/// Runs `future` on the node's runtime and waits for it from whatever executor polls the caller
/// (Kotlin's coroutine dispatcher), so no foreign thread ever runs core code that needs tokio.
async fn hop<T: Send + 'static>(
    runtime: &Handle,
    future: impl Future<Output = T> + Send + 'static,
) -> Result<T, CoreError> {
    runtime.spawn(future).await.map_err(|_| CoreError::Stopped)
}

/// One Baken node: its own tokio runtime, store, membership, catalog, agent actors and transport.
/// Create it, `start` it, `stop` it; it can start again after a stop.
#[derive(uniffi::Object)]
pub struct CoreNode {
    options: NodeOptions,
    constellation: OpId,
    membership_ops: Vec<SignedOp>,
    transport: Transport,
    host: Arc<dyn RuntimeHost>,
    hooks: Arc<dyn PlatformHooks>,
    lifecycle: tokio::sync::Mutex<()>,
    running: Mutex<Option<Arc<Running>>>,
    events: broadcast::Sender<NodeEvent>,
}

impl CoreNode {
    fn current(&self) -> Result<Arc<Running>, CoreError> {
        self.running
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clone()
            .ok_or(CoreError::NotRunning)
    }

    fn config(&self, key: Arc<NodeKey>) -> NodeConfig {
        let options = &self.options;
        let mut config = NodeConfig::new(
            key,
            PathBuf::from(&options.backend_dir),
            PathBuf::from(&options.data_dir),
            self.constellation,
            self.membership_ops.clone(),
        );
        config.class = options.class.into();
        config.app_version.clone_from(&options.app_version);
        config.worker_threads = usize::try_from(options.worker_threads).unwrap_or(1).max(1);
        config.tick = Duration::from_millis(options.tick_ms.max(1));
        config.watch_debounce = Duration::from_millis(options.watch_debounce_ms);
        config
    }

    async fn node_key(&self) -> Result<[u8; 32], CoreError> {
        let secret = self
            .hooks
            .node_secret_key()
            .await
            .map_err(|error| CoreError::Platform {
                detail: error.to_string(),
            })?;
        secret.try_into().map_err(|_| CoreError::Platform {
            detail: "the node secret key is not 32 bytes".into(),
        })
    }

    /// Binds the transport for a started node: serves its protocols on the in-process network,
    /// or binds its iroh endpoint and hands the endpoint's carrier to the node.
    async fn bind(
        &self,
        node: &Node<SqliteStore>,
        secret: [u8; 32],
        late: &LateCarrier,
    ) -> Result<
        (
            Option<Arc<IrohNode>>,
            Option<core_carrier::in_process::InProcessNetwork>,
        ),
        CoreError,
    > {
        match &self.transport {
            Transport::InProcess { network } => {
                network.inner.serve(node.id(), node.protocols());
                Ok((None, Some(network.inner.clone())))
            }
            Transport::Iroh { bind_addr, relays } => {
                let config = IrohConfig {
                    secret_key: secret,
                    bind: parse_bind(bind_addr)?,
                    relays: relays.clone(),
                };
                let bound = hop(&node.runtime(), IrohNode::bind(config, node.protocols()))
                    .await?
                    .map_err(|error| CoreError::Transport {
                        detail: error.to_string(),
                    })?;
                late.bind(bound.carrier());
                Ok((Some(Arc::new(bound)), None))
            }
        }
    }
}

fn parse_bind(text: &str) -> Result<SocketAddr, CoreError> {
    text.parse()
        .map_err(|_| CoreError::invalid(format!("not an ip:port: {text}")))
}

#[uniffi::export]
impl CoreNode {
    /// A node with these options, transport and platform implementations. Nothing runs until
    /// `start`.
    ///
    /// # Errors
    ///
    /// [`CoreError::InvalidArgument`] when the constellation id, an op envelope or the iroh bind
    /// address does not parse.
    #[uniffi::constructor]
    pub fn new(
        options: NodeOptions,
        transport: Transport,
        host: Arc<dyn RuntimeHost>,
        hooks: Arc<dyn PlatformHooks>,
    ) -> Result<Arc<Self>, CoreError> {
        let constellation = OpId::from_bytes(parse_hex32("constellation", &options.constellation)?);
        let membership_ops = options
            .membership_ops
            .iter()
            .map(|envelope| {
                SignedOp::decode_envelope(envelope)
                    .map_err(|error| CoreError::invalid(format!("membership op: {error}")))
            })
            .collect::<Result<Vec<_>, _>>()?;
        if let Transport::Iroh { bind_addr, .. } = &transport {
            parse_bind(bind_addr)?;
        }
        let (events, _) = broadcast::channel(64);
        Ok(Arc::new(Self {
            options,
            constellation,
            membership_ops,
            transport,
            host,
            hooks,
            lifecycle: tokio::sync::Mutex::new(()),
            running: Mutex::new(None),
            events,
        }))
    }

    /// Starts the node: asks the platform for the node key, opens the store, starts the node's
    /// runtime, binds the transport and attaches the platform's events. Returns the node id, hex.
    ///
    /// # Errors
    ///
    /// [`CoreError::AlreadyRunning`], [`CoreError::Platform`] when the key cannot be had,
    /// [`CoreError::Store`], [`CoreError::Transport`] when the endpoint cannot bind.
    pub async fn start(&self) -> Result<String, CoreError> {
        let _lifecycle = self.lifecycle.lock().await;
        if self.current().is_ok() {
            return Err(CoreError::AlreadyRunning);
        }
        let secret = self.node_key().await?;
        let key = Arc::new(NodeKey::from_seed(&secret));
        let id = key.node_id();
        let config = self.config(key);
        std::fs::create_dir_all(&config.data_dir).map_err(|error| CoreError::Store {
            detail: format!("data dir: {error}"),
        })?;
        let store = Arc::new(SqliteStore::open(config.data_dir.join(STORE_FILE))?);
        let late = Arc::new(LateCarrier::new(id));
        let carrier: Arc<dyn Carrier> = match &self.transport {
            Transport::InProcess { network } => {
                Arc::new(network.inner.carrier(VerifiedPeer::Node(id)))
            }
            Transport::Iroh { .. } => Arc::clone(&late) as Arc<dyn Carrier>,
        };
        let host = Arc::new(HostAdapter(Arc::clone(&self.host)));
        let node = Node::start(config, store, carrier, host).await?;
        let (endpoint, network) = match self.bind(&node, secret, &late).await {
            Ok(bound) => bound,
            Err(error) => {
                node.shutdown().await;
                return Err(error);
            }
        };
        let runtime = node.runtime();
        let platform = Arc::new(PlatformEvents::new(
            self.events.clone(),
            runtime.clone(),
            endpoint.clone(),
        ));
        *self.running.lock().unwrap_or_else(PoisonError::into_inner) = Some(Arc::new(Running {
            node,
            runtime,
            endpoint,
            network,
            platform: Arc::clone(&platform),
        }));
        self.hooks.attach(platform);
        Ok(id.to_hex())
    }

    /// Stops the node: detaches the platform's events, closes the transport, stops every agent
    /// actor and the catalog, then the node's runtime. Every stream of the node ends. Stopping a
    /// node that is not running does nothing.
    ///
    /// # Errors
    ///
    /// None today; the signature leaves room for a stop that can fail.
    pub async fn stop(&self) -> Result<(), CoreError> {
        let _lifecycle = self.lifecycle.lock().await;
        let Some(running) = self
            .running
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .take()
        else {
            return Ok(());
        };
        self.hooks.detach();
        running.platform.close();
        let _ = self.events.send(NodeEvent::Stopping);
        if let Some(endpoint) = running.endpoint.clone() {
            let _ = hop(&running.runtime, async move { endpoint.shutdown().await }).await;
        }
        if let Some(network) = &running.network {
            network.stop(&running.node.id());
        }
        // Calls still in flight hold the node; wait a little for them, then shut it down here. If
        // one is still running after that, the last to finish drops the node, which stops its
        // runtime.
        let runtime = running.runtime.clone();
        let sole = hop(&runtime, async move {
            let deadline = tokio::time::Instant::now() + STOP_DRAIN;
            while Arc::strong_count(&running) > 1 && tokio::time::Instant::now() < deadline {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
            Arc::try_unwrap(running).ok()
        })
        .await
        .ok()
        .flatten();
        if let Some(running) = sole {
            running.node.shutdown().await;
        }
        Ok(())
    }

    /// Whether the node is running.
    #[must_use]
    pub fn is_running(&self) -> bool {
        self.current().is_ok()
    }

    /// The node id, hex.
    ///
    /// # Errors
    ///
    /// [`CoreError::NotRunning`].
    pub fn node_id(&self) -> Result<String, CoreError> {
        Ok(self.current()?.node.id().to_hex())
    }

    /// Where other nodes can dial this one: its id and, on iroh, the direct addresses and relay its
    /// endpoint knows now.
    ///
    /// # Errors
    ///
    /// [`CoreError::NotRunning`].
    pub fn address(&self) -> Result<PeerAddress, CoreError> {
        let running = self.current()?;
        let addr = running.endpoint.as_ref().map_or_else(
            || NodeAddr::new(running.node.id()),
            |endpoint| endpoint.addr(),
        );
        Ok(PeerAddress::from_core(&addr))
    }

    /// Keeps a session with `peer` (the agent logs and the catalog), redialling with backoff for
    /// as long as the node runs. Returns once the session is set up to run; it does not wait for
    /// the first dial.
    ///
    /// # Errors
    ///
    /// [`CoreError::NotRunning`], [`CoreError::InvalidArgument`] for a bad address.
    pub async fn connect(&self, peer: PeerAddress) -> Result<(), CoreError> {
        let running = self.current()?;
        let peer = peer.to_core()?;
        let task = Arc::clone(&running);
        hop(
            &running.runtime,
            async move { task.node.connect(peer).await },
        )
        .await??;
        Ok(())
    }

    /// node.info v1 for this node.
    ///
    /// # Errors
    ///
    /// [`CoreError::NotRunning`].
    pub fn info(&self) -> Result<NodeInfo, CoreError> {
        Ok(self.current()?.node.info().into())
    }

    /// The I6 runtime parity this node reports (`letta-code@<version>+lcp:<sha256>`).
    ///
    /// # Errors
    ///
    /// [`CoreError::NotRunning`].
    pub fn runtime_parity(&self) -> Result<String, CoreError> {
        Ok(self.current()?.node.runtime_parity().to_owned())
    }

    /// The membership view the node holds now.
    ///
    /// # Errors
    ///
    /// [`CoreError::NotRunning`].
    pub fn membership(&self) -> Result<MembershipView, CoreError> {
        Ok(MembershipView::from(
            &*self.current()?.node.membership().view(),
        ))
    }

    /// The documents the node's catalog registry lists.
    ///
    /// # Errors
    ///
    /// [`CoreError::NotRunning`], [`CoreError::Store`].
    pub async fn catalog_documents(&self) -> Result<Vec<CatalogDocument>, CoreError> {
        let running = self.current()?;
        let store = Arc::clone(running.node.store());
        let docs = hop(&running.runtime, async move {
            store.read(|txn| txn.catalog_docs())
        })
        .await??;
        Ok(docs.into_iter().map(CatalogDocument::from).collect())
    }

    /// The typed view of a registered catalog document (catalog-v1 §3.7: the constellation, shell
    /// or agent view, with its violations), as JSON, checked against this node's membership.
    ///
    /// # Errors
    ///
    /// [`CoreError::NotRunning`], [`CoreError::InvalidArgument`] for a bad URL,
    /// [`CoreError::Catalog`] for a document the node does not hold.
    pub async fn catalog_view_json(&self, url: String) -> Result<String, CoreError> {
        let running = self.current()?;
        let url = DocUrl::from_bs58(url.trim_start_matches("automerge:"))
            .map_err(|error| CoreError::invalid(error.to_string()))?;
        let catalog = running.node.catalog().clone();
        let membership = running.node.membership().view();
        let view = hop(&running.runtime, async move {
            let context = ViewContext {
                membership: Some(&membership),
                placement_hwm: None,
            };
            catalog.view(&url, &context).await
        })
        .await??;
        serde_json::to_string(&view).map_err(|error| CoreError::Catalog {
            detail: error.to_string(),
        })
    }

    /// Where `agent_id` lives according to this node's copy of its log, and this node's input gate
    /// for it; none when the node holds no valid segment of the agent's log.
    ///
    /// # Errors
    ///
    /// [`CoreError::NotRunning`], [`CoreError::Store`] or [`CoreError::Log`].
    pub async fn placement(&self, agent_id: String) -> Result<Option<PlacementStatus>, CoreError> {
        let running = self.current()?;
        let task = Arc::clone(&running);
        let agent = AgentId::new(agent_id);
        hop(&running.runtime, async move {
            let Some(record) = task.node.placement(&agent)? else {
                return Ok(None);
            };
            let gate = task.node.gate(&agent)?;
            Ok(Some(PlacementStatus::of(&record, gate)))
        })
        .await?
    }

    /// Streams the membership view: the current one at once, then one per change, until the
    /// handle is cancelled or the node stops.
    ///
    /// # Errors
    ///
    /// [`CoreError::NotRunning`].
    pub fn subscribe_membership(
        &self,
        listener: Arc<dyn MembershipListener>,
    ) -> Result<Arc<TaskHandle>, CoreError> {
        let running = self.current()?;
        let mut views = running.node.membership().subscribe();
        Ok(TaskHandle::spawn(&running.runtime, async move {
            loop {
                let view = {
                    let current = views.borrow_and_update();
                    MembershipView::from(&**current)
                };
                listener.on_view(view);
                if views.changed().await.is_err() {
                    break;
                }
            }
        }))
    }

    /// Streams catalog events until the handle is cancelled or the node stops.
    ///
    /// # Errors
    ///
    /// [`CoreError::NotRunning`].
    pub fn subscribe_catalog_events(
        &self,
        listener: Arc<dyn CatalogEventListener>,
    ) -> Result<Arc<TaskHandle>, CoreError> {
        let running = self.current()?;
        let receiver = running.node.catalog().events();
        let lagging = Arc::clone(&listener);
        Ok(TaskHandle::spawn(
            &running.runtime,
            forward(
                receiver,
                move |event| listener.on_event(CatalogEvent::from(event)),
                move |missed| lagging.on_event(CatalogEvent::Lagged { missed }),
            ),
        ))
    }

    /// Streams the node's own events (lifecycle, platform reports) until the handle is cancelled
    /// or the node stops.
    ///
    /// # Errors
    ///
    /// [`CoreError::NotRunning`].
    pub fn subscribe_node_events(
        &self,
        listener: Arc<dyn NodeEventListener>,
    ) -> Result<Arc<TaskHandle>, CoreError> {
        let running = self.current()?;
        Ok(TaskHandle::spawn(
            &running.runtime,
            forward(
                self.events.subscribe(),
                move |event| listener.on_event(event),
                |_| {},
            ),
        ))
    }
}
