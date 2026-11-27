//! How a node reaches other nodes: iroh (every real node) or an in-process network (tests, and
//! several nodes inside one process).

use std::sync::{Arc, OnceLock};

use async_trait::async_trait;
use core_carrier::{Carrier, CarrierError, DialedLink, NodeAddr, StreamLink, VerifiedPeer};
use core_carrier_iroh::IrohCarrier;
use core_proto::NodeId;

/// A set of nodes in one process that reach each other without a network. Share one between the
/// nodes that should see each other.
#[derive(uniffi::Object)]
pub struct InProcessNetwork {
    pub(crate) inner: core_carrier::in_process::InProcessNetwork,
}

#[uniffi::export]
impl InProcessNetwork {
    /// An empty network.
    #[uniffi::constructor]
    #[must_use]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inner: core_carrier::in_process::InProcessNetwork::new(),
        })
    }
}

/// The transport a node binds at start.
#[derive(uniffi::Enum)]
pub enum Transport {
    /// iroh QUIC: one endpoint per node, bound to `bind_addr` (`ip:port`, port 0 picks one), using
    /// these relay URLs (none: direct addresses only).
    Iroh {
        /// The UDP address to bind.
        bind_addr: String,
        /// Relay URLs.
        relays: Vec<String>,
    },
    /// An in-process network shared with the other nodes of this process.
    InProcess {
        /// The network.
        network: Arc<InProcessNetwork>,
    },
}

/// The node's carrier when it is iroh: the node needs a carrier to start, and the iroh endpoint
/// needs the started node's protocols to bind, so dials wait for the endpoint the start binds.
pub(crate) struct LateCarrier {
    id: NodeId,
    carrier: OnceLock<IrohCarrier>,
}

impl LateCarrier {
    pub(crate) fn new(id: NodeId) -> Self {
        Self {
            id,
            carrier: OnceLock::new(),
        }
    }

    pub(crate) fn bind(&self, carrier: IrohCarrier) {
        let _ = self.carrier.set(carrier);
    }

    fn get(&self) -> Result<&IrohCarrier, CarrierError> {
        self.carrier
            .get()
            .ok_or_else(|| CarrierError::Unreachable("the endpoint is not bound yet".into()))
    }
}

#[async_trait]
impl Carrier for LateCarrier {
    fn local(&self) -> VerifiedPeer {
        VerifiedPeer::Node(self.id)
    }

    async fn dial(&self, target: &NodeAddr, alpn: &[u8]) -> Result<DialedLink, CarrierError> {
        self.get()?.dial(target, alpn).await
    }

    async fn dial_stream(
        &self,
        target: &NodeAddr,
        alpn: &[u8],
    ) -> Result<StreamLink, CarrierError> {
        self.get()?.dial_stream(target, alpn).await
    }
}
