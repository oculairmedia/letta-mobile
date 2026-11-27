//! The node: one tokio runtime holding the store, membership, the catalog, the carrier with the
//! node's protocols, the log hub and one actor per agent log.

use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use async_trait::async_trait;
use backon::{BackoffBuilder as _, ExponentialBuilder};
use core_carrier::{
    AcceptedLink, AccessDecision, AccessHook, ByteStream, Carrier, Frame, MembershipAccess,
    NodeAddr, ProtocolHandler, Protocols, StreamHandler, StreamLink, VerifiedPeer,
};
use core_catalog::{CatalogAccess, CatalogNode, CatalogPolicy};
use core_log::allowlist::STORE_FORMAT;
use core_log::driver::{Hub, Notice, run_session};
use core_log::payload::Payload;
use core_log::{AgentLog, LogError, Mode, Pos, SessionConfig, SessionEvent, SignedEntry};
use core_membership::{OpId, SignedOp};
use core_placement::{Gate, NodeClass, PlaceMessage, PlacementRecord};
use core_projection::ProjectionError;
use core_proto::alpn::Alpn;
use core_proto::crypto::NodeKey;
use core_proto::frames::{AdminRpcResponse, AuthResponse, ControlFrame, ErrorFrame, Pairing};
use core_proto::{AgentId, NodeId};
use core_store::{LogId, PayloadState, Store, StoreError};
use thiserror::Error;
use tokio::runtime::Runtime;
use tokio::sync::oneshot;

use crate::actor::{self, AgentHandle, Command};
use crate::clock::NodeClock;
use crate::host::{RuntimeError, RuntimeHost};
use crate::info::{NodeInfo, node_info, runtime_parity};
use crate::membership::{LiveJudge, MemberQuery, Membership};
use crate::wire::{self, LOG_ALPN, MEMBER_ALPN, MemberPush, PLACE_ALPN};

