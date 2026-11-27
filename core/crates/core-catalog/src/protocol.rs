//! The catalog node: a samod repository on the node store, behind the access gate, served on
//! `meridian/automerge-repo/1` through core-carrier.
//!
//! - Layer 0 is [`CatalogAccess`], an [`AccessHook`] over the current [`CatalogPolicy`]: members and
//!   notebook-grant principals pass, others are closed `4403 peer_not_allowed` before any handler.
//! - Layer 1 is the link driver: the principal is the carrier's verified peer, the policy standing is
//!   taken at admission, and a membership change that moves it closes the link (`4403` on revoke,
//!   `4409 caps_changed` otherwise).
//! - Layer 2 is [`DocGate`] on every frame in both directions.
//!
//! Frames are a u32 big-endian length and one samod message, at most 4 MiB, on the link's one byte
//! stream (`tokio_util`'s length-delimited codec). The driver reads the clock; the gate does not.

use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use async_trait::async_trait;
use automerge::transaction::{CommitOptions, Transaction};
use automerge::{AutoCommit, Automerge};
use core_carrier::{
    AccessDecision, AccessHook, AppClose, Carrier, LinkClosure, NodeAddr, StreamHandler,
    StreamLink, VerifiedPeer,
};
use core_proto::NodeId;
use core_proto::alpn::Alpn;
use core_proto::closure::Closure;
use core_store::{CatalogDoc, DocId, DocState, Store, StoreError};
use futures::{SinkExt, StreamExt};
use samod::{PeerId, Repo, Transport};
use tokio::sync::{broadcast, watch};
use tokio_util::codec::{Framed, LengthDelimitedCodec};

use crate::canonical::canonical_view;
use crate::error::CatalogError;
use crate::gate::{DocGate, GateAccess, QuarantineReason, Verdict};
use crate::ids::{DocClass, DocUrl, doc_id_of, samod_id};
use crate::policy::{Access, CatalogPolicy, DocPolicy, Standing, closures, standing_change};
use crate::storage::KvSamodStorage;
use crate::view::{CatalogView, ViewContext, view};
use crate::wire::MAX_FRAME;
use crate::write::{Genesis, genesis};

/// The ALPN the catalog is served on.
pub const ALPN: &[u8] = Alpn::AutomergeRepo.as_bytes();

/// Something the catalog reports to the node (and the shell).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum CatalogEvent {
    /// A peer sent changes it may not make, or malformed ones (`catalog_violation`).
    Violation {
        /// The principal.
        principal: NodeId,
        /// The document.
        doc_id: DocId,
        /// The quarantine reason.
        reason: QuarantineReason,
    },
    /// A link ended.
    LinkClosed {
        /// The principal.
        principal: NodeId,
        /// How it ended.
        closure: LinkClosure,
    },
}

/// The wall clock in milliseconds, as the driver reads it.
pub type Clock = Arc<dyn Fn() -> u64 + Send + Sync>;

/// The system clock.
#[must_use]
pub fn system_clock() -> Clock {
    Arc::new(|| {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |elapsed| {
                u64::try_from(elapsed.as_millis()).unwrap_or(u64::MAX)
            })
    })
}

struct Shared<S> {
    ours: NodeId,
    repo: Repo,
    store: Arc<S>,
    policy: watch::Sender<Arc<CatalogPolicy>>,
    events: broadcast::Sender<CatalogEvent>,
    clock: Clock,
    links: Mutex<Vec<Arc<dyn core_carrier::LinkControl>>>,
}

/// One node's catalog: its samod repository, policy and links.
pub struct CatalogNode<S> {
    shared: Arc<Shared<S>>,
}

impl<S> Clone for CatalogNode<S> {
    fn clone(&self) -> Self {
        Self {
            shared: Arc::clone(&self.shared),
        }
    }
}

/// samod's announce policy over the current catalog policy. samod's peer id is bound to the
/// authenticated endpoint id by the gate, so the outbound filter and this agree.
#[derive(Clone)]
struct Announce(watch::Receiver<Arc<CatalogPolicy>>);

impl samod::AnnouncePolicy for Announce {
    fn should_announce(
        &self,
        doc_id: samod::DocumentId,
        peer_id: PeerId,
    ) -> impl Future<Output = bool> + Send + 'static {
        let policy = Arc::clone(&self.0.borrow());
        let allowed = match (doc_id_of(&doc_id), peer_id.as_str().parse::<NodeId>()) {
            (Ok(doc), Ok(peer)) => policy.may(&peer, &doc) >= Access::Read,
            _ => false,
        };
        async move { allowed }
    }
}

