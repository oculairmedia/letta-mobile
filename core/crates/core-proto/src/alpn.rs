//! The application protocols (TLS ALPNs) a node's endpoint accepts, and how each is routed.

use serde::{Deserialize, Serialize};

use crate::closure::Closure;

/// A protocol a node accepts on its one endpoint.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Alpn {
    /// `/letta/appserver/0`: the node session (control, push and call lanes). Always accepted.
    AppServer,
    /// `meridian/canvas-relay/1`: shared canvases, for peers signed in on an App Server session.
    CanvasRelay,
    /// `/letta/a2a/0`: one agent-to-agent message per connection; its handler authorizes the peer.
    AgentToAgent,
    /// `meridian/automerge-repo/1`: Automerge repository sync; its handler authorizes the peer.
    AutomergeRepo,
}

/// How the node decides whether to serve a connection on an [`Alpn`].
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Admission {
    /// The endpoint's admission check, then the session's own `auth` frame.
    Session,
    /// Served only while the same peer has an authenticated [`Alpn::AppServer`] session on this
    /// endpoint; otherwise closed with [`Closure::NOT_AUTHENTICATED`].
    RequiresAuthenticatedSession,
    /// The protocol's handler authorizes the peer before the endpoint's admission check runs;
    /// a refusal closes with [`Closure::PROTOCOL_NOT_AUTHORIZED`].
    OwnAuthorization,
}

impl Alpn {
    /// Every protocol, in the order a node registers them.
    pub const ALL: [Self; 4] = [
        Self::AppServer,
        Self::CanvasRelay,
        Self::AgentToAgent,
        Self::AutomergeRepo,
    ];

    /// The ALPN bytes on the wire.
    #[must_use]
    pub const fn as_bytes(self) -> &'static [u8] {
        match self {
            Self::AppServer => b"/letta/appserver/0",
            Self::CanvasRelay => b"meridian/canvas-relay/1",
            Self::AgentToAgent => b"/letta/a2a/0",
            Self::AutomergeRepo => b"meridian/automerge-repo/1",
        }
    }

    /// The protocol named by `bytes`, if a node knows it.
    #[must_use]
    pub fn from_bytes(bytes: &[u8]) -> Option<Self> {
        Self::ALL.into_iter().find(|alpn| alpn.as_bytes() == bytes)
    }

    /// How a connection on this protocol is admitted.
    #[must_use]
    pub const fn admission(self) -> Admission {
        match self {
            Self::AppServer => Admission::Session,
            Self::CanvasRelay => Admission::RequiresAuthenticatedSession,
            Self::AgentToAgent | Self::AutomergeRepo => Admission::OwnAuthorization,
        }
    }

    /// The closure for a connection on this protocol that the node refuses to serve.
    #[must_use]
    pub const fn refusal(self) -> Closure {
        match self.admission() {
            Admission::Session => Closure::PEER_NOT_ALLOWED,
            Admission::RequiresAuthenticatedSession => Closure::NOT_AUTHENTICATED,
            Admission::OwnAuthorization => Closure::PROTOCOL_NOT_AUTHORIZED,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_protocol_round_trips_its_bytes() {
        for alpn in Alpn::ALL {
            assert_eq!(Alpn::from_bytes(alpn.as_bytes()), Some(alpn));
        }
        assert_eq!(Alpn::from_bytes(b"/letta/appserver/1"), None);
    }
}