/// Why a node operation failed.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
pub enum NodeError {
    /// The log refused.
    #[error("log: {0}")]
    Log(#[from] LogError),
    /// The store failed.
    #[error("store: {0}")]
    Store(#[from] StoreError),
    /// The projection failed.
    #[error("projection: {0}")]
    Projection(#[from] ProjectionError),
    /// The runtime host failed.
    #[error("runtime: {0}")]
    Runtime(#[from] RuntimeError),
    /// A move ended without committing (the wire reason).
    #[error("move aborted: {0}")]
    MoveAborted(String),
    /// The request was refused (the reason).
    #[error("refused: {0}")]
    Refused(String),
    /// The node or the agent's actor has stopped.
    #[error("stopped")]
    Stopped,
}

/// How a node is set up.
#[derive(Debug)]
pub struct NodeConfig {
    /// The node's key; its public half is the NodeId.
    pub key: Arc<NodeKey>,
    /// letta-code's local backend directory.
    pub backend_dir: PathBuf,
    /// The node's own data directory (staging, the hooks dir).
    pub data_dir: PathBuf,
    /// The node's class, as node.info reports it.
    pub class: NodeClass,
    /// The Baken build.
    pub app_version: String,
    /// Tokio worker threads (2 on phones, 4 on desktop).
    pub worker_threads: usize,
    /// The constellation (the genesis op id).
    pub constellation: OpId,
    /// The membership ops the node holds at start.
    pub membership_ops: Vec<SignedOp>,
    /// Classes other nodes reported in node.info (failover ranking).
    pub peer_classes: BTreeMap<NodeId, NodeClass>,
    /// The agent actors' tick (move deadlines, failover, periodic checkpoints).
    pub tick: Duration,
    /// The backend watcher's debounce.
    pub watch_debounce: Duration,
}

impl NodeConfig {
    /// A config with the defaults: desktop class, 4 workers, a 500 ms tick, a 200 ms debounce.
    #[must_use]
    pub fn new(
        key: Arc<NodeKey>,
        backend_dir: PathBuf,
        data_dir: PathBuf,
        constellation: OpId,
        membership_ops: Vec<SignedOp>,
    ) -> Self {
        Self {
            key,
            backend_dir,
            data_dir,
            class: NodeClass::Desktop,
            app_version: "unknown".into(),
            worker_threads: 4,
            constellation,
            membership_ops,
            peer_classes: BTreeMap::new(),
            tick: Duration::from_millis(500),
            watch_debounce: Duration::from_millis(200),
        }
    }

    /// The node's hooks directory for memfs (I3).
    #[must_use]
    pub fn hooks_dir(&self) -> PathBuf {
        self.data_dir.join("hooks")
    }
}

/// What every task of the node shares.
pub(crate) struct Inner<S> {
    pub(crate) key: Arc<NodeKey>,
    pub(crate) id: NodeId,
    pub(crate) config: NodeConfig,
    pub(crate) store: Arc<S>,
    pub(crate) hub: Hub,
    pub(crate) carrier: Arc<dyn Carrier>,
    pub(crate) host: Arc<dyn RuntimeHost>,
    pub(crate) membership: Membership,
    pub(crate) catalog: CatalogNode<S>,
    pub(crate) clock: NodeClock,
    pub(crate) parity: String,
    actors: Mutex<BTreeMap<LogId, AgentHandle>>,
    peers: Mutex<BTreeMap<NodeId, NodeAddr>>,
    heard: Mutex<BTreeMap<NodeId, u64>>,
    dial_failures: Mutex<BTreeMap<NodeId, u64>>,
    /// Which log a move with a peer is about (replies carry no log id).
    moves: Mutex<BTreeMap<NodeId, LogId>>,
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

impl<S: Store + 'static> Inner<S> {
    /// The actor of `log_id`, started on first use.
    pub(crate) fn actor(self: &Arc<Self>, log_id: LogId) -> AgentHandle {
        let mut actors = lock(&self.actors);
        if let Some(handle) = actors.get(&log_id)
            && !handle.is_closed()
        {
            return handle.clone();
        }
        let handle = actor::spawn(Arc::clone(self), log_id);
        actors.insert(log_id, handle.clone());
        handle
    }

    pub(crate) fn judge(&self) -> LiveJudge {
        LiveJudge::new(&self.membership, None)
    }

    /// The placement record of a log.
    pub(crate) fn record(&self, log_id: LogId) -> Result<Option<PlacementRecord>, NodeError> {
        let judge = self.judge();
        let log = AgentLog::new(log_id, &judge);
        Ok(self.store.read(|txn| PlacementRecord::derive(&log, txn))?)
    }

    /// The canonical line's payloads (held ones), with positions, up to `upto` inclusive.
    pub(crate) fn canonical(
        &self,
        log_id: LogId,
        upto: Option<u64>,
    ) -> Result<Vec<(Pos, Payload)>, NodeError> {
        let judge = self.judge();
        let log = AgentLog::new(log_id, &judge);
        Ok(self.store.read(|txn| {
            let mut out = Vec::new();
            for entry in log.canonical_line(txn)? {
                if upto.is_some_and(|k| entry.seq > k) {
                    break;
                }
                if entry.payload_state != PayloadState::Stored {
                    continue;
                }
                let Some(bytes) = txn.blob(&entry.payload_hash)? else {
                    continue;
                };
                let payload = Payload::decode(entry.kind, &bytes).map_err(LogError::from)?;
                out.push((
                    Pos {
                        seq: entry.seq,
                        hash: entry.entry_hash,
                    },
                    payload,
                ));
            }
            Ok::<_, LogError>(out)
        })?)
    }

    /// Hands newly committed local entries to every log session (live tail) and to the actor.
    pub(crate) fn publish(&self, log_id: LogId, entries: &[SignedEntry]) {
        if entries.is_empty() {
            return;
        }
        self.hub.publish(
            None,
            &Notice {
                log_id,
                entries: entries.iter().map(SignedEntry::hash).collect(),
            },
        );
    }

    pub(crate) fn addr(&self, node: NodeId) -> NodeAddr {
        lock(&self.peers)
            .get(&node)
            .cloned()
            .unwrap_or_else(|| NodeAddr::new(node))
    }

    /// Sends one `baken/place/1` message (fire and forget; a failed dial is a dial failure).
    pub(crate) fn send_place(self: &Arc<Self>, to: NodeId, message: &PlaceMessage) {
        let body = wire::encode_place(message);
        let inner = Arc::clone(self);
        tokio::spawn(async move {
            let target = inner.addr(to);
            if wire::send_one(&*inner.carrier, &target, PLACE_ALPN, body)
                .await
                .is_err()
            {
                inner.dial_failed(to);
            }
        });
    }

    pub(crate) fn note_move(&self, peer: NodeId, log_id: LogId) {
        lock(&self.moves).insert(peer, log_id);
    }

    pub(crate) fn end_move(&self, peer: &NodeId) {
        lock(&self.moves).remove(peer);
    }

    pub(crate) fn heard(&self, node: NodeId) {
        lock(&self.heard).insert(node, self.clock.mono_ms());
    }

    pub(crate) fn last_heard(&self, node: &NodeId) -> Option<u64> {
        lock(&self.heard).get(node).copied()
    }

    pub(crate) fn dial_failed(&self, node: NodeId) {
        lock(&self.dial_failures).insert(node, self.clock.mono_ms());
    }

    pub(crate) fn last_dial_failure(&self, node: &NodeId) -> Option<u64> {
        lock(&self.dial_failures).get(node).copied()
    }

    fn session_config(&self) -> SessionConfig {
        SessionConfig {
            node_id: self.id,
            constellation_id: *self.membership.constellation().as_bytes(),
            member_heads: self
                .membership
                .heads()
                .iter()
                .map(|h| *h.as_bytes())
                .collect(),
            mode: Mode::Live,
            store_format: STORE_FORMAT,
        }
    }

    /// Runs one `baken/log/1` session with `peer` over `stream` until it ends.
    async fn run_log_session(self: &Arc<Self>, peer: NodeId, stream: Box<dyn ByteStream>) {
        self.heard(peer);
        let (reader, writer) = tokio::io::split(stream);
        let judge = self.judge();
        let observer = Arc::clone(self);
        let _ = run_session(
            reader,
            writer,
            &*self.store,
            &judge,
            &self.hub,
            &self.clock,
            self.session_config(),
            move |event| observer.on_session_event(peer, event),
        )
        .await;
    }

    fn on_session_event(self: &Arc<Self>, peer: NodeId, event: &SessionEvent) {
        self.heard(peer);
        match event {
            SessionEvent::Stored { log_id, .. } | SessionEvent::Evaluated { log_id, .. } => {
                self.actor(*log_id).send(Command::LogChanged);
            }
            SessionEvent::MembershipDiffers => self.push_members(peer),
            _ => {}
        }
    }

    /// Pushes every membership op to `peer` (anti-entropy).
    fn push_members(self: &Arc<Self>, peer: NodeId) {
        let push = MemberPush {
            heads: self.membership.heads(),
            ops: self.membership.ops(),
        };
        let inner = Arc::clone(self);
        tokio::spawn(async move {
            let target = inner.addr(peer);
            let _ = wire::send_one(&*inner.carrier, &target, MEMBER_ALPN, push.encode()).await;
        });
    }

    fn on_member_push(self: &Arc<Self>, peer: NodeId, push: MemberPush) {
        let fresh = self.membership.insert(push.ops);
        if fresh {
            let view = self.membership.view();
            let current = self.catalog.policy();
            self.catalog.set_policy(CatalogPolicy::new(
                (*view).clone(),
                &[],
                current.grants().clone(),
            ));
            let _ = self.catalog.refresh_registry();
            let handles: Vec<AgentHandle> = lock(&self.actors).values().cloned().collect();
            for handle in handles {
                handle.send(Command::LogChanged);
            }
        }
        if self.membership.peer_lacks(&push.heads) {
            self.push_members(peer);
        }
    }

    fn on_place(self: &Arc<Self>, peer: NodeId, message: PlaceMessage) {
        self.heard(peer);
        let log_id = match &message {
            PlaceMessage::MoveRequest { log_id, .. }
            | PlaceMessage::Prepare { log_id, .. }
            | PlaceMessage::ClaimRequest { log_id, .. } => Some(*log_id),
            PlaceMessage::Fenced(report) => Some(report.log_id),
            PlaceMessage::Commit { seal } => SignedEntry::from_bytes(seal)
                .ok()
                .map(|entry| entry.header().log_id),
            _ => lock(&self.moves).get(&peer).copied(),
        };
        if let Some(log_id) = log_id {
            self.actor(log_id).send(Command::Place {
                from: peer,
                message,
            });
        }
    }

    /// node.info for this node.
    pub(crate) fn info(&self) -> NodeInfo {
        let view = self.membership.view();
        node_info(
            &self.id,
            Some(&view),
            self.config.class,
            &self.config.app_version,
            &self.host.info(),
        )
    }
}

/// A Baken node.
pub struct Node<S: Store + 'static> {
    inner: Arc<Inner<S>>,
    runtime: Option<Runtime>,
}

impl<S: Store + 'static> std::fmt::Debug for Node<S> {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("Node")
            .field("id", &self.inner.id)
            .finish_non_exhaustive()
    }
}

impl<S: Store + 'static> Node<S> {
    /// Starts a node on its own tokio runtime: membership from the configured ops, the catalog on
    /// `store`, and the node's actors (one per agent log the store already holds).
    ///
    /// # Errors
    ///
    /// [`NodeError::Stopped`] when the runtime cannot start.
    pub async fn start(
        config: NodeConfig,
        store: Arc<S>,
        carrier: Arc<dyn Carrier>,
        host: Arc<dyn RuntimeHost>,
    ) -> Result<Self, NodeError> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(config.worker_threads.max(1))
            .thread_name("baken-node")
            .enable_all()
            .build()
            .map_err(|_| NodeError::Stopped)?;
        let id = config.key.node_id();
        let membership = Membership::new(config.constellation, config.membership_ops.clone());
        let policy = CatalogPolicy::new((*membership.view()).clone(), &[], BTreeMap::new());
        let catalog_store = Arc::clone(&store);
        let catalog = runtime
            .spawn(async move {
                CatalogNode::start(
                    id,
                    catalog_store,
                    policy,
                    core_catalog::protocol::system_clock(),
                )
                .await
            })
            .await
            .map_err(|_| NodeError::Stopped)?;
        let parity = runtime_parity(&host.info());
        let inner = Arc::new(Inner {
            key: Arc::clone(&config.key),
            id,
            config,
            store,
            hub: Hub::new(),
            carrier,
            host,
            membership,
            catalog,
            clock: NodeClock::new(),
            parity,
            actors: Mutex::new(BTreeMap::new()),
            peers: Mutex::new(BTreeMap::new()),
            heard: Mutex::new(BTreeMap::new()),
            dial_failures: Mutex::new(BTreeMap::new()),
            moves: Mutex::new(BTreeMap::new()),
        });
        let logs = inner.store.read(|txn| txn.logs())?;
        let starter = Arc::clone(&inner);
        runtime
            .spawn(async move {
                for log_id in logs {
                    starter.actor(log_id).send(Command::Resume);
                }
            })
            .await
            .map_err(|_| NodeError::Stopped)?;
        Ok(Self {
            inner,
            runtime: Some(runtime),
        })
    }

    /// This node's id.
    #[must_use]
    pub fn id(&self) -> NodeId {
        self.inner.id
    }

    fn handle(&self) -> &tokio::runtime::Handle {
        self.runtime
            .as_ref()
            .map(Runtime::handle)
            .expect("the runtime lives as long as the node")
    }

    /// Runs `future` on the node's runtime and waits for it from any executor.
    async fn on_node<T: Send + 'static>(
        &self,
        future: impl std::future::Future<Output = T> + Send + 'static,
    ) -> Result<T, NodeError> {
        self.handle()
            .spawn(future)
            .await
            .map_err(|_| NodeError::Stopped)
    }

    /// The protocols the node serves, for its transport: the node session (`/letta/appserver/0`),
    /// `baken/log/1`, `baken/place/1`, `baken/member/1` and the catalog's
    /// `meridian/automerge-repo/1`, behind one access hook (members only; membership pushes are
    /// open to all; the catalog applies its own gate).
    #[must_use]
    pub fn protocols(&self) -> Protocols {
        let inner = &self.inner;
        let members = MembershipAccess::new(MemberQuery(inner.membership.subscribe()))
            .open_to_all(MEMBER_ALPN);
        Protocols::builder()
            .access(NodeAccess {
                catalog: inner.catalog.access_hook(),
                members,
            })
            .accept(
                Alpn::AppServer.as_bytes(),
                SessionHandler {
                    inner: Arc::clone(inner),
                },
            )
            .accept_stream(
                LOG_ALPN,
                LogHandler {
                    inner: Arc::clone(inner),
                },
            )
            .accept_stream(
                PLACE_ALPN,
                PlaceHandler {
                    inner: Arc::clone(inner),
                },
            )
            .accept_stream(
                MEMBER_ALPN,
                MemberHandler {
                    inner: Arc::clone(inner),
                },
            )
            .accept_stream(core_catalog::ALPN, inner.catalog.handler())
            .build()
    }

    /// Keeps a `baken/log/1` session (and a catalog sync) with `peer`, redialling with backoff.
    ///
    /// # Errors
    ///
    /// [`NodeError::Stopped`] when the node's runtime has stopped.
    pub async fn connect(&self, peer: NodeAddr) -> Result<(), NodeError> {
        let inner = Arc::clone(&self.inner);
        self.on_node(async move {
            lock(&inner.peers).insert(peer.id, peer.clone());
            let _ = inner.catalog.dial(Arc::clone(&inner.carrier), peer.clone());
            let dialer = Arc::clone(&inner);
            tokio::spawn(async move {
                let schedule = || {
                    ExponentialBuilder::default()
                        .with_min_delay(Duration::from_millis(200))
                        .with_max_delay(Duration::from_secs(30))
                        .without_max_times()
                        .build()
                };
                let mut delays = schedule();
                loop {
                    match dialer.carrier.dial_stream(&peer, LOG_ALPN).await {
                        Ok(link) => {
                            delays = schedule();
                            dialer.run_log_session(peer.id, link.stream).await;
                        }
                        Err(_) => dialer.dial_failed(peer.id),
                    }
                    let delay = delays.next().unwrap_or(Duration::from_secs(30));
                    tokio::time::sleep(delay).await;
                }
            });
        })
        .await
    }

    /// Creates `agent`'s log with this node as holder (genesis), exports its files and writes the
    /// first checkpoint.
    ///
    /// # Errors
    ///
    /// [`NodeError`] when the log exists or the export fails.
    pub async fn create_agent(&self, agent: &AgentId) -> Result<Pos, NodeError> {
        let handle = self.agent(agent);
        let agent = agent.clone();
        self.on_node(async move {
            handle
                .request(|reply| Command::Create { agent, reply })
                .await
        })
        .await?
    }

    /// The handle of `agent`'s actor.
    #[must_use]
    pub fn agent(&self, agent: &AgentId) -> AgentHandle {
        let inner = Arc::clone(&self.inner);
        let log_id = core_log::hashes::log_id(agent);
        let _guard = self.handle().enter();
        inner.actor(log_id)
    }

    /// The placement record of `agent`, from this node's log.
    ///
    /// # Errors
    ///
    /// A store or log error.
    pub fn placement(&self, agent: &AgentId) -> Result<Option<PlacementRecord>, NodeError> {
        self.inner.record(core_log::hashes::log_id(agent))
    }

    /// The input gate of `agent` on this node (a move in its quiesce phase shows as active here;
    /// the actor refuses input itself).
    ///
    /// # Errors
    ///
    /// A store or log error.
    pub fn gate(&self, agent: &AgentId) -> Result<Gate, NodeError> {
        Ok(self
            .placement(agent)?
            .map_or(Gate::Placeless, |record| record.gate(&self.inner.id, false)))
    }

    /// node.info.
    #[must_use]
    pub fn info(&self) -> NodeInfo {
        self.inner.info()
    }

    /// The I6 runtime parity this node reports.
    #[must_use]
    pub fn runtime_parity(&self) -> &str {
        &self.inner.parity
    }

    /// The catalog node.
    #[must_use]
    pub fn catalog(&self) -> &CatalogNode<S> {
        &self.inner.catalog
    }

    /// The node's store.
    #[must_use]
    pub fn store(&self) -> &Arc<S> {
        &self.inner.store
    }

    /// Stops every actor (each stops its runtime and watcher) and the catalog, then the runtime.
    pub async fn shutdown(mut self) {
        let inner = Arc::clone(&self.inner);
        let _ = self
            .on_node(async move {
                let handles: Vec<AgentHandle> = lock(&inner.actors).values().cloned().collect();
                for handle in handles {
                    let (reply, done) = oneshot::channel();
                    handle.send(Command::Stop(reply));
                    let _ = done.await;
                }
                inner.catalog.stop().await;
            })
            .await;
        if let Some(runtime) = self.runtime.take() {
            runtime.shutdown_background();
        }
    }
}

