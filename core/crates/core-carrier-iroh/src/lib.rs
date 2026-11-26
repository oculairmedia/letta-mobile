//! The Iroh carrier: the node-carrier v1 SPI over iroh 1.x QUIC connections. This is the only crate
//! in the core that names iroh.
//!
//! - One [`IrohNode`] per node: one iroh `Endpoint` (one key, one NodeId) and one iroh `Router`
//!   that serves every ALPN of the node's [`Protocols`].
//! - The node's [`AccessHook`](core_carrier::AccessHook) runs inside the endpoint's connection hook
//!   (`EndpointHooks::after_handshake`), so a rejected peer's connection is closed with the hook's
//!   code and reason before the router hands it to any protocol handler.
//! - Lanes are QUIC bi-directional streams, mapped as node-carrier v1 says: the dialler's first
//!   stream is the control lane, its second the push lane (whose send half it finishes at once),
//!   and every later stream one call lane. Frames are core-proto's codec, with `frame_part`
//!   chunking from dialler to node always and from node to dialler once the node reported the
//!   peer's `frame_part` capability. A byte-stream ALPN (`meridian/automerge-repo/1`) is the
//!   dialler's first bidirectional stream, handed over raw.
//! - Close codes and reasons pass through unchanged as QUIC application close codes and reasons.
//!
//! Wire compatibility: iroh 1.x declares its wire stable across the 1.x line, so this crate's
//! pinned 1.5.0 speaks to the Kotlin side's `computer.iroh` 1.2.0 (iroh 1.2).

mod hook;
mod link;

use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;

use async_trait::async_trait;
use core_carrier::{
    Carrier, CarrierError, DialedLink, NodeAddr, Protocols, StreamLink, VerifiedPeer,
};
use core_proto::NodeId;
use iroh::endpoint::{ConnectError, ConnectingError, presets};
use iroh::protocol::Router;
use iroh::{Endpoint, EndpointAddr, PublicKey, RelayMode, RelayUrl, SecretKey};
use thiserror::Error;

pub use link::closure_of;

/// How to bind a node's endpoint.
#[derive(Clone, Debug)]
pub struct IrohConfig {
    /// The node's Ed25519 secret key; its public key is the NodeId.
    pub secret_key: [u8; 32],
    /// The UDP address to bind (port 0 picks a free port).
    pub bind: SocketAddr,
    /// Relay URLs to use. Empty disables relays: only direct addresses are dialled.
    pub relays: Vec<String>,
}

impl IrohConfig {
    /// A loopback-only endpoint with no relays and no address lookup: deterministic, for tests and
    /// same-host links.
    #[must_use]
    pub fn loopback(secret_key: [u8; 32]) -> Self {
        Self {
            secret_key,
            bind: SocketAddr::from((Ipv4Addr::LOCALHOST, 0)),
            relays: Vec::new(),
        }
    }

    /// A fresh random secret key.
    #[must_use]
    pub fn random_secret_key() -> [u8; 32] {
        SecretKey::generate().to_bytes()
    }
}

/// Why an endpoint could not start.
#[derive(Debug, Error)]
pub enum IrohCarrierError {
    /// The bind address is not usable.
    #[error("bind address: {0}")]
    BindAddress(String),
    /// A relay URL does not parse.
    #[error("relay url {url}: {reason}")]
    RelayUrl {
        /// The URL as given.
        url: String,
        /// Why it was refused.
        reason: String,
    },
    /// The endpoint could not bind.
    #[error("bind: {0}")]
    Bind(String),
}

/// One node's iroh endpoint and router.
#[derive(Debug)]
pub struct IrohNode {
    endpoint: Endpoint,
    router: Router,
}

impl IrohNode {
    /// Binds the endpoint and starts serving `protocols`: the router accepts exactly their ALPNs,
    /// and their access hook runs in the endpoint's connection hook.
    ///
    /// # Errors
    ///
    /// [`IrohCarrierError`] when the configuration is unusable or the endpoint cannot bind.
    pub async fn bind(config: IrohConfig, protocols: Protocols) -> Result<Self, IrohCarrierError> {
        let protocols = Arc::new(protocols);
        let mut builder = Endpoint::builder(presets::Minimal)
            .secret_key(SecretKey::from_bytes(&config.secret_key))
            .clear_ip_transports()
            .bind_addr(config.bind)
            .map_err(|error| IrohCarrierError::BindAddress(error.to_string()))?
            .hooks(hook::AccessHookAdapter::new(protocols.access()));
        if !config.relays.is_empty() {
            let urls = config
                .relays
                .iter()
                .map(|url| {
                    url.parse::<RelayUrl>()
                        .map_err(|error| IrohCarrierError::RelayUrl {
                            url: url.clone(),
                            reason: error.to_string(),
                        })
                })
                .collect::<Result<Vec<_>, _>>()?;
            builder = builder.relay_mode(RelayMode::custom(urls));
        }
        let endpoint = builder
            .bind()
            .await
            .map_err(|error| IrohCarrierError::Bind(error.to_string()))?;
        let mut router = Router::builder(endpoint.clone());
        for alpn in protocols.alpns() {
            router = router.accept(alpn, link::RouterAdapter::new(Arc::clone(&protocols)));
        }
        Ok(Self {
            endpoint,
            router: router.spawn(),
        })
    }