impl<S: Store + 'static> CatalogNode<S> {
    /// Starts the catalog of node `ours` on `store`, under `policy`. samod's peer id is the endpoint
    /// id hex.
    pub async fn start(ours: NodeId, store: Arc<S>, policy: CatalogPolicy, clock: Clock) -> Self {
        let (policy, receiver) = watch::channel(Arc::new(policy));
        let repo = Repo::build_tokio()
            .with_storage(KvSamodStorage::new(Arc::clone(&store)))
            .with_peer_id(PeerId::from(ours.to_hex()))
            .with_announce_policy(Announce(receiver))
            .load()
            .await;
        let (events, _) = broadcast::channel(256);
        Self {
            shared: Arc::new(Shared {
                ours,
                repo,
                store,
                policy,
                events,
                clock,
                links: Mutex::new(Vec::new()),
            }),
        }
    }

    /// This node's id.
    #[must_use]
    pub fn id(&self) -> NodeId {
        self.shared.ours
    }

    /// The current policy.
    #[must_use]
    pub fn policy(&self) -> Arc<CatalogPolicy> {
        Arc::clone(&self.shared.policy.borrow())
    }

    /// Replaces the policy (after a membership op, a registry change or a grants change). Every
    /// link whose principal's standing moved is closed.
    pub fn set_policy(&self, policy: CatalogPolicy) {
        self.shared.policy.send_replace(Arc::new(policy));
    }

    /// Reads the registry from the store and republishes the policy with it.
    ///
    /// # Errors
    ///
    /// A store error.
    pub fn refresh_registry(&self) -> Result<(), StoreError> {
        let docs = self.shared.store.read(|txn| txn.catalog_docs())?;
        let current = self.policy();
        self.set_policy(CatalogPolicy::new(
            current.membership().clone(),
            &docs,
            current.grants().clone(),
        ));
        Ok(())
    }

    /// Catalog events.
    #[must_use]
    pub fn events(&self) -> broadcast::Receiver<CatalogEvent> {
        self.shared.events.subscribe()
    }

    /// The access hook for the node's [`core_carrier::Protocols`].
    #[must_use]
    pub fn access_hook(&self) -> CatalogAccess {
        CatalogAccess {
            policy: self.shared.policy.subscribe(),
        }
    }

    /// The stream handler for [`ALPN`].
    #[must_use]
    pub fn handler(&self) -> CatalogProtocol<S> {
        CatalogProtocol { node: self.clone() }
    }

    /// Dials `target` through `carrier` and keeps a samod connection to it (samod redials with
    /// backoff when the link is lost or closed).
    ///
    /// # Errors
    ///
    /// [`CatalogError::Stopped`] when the repository stopped.
    pub fn dial(
        &self,
        carrier: Arc<dyn Carrier>,
        target: NodeAddr,
    ) -> Result<samod::DialerHandle, CatalogError> {
        let dialer = CarrierDialer {
            carrier,
            target,
            node: self.clone(),
        };
        self.shared
            .repo
            .dial(
                samod::BackoffConfig {
                    initial_delay: Duration::from_millis(200),
                    max_delay: Duration::from_secs(30),
                    max_retries: None,
                },
                Arc::new(dialer),
            )
            .map_err(|_| CatalogError::Stopped)
    }

    /// Creates a catalog document of `class` with its genesis change, registers it, and returns
    /// its URL.
    ///
    /// # Errors
    ///
    /// A schema, store or repository error.
    pub async fn create(&self, class: DocClass, init: &Genesis) -> Result<DocUrl, CatalogError> {
        let mut doc = AutoCommit::new();
        genesis(&mut doc, class, init)?;
        doc.commit_with(CommitOptions::default().with_time(init.created_at_ms / 1000));
        let agent = init.agent_id.clone().map(core_proto::AgentId::new);
        self.import(class, agent, Automerge::load(&doc.save())?)
            .await
    }

    /// Puts an existing document (a notebook, a rollover successor) into the repository under a
    /// fresh id and registers it as `class`.
    ///
    /// # Errors
    ///
    /// A store or repository error.
    pub async fn import(
        &self,
        class: DocClass,
        agent_id: Option<core_proto::AgentId>,
        doc: Automerge,
    ) -> Result<DocUrl, CatalogError> {
        let handle = self
            .shared
            .repo
            .create(doc)
            .await
            .map_err(|_| CatalogError::Stopped)?;
        let id = doc_id_of(handle.document_id())?;
        self.register(class, id, agent_id)?;
        Ok(DocUrl::new(id))
    }

    /// Registers a document the constellation lists (so the gate serves it) and republishes the
    /// policy. A peer then `find`s it.
    ///
    /// # Errors
    ///
    /// A store error.
    pub fn register(
        &self,
        class: DocClass,
        doc_id: DocId,
        agent_id: Option<core_proto::AgentId>,
    ) -> Result<(), CatalogError> {
        let registered = CatalogDoc {
            doc_id,
            class: class.as_str().to_owned(),
            agent_id,
            state: DocState::Live,
            registered_at_ms: (self.shared.clock)(),
        };
        self.shared
            .store
            .write(|txn| txn.register_doc(&registered))?;
        self.refresh_registry()?;
        Ok(())
    }

    /// Asks connected peers for a registered document (after a directory change listed it).
    ///
    /// # Errors
    ///
    /// [`CatalogError::Stopped`].
    pub async fn find(&self, url: &DocUrl) -> Result<Option<samod::DocHandle>, CatalogError> {
        self.shared
            .repo
            .find(samod_id(&url.doc_id()))
            .await
            .map_err(|_| CatalogError::Stopped)
    }

    /// Writes a registered document in one transaction. Refused when this node may only read it.
    ///
    /// # Errors
    ///
    /// [`CatalogError::NotPermitted`], [`CatalogError::UnknownDocument`], or the writer's error.
    pub async fn write<R: Send + 'static>(
        &self,
        url: &DocUrl,
        f: impl FnOnce(&mut Transaction<'_>) -> Result<R, CatalogError> + Send + 'static,
    ) -> Result<R, CatalogError> {
        if self.policy().may(&self.shared.ours, &url.doc_id()) < Access::Write {
            return Err(CatalogError::NotPermitted(url.to_string()));
        }
        let handle = self
            .find(url)
            .await?
            .ok_or_else(|| CatalogError::UnknownDocument(url.to_string()))?;
        let now_s = i64::try_from((self.shared.clock)() / 1000).unwrap_or(i64::MAX);
        tokio::task::spawn_blocking(move || {
            handle.with_document(|doc| {
                let mut tx = doc.transaction();
                let out = f(&mut tx)?;
                tx.commit_with(CommitOptions::default().with_time(now_s));
                Ok(out)
            })
        })
        .await
        .map_err(|_| CatalogError::Stopped)?
    }

    /// The canonical JSON of a document this node holds.
    ///
    /// # Errors
    ///
    /// [`CatalogError::UnknownDocument`].
    pub async fn canonical(&self, url: &DocUrl) -> Result<serde_json::Value, CatalogError> {
        let handle = self
            .find(url)
            .await?
            .ok_or_else(|| CatalogError::UnknownDocument(url.to_string()))?;
        Ok(handle.with_document(|doc| canonical_view(&*doc)))
    }

    /// The typed view of a registered document.
    ///
    /// # Errors
    ///
    /// [`CatalogError::UnknownDocument`].
    pub async fn view(
        &self,
        url: &DocUrl,
        ctx: &ViewContext<'_>,
    ) -> Result<CatalogView, CatalogError> {
        let class = self
            .policy()
            .class(&url.doc_id())
            .ok_or_else(|| CatalogError::UnknownDocument(url.to_string()))?;
        let root = self.canonical(url).await?;
        Ok(view(class, &root, ctx))
    }

    /// The heads of a document this node holds, hex.
    ///
    /// # Errors
    ///
    /// [`CatalogError::UnknownDocument`].
    pub async fn heads(&self, url: &DocUrl) -> Result<Vec<String>, CatalogError> {
        let handle = self
            .find(url)
            .await?
            .ok_or_else(|| CatalogError::UnknownDocument(url.to_string()))?;
        Ok(handle.with_document(|doc| doc.get_heads().iter().map(ToString::to_string).collect()))
    }

    /// Closes every open link and stops the repository.
    pub async fn stop(&self) {
        let links: Vec<_> = self
            .shared
            .links
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .drain(..)
            .collect();
        for link in links {
            link.close(AppClose::closed());
        }
        self.shared.repo.stop().await;
    }

    /// Runs the gate between `link` and samod: returns samod's transport for the link and spawns
    /// the driver that moves frames until the link closes.
    fn drive(&self, link: StreamLink) -> Result<Transport, Closure> {
        let Some(principal) = link.control.remote().node_id().copied() else {
            return Err(closures::PEER_NOT_ALLOWED);
        };
        let mut policy = self.shared.policy.subscribe();
        let admitted = policy.borrow_and_update().standing(&principal);
        if admitted == Standing::Refused {
            return Err(closures::PEER_NOT_ALLOWED);
        }
        let (to_samod, from_link) = futures::channel::mpsc::unbounded::<Vec<u8>>();
        let (to_link, from_samod) = futures::channel::mpsc::unbounded::<Vec<u8>>();
        let transport = Transport::new(from_link.map(Ok::<_, std::convert::Infallible>), to_link);
        self.shared
            .links
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .push(Arc::clone(&link.control));
        let driver = Driver {
            shared: Arc::clone(&self.shared),
            principal,
            gate: DocGate::new(&self.shared.ours, &principal),
            admitted,
        };
        tokio::spawn(driver.run(link, policy, to_samod, from_samod));
        Ok(transport)
    }
}