impl<S: Store + 'static> Drop for Node<S> {
    fn drop(&mut self) {
        if let Some(runtime) = self.runtime.take() {
            runtime.shutdown_background();
        }
    }
}

/// The node's access hook: the catalog's gate for its ALPN, membership for everything else.
struct NodeAccess {
    catalog: CatalogAccess,
    members: MembershipAccess<MemberQuery>,
}

#[async_trait]
impl AccessHook for NodeAccess {
    async fn check(&self, peer: &VerifiedPeer, alpn: &[u8]) -> AccessDecision {
        if alpn == core_catalog::ALPN {
            self.catalog.check(peer, alpn).await
        } else {
            self.members.check(peer, alpn).await
        }
    }
}

struct LogHandler<S> {
    inner: Arc<Inner<S>>,
}

#[async_trait]
impl<S: Store + 'static> StreamHandler for LogHandler<S> {
    async fn serve(&self, link: StreamLink) {
        if let Some(peer) = link.control.remote().node_id().copied() {
            self.inner.run_log_session(peer, link.stream).await;
        }
    }
}

struct PlaceHandler<S> {
    inner: Arc<Inner<S>>,
}

#[async_trait]
impl<S: Store + 'static> StreamHandler for PlaceHandler<S> {
    async fn serve(&self, link: StreamLink) {
        let Some(peer) = link.control.remote().node_id().copied() else {
            return;
        };
        if let Some(body) = wire::read_one(link.stream).await
            && let Ok(message) = PlaceMessage::decode_body(&body)
        {
            self.inner.on_place(peer, message);
        }
    }
}

