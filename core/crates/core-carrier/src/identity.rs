//! Who is at the other end of a link, and where to dial a node.

use std::fmt;
use std::net::SocketAddr;

use core_proto::NodeId;

/// The identity of the other end of a link, as the transport proved it.
///
/// Only carriers produce these: the Iroh carrier from the TLS handshake (the remote endpoint's
/// Ed25519 key), the in-process carrier from the identity the dialling endpoint was created with.
/// Never build one from a value a frame carried.
#[derive(Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum VerifiedPeer {
    /// A node (or a client with its own key): its NodeId.
    Node(NodeId),
    /// The shell in the same process as the node, reached through the in-process carrier. It has no
    /// key of its own; the process boundary is its proof.
    LocalShell,
}

impl VerifiedPeer {
    /// The NodeId, unless this is the in-process shell.
    #[must_use]
    pub const fn node_id(&self) -> Option<&NodeId> {
        match self {
            Self::Node(id) => Some(id),
            Self::LocalShell => None,
        }
    }
}

impl fmt::Debug for VerifiedPeer {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Display::fmt(self, formatter)
    }
}

impl fmt::Display for VerifiedPeer {
    /// The NodeId's 64 hex characters, or `local-shell`.
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Node(id) => fmt::Display::fmt(id, formatter),
            Self::LocalShell => formatter.write_str("local-shell"),
        }
    }
}

/// Where to dial a node: its NodeId, plus the addressing hints a transport may use.
///
/// The in-process carrier uses only `id`. The Iroh carrier dials `id` at the direct addresses and
/// relay given; with neither, it can only reach a node its address lookup knows.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct NodeAddr {
    /// The node to reach. A carrier refuses a link whose proven identity is not this one.
    pub id: NodeId,
    /// Direct UDP addresses to try.
    pub direct: Vec<SocketAddr>,
    /// A relay URL to try.
    pub relay: Option<String>,
}

impl NodeAddr {
    /// An address with no hints: just the NodeId.
    #[must_use]
    pub const fn new(id: NodeId) -> Self {
        Self {
            id,
            direct: Vec::new(),
            relay: None,
        }
    }

    /// Adds a direct address hint.
    #[must_use]
    pub fn with_direct(mut self, addr: SocketAddr) -> Self {
        self.direct.push(addr);
        self
    }
}