    /// This node's NodeId.
    #[must_use]
    pub fn id(&self) -> NodeId {
        node_id(self.endpoint.id())
    }

    /// Where this node can be dialled: its NodeId and the direct addresses (and relay) the endpoint
    /// knows now.
    #[must_use]
    pub fn addr(&self) -> NodeAddr {
        let addr = self.endpoint.addr();
        let mut direct: Vec<SocketAddr> = addr.ip_addrs().copied().collect();
        for bound in self.endpoint.bound_sockets() {
            if !bound.ip().is_unspecified() && !direct.contains(&bound) {
                direct.push(bound);
            }
        }
        NodeAddr {
            id: node_id(addr.id),
            direct,
            relay: addr.relay_urls().next().map(ToString::to_string),
        }
    }

    /// A carrier that dials from this node's endpoint, so its links prove this node's NodeId.
    #[must_use]
    pub fn carrier(&self) -> IrohCarrier {
        IrohCarrier {
            endpoint: self.endpoint.clone(),
        }
    }

    /// Tells the endpoint the network may have changed (a platform's connectivity callback), so it
    /// re-probes its paths at once instead of waiting to notice. Harmless when nothing changed.
    pub async fn network_change(&self) {
        self.endpoint.network_change().await;
    }

    /// Stops accepting, closes every connection and the endpoint.
    pub async fn shutdown(&self) {
        // A protocol handler that panicked is already gone; shutting down still closes the
        // endpoint.
        let _ = self.router.shutdown().await;
        self.endpoint.close().await;
    }
}

/// Dials nodes from one iroh endpoint.
#[derive(Clone, Debug)]
pub struct IrohCarrier {
    endpoint: Endpoint,
}

#[async_trait]
impl Carrier for IrohCarrier {
    fn local(&self) -> VerifiedPeer {
        VerifiedPeer::Node(node_id(self.endpoint.id()))
    }

    async fn dial(&self, target: &NodeAddr, alpn: &[u8]) -> Result<DialedLink, CarrierError> {
        let connection = self.connect(target, alpn).await?;
        link::dial_lanes(connection, target.id).await
    }

    async fn dial_stream(
        &self,
        target: &NodeAddr,
        alpn: &[u8],
    ) -> Result<StreamLink, CarrierError> {
        let connection = self.connect(target, alpn).await?;
        link::dial_stream(connection, target.id).await
    }
}

impl IrohCarrier {
    async fn connect(
        &self,
        target: &NodeAddr,
        alpn: &[u8],
    ) -> Result<iroh::endpoint::Connection, CarrierError> {
        let id = PublicKey::from_bytes(target.id.as_bytes()).map_err(|error| {
            CarrierError::Unreachable(format!("{} is not a node key: {error}", target.id))
        })?;
        let mut addr = EndpointAddr::new(id);
        for direct in &target.direct {
            addr = addr.with_ip_addr(*direct);
        }
        if let Some(relay) = &target.relay {
            let url = relay.parse::<RelayUrl>().map_err(|error| {
                CarrierError::Unreachable(format!("relay url {relay}: {error}"))
            })?;
            addr = addr.with_relay_url(url);
        }
        self.endpoint
            .connect(addr, alpn)
            .await
            .map_err(|error| connect_error(&error))
    }
}

/// The NodeId a secret key proves: its Ed25519 public key.
#[must_use]
pub fn node_id_of_secret(secret_key: &[u8; 32]) -> NodeId {
    node_id(SecretKey::from_bytes(secret_key).public())
}

/// The NodeId of an iroh endpoint id (the same 32 key bytes).
#[must_use]
pub fn node_id(key: PublicKey) -> NodeId {
    NodeId::from_bytes(*key.as_bytes())
}

fn connect_error(error: &ConnectError) -> CarrierError {
    let closed = match error {
        ConnectError::Connection { source, .. }
        | ConnectError::Connecting {
            source: ConnectingError::ConnectionError { source, .. },
            ..
        } => link::closure_of(source),
        _ => None,
    };
    match closed {
        Some(closure) if !matches!(closure, core_carrier::LinkClosure::Lost) => {
            CarrierError::Closed(closure)
        }
        _ => CarrierError::Unreachable(error.to_string()),
    }
}