struct MemberHandler<S> {
    inner: Arc<Inner<S>>,
}

#[async_trait]
impl<S: Store + 'static> StreamHandler for MemberHandler<S> {
    async fn serve(&self, link: StreamLink) {
        let Some(peer) = link.control.remote().node_id().copied() else {
            return;
        };
        if let Some(body) = wire::read_one(link.stream).await
            && let Some(push) = MemberPush::decode(&body)
        {
            self.inner.on_member_push(peer, push);
        }
    }
}

/// The node session's control lane, as far as the core serves it: `auth` (members and the
/// in-process shell are paired peers), then `admin_rpc` `node.info`. Turn commands stay with the
/// App Server, which the shell reaches through the runtime host's endpoint.
struct SessionHandler<S> {
    inner: Arc<Inner<S>>,
}

impl<S: Store + 'static> SessionHandler<S> {
    fn admin(&self, frame: &core_proto::frames::AdminRpc) -> AdminRpcResponse {
        let request_id = frame
            .request_id
            .clone()
            .unwrap_or_else(|| core_proto::RequestId::new(""));
        match frame.method.as_deref() {
            Some("node.info") => {
                let mut body = serde_json::Map::new();
                body.insert(
                    "result".into(),
                    serde_json::to_value(self.inner.info()).unwrap_or_default(),
                );
                AdminRpcResponse {
                    request_id: Some(request_id),
                    success: true,
                    error: None,
                    capability: None,
                    body,
                }
            }
            Some(method) => {
                AdminRpcResponse::failed(request_id, format!("Unknown admin method: {method}"))
            }
            None => AdminRpcResponse::failed(request_id, "method is required"),
        }
    }
}