/// The access the gate sees for one principal under the current policy.
struct LinkAccess<'a> {
    policy: &'a CatalogPolicy,
    principal: NodeId,
    ours: NodeId,
}

impl GateAccess for LinkAccess<'_> {
    fn peer(&self, doc: &DocId) -> Option<(DocClass, Access)> {
        let class = self.policy.class(doc)?;
        Some((class, self.policy.may(&self.principal, doc)))
    }

    fn local(&self, doc: &DocId) -> Access {
        self.policy.may(&self.ours, doc)
    }
}

struct Driver<S> {
    shared: Arc<Shared<S>>,
    principal: NodeId,
    gate: DocGate,
    admitted: Standing,
}

impl<S: Store + 'static> Driver<S> {
    async fn run(
        mut self,
        link: StreamLink,
        mut policy: watch::Receiver<Arc<CatalogPolicy>>,
        to_samod: futures::channel::mpsc::UnboundedSender<Vec<u8>>,
        mut from_samod: futures::channel::mpsc::UnboundedReceiver<Vec<u8>>,
    ) {
        let control = link.control;
        let codec = LengthDelimitedCodec::builder()
            .length_field_length(4)
            .max_frame_length(MAX_FRAME)
            .new_codec();
        let mut framed = Framed::new(link.stream, codec);
        loop {
            tokio::select! {
                inbound = framed.next() => match inbound {
                    Some(Ok(frame)) => {
                        if let Some(close) = self.inbound(&frame, &policy, &to_samod, &mut framed).await {
                            control.close(close.into());
                            break;
                        }
                    }
                    Some(Err(error)) => {
                        let close = if error.kind() == std::io::ErrorKind::InvalidData {
                            closures::FRAME_TOO_LARGE
                        } else {
                            closures::MALFORMED_FRAME
                        };
                        if control.closure().is_none() {
                            control.close(close.into());
                        }
                        break;
                    }
                    None => break,
                },
                outbound = from_samod.next() => {
                    let Some(frame) = outbound else {
                        if control.closure().is_none() {
                            control.close(AppClose::closed());
                        }
                        break;
                    };
                    let current = Arc::clone(&policy.borrow());
                    let access = self.access(&current);
                    let bytes = match self.gate.outbound(&frame, &access) {
                        Verdict::PassOriginal => frame,
                        Verdict::PassRewritten(bytes) => bytes,
                        _ => continue,
                    };
                    if framed.send(bytes::Bytes::from(bytes)).await.is_err() {
                        break;
                    }
                }
                changed = policy.changed() => {
                    if changed.is_err() {
                        break;
                    }
                    let standing = policy.borrow_and_update().standing(&self.principal);
                    if let Some(close) = standing_change(&self.admitted, &standing) {
                        control.close(close.into());
                        break;
                    }
                }
                closure = control.closed() => {
                    let _ = closure;
                    break;
                }
            }
        }
        drop(to_samod);
        let closure = control.closure().unwrap_or(LinkClosure::Lost);
        self.shared
            .links
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .retain(|other| !Arc::ptr_eq(other, &control));
        let _ = self.shared.events.send(CatalogEvent::LinkClosed {
            principal: self.principal,
            closure,
        });
    }

    fn access<'a>(&self, policy: &'a CatalogPolicy) -> LinkAccess<'a> {
        LinkAccess {
            policy,
            principal: self.principal,
            ours: self.shared.ours,
        }
    }

    /// Handles one inbound frame; `Some(close)` ends the link.
    async fn inbound<T>(
        &mut self,
        frame: &[u8],
        policy: &watch::Receiver<Arc<CatalogPolicy>>,
        to_samod: &futures::channel::mpsc::UnboundedSender<Vec<u8>>,
        framed: &mut Framed<T, LengthDelimitedCodec>,
    ) -> Option<Closure>
    where
        T: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
    {
        let current = Arc::clone(&policy.borrow());
        let now = (self.shared.clock)();
        let decision = {
            let access = self.access(&current);
            self.gate.inbound(frame, &access, now)
        };
        if let Some(quarantined) = decision.quarantine {
            let store = Arc::clone(&self.shared.store);
            let principal = self.principal;
            let row = quarantined.clone();
            let _ = tokio::task::spawn_blocking(move || {
                store.write(|txn| crate::quarantine::record(txn, &principal, &row, now))
            })
            .await;
            let _ = self.shared.events.send(CatalogEvent::Violation {
                principal: self.principal,
                doc_id: quarantined.doc_id,
                reason: quarantined.reason,
            });
        }
        match decision.verdict {
            Verdict::PassOriginal => {
                let _ = to_samod.unbounded_send(frame.to_vec());
                None
            }
            Verdict::PassRewritten(bytes) => {
                let _ = to_samod.unbounded_send(bytes);
                None
            }
            Verdict::Drop => None,
            Verdict::Reply(bytes) => {
                let _ = framed.send(bytes::Bytes::from(bytes)).await;
                None
            }
            Verdict::Close(close) => Some(close),
        }
    }
}