#[async_trait]
impl<S: Store + 'static> ProtocolHandler for SessionHandler<S> {
    async fn serve(&self, mut accepted: AcceptedLink) {
        let peer = accepted.link.remote();
        let paired = match peer {
            VerifiedPeer::LocalShell => true,
            VerifiedPeer::Node(id) => self.inner.membership.view().is_member(&id),
        };
        let mut authenticated = false;
        while let Some(frame) = accepted.control.recv().await {
            let reply = match ControlFrame::parse(frame.as_str()) {
                Err(error) => ControlFrame::Error(ErrorFrame::parse_failure(error.diagnostic())),
                Ok(ControlFrame::Auth(auth)) => match auth.request_id {
                    None => ControlFrame::AuthResponse(AuthResponse::refused(
                        None,
                        core_proto::auth::AuthErrorCode::RequestIdRequired,
                    )),
                    Some(request_id) if paired => {
                        authenticated = true;
                        if let Some(capabilities) = &auth.capabilities {
                            accepted.link.on_peer_capabilities(capabilities);
                        }
                        ControlFrame::AuthResponse(AuthResponse::accepted(
                            request_id,
                            Vec::new(),
                            Pairing::NotReported,
                        ))
                    }
                    Some(request_id) => ControlFrame::AuthResponse(AuthResponse::refused(
                        Some(request_id),
                        core_proto::auth::AuthErrorCode::PairingRequired,
                    )),
                },
                Ok(other) if !authenticated => ControlFrame::Error(ErrorFrame::unauthorized(
                    other.request_id().map(core_proto::RequestId::new).as_ref(),
                )),
                Ok(ControlFrame::AdminRpc(call)) => {
                    ControlFrame::AdminRpcResponse(self.admin(&call))
                }
                Ok(other) => {
                    ControlFrame::Error(ErrorFrame::unknown_command_type(Some(other.type_name())))
                }
            };
            if accepted
                .link
                .send_control(Frame::new(reply.to_json()))
                .await
                .is_err()
            {
                break;
            }
        }
    }
}