/// Layer 0: the [`AccessHook`] over the current policy.
#[derive(Clone)]
pub struct CatalogAccess {
    policy: watch::Receiver<Arc<CatalogPolicy>>,
}

#[async_trait]
impl AccessHook for CatalogAccess {
    async fn check(&self, peer: &VerifiedPeer, alpn: &[u8]) -> AccessDecision {
        if alpn != ALPN {
            return AccessDecision::Admit;
        }
        let admitted = match peer {
            VerifiedPeer::LocalShell => false,
            VerifiedPeer::Node(id) => self.policy.borrow().admits(id),
        };
        if admitted {
            AccessDecision::Admit
        } else {
            AccessDecision::Reject(closures::PEER_NOT_ALLOWED.into())
        }
    }
}

/// The [`StreamHandler`] for [`ALPN`]: hands each accepted link to samod through the gate.
pub struct CatalogProtocol<S> {
    node: CatalogNode<S>,
}

#[async_trait]
impl<S: Store + 'static> StreamHandler for CatalogProtocol<S> {
    async fn serve(&self, link: StreamLink) {
        let control = Arc::clone(&link.control);
        let url = format!("iroh://{}", control.remote());
        let transport = match self.node.drive(link) {
            Ok(transport) => transport,
            Err(close) => {
                control.close(close.into());
                return;
            }
        };
        let Ok(url) = url.parse() else {
            control.close(closures::MALFORMED_FRAME.into());
            return;
        };
        let Ok(acceptor) = self.node.shared.repo.make_acceptor(url) else {
            control.close(AppClose::closed());
            return;
        };
        if acceptor.accept(transport).is_err() {
            control.close(AppClose::closed());
            return;
        }
        // The acceptor waits for the dialler to close.
        control.closed().await;
    }
}

/// samod's dialer over a core-carrier byte-stream link.
struct CarrierDialer<S> {
    carrier: Arc<dyn Carrier>,
    target: NodeAddr,
    node: CatalogNode<S>,
}

impl<S: Store + 'static> samod::Dialer for CarrierDialer<S> {
    fn url(&self) -> samod::Url {
        format!("iroh://{}", self.target.id)
            .parse()
            .expect("an iroh URL parses")
    }

    fn connect(
        &self,
    ) -> futures::future::BoxFuture<
        'static,
        Result<Transport, Box<dyn std::error::Error + Send + Sync + 'static>>,
    > {
        let carrier = Arc::clone(&self.carrier);
        let target = self.target.clone();
        let node = self.node.clone();
        Box::pin(async move {
            let link = carrier.dial_stream(&target, ALPN).await?;
            let control = Arc::clone(&link.control);
            node.drive(link).map_err(|close| {
                control.close(close.into());
                Box::new(core_carrier::CarrierError::Closed(close.into()))
                    as Box<dyn std::error::Error + Send + Sync>
            })
        })
    }
}
