//! Connection closures: the application close code and reason a node ends a connection with.

use serde::Serialize;

/// An application close: a numeric code and a short ASCII reason.
///
/// A peer treats an unknown code as a closure like any other. A carrier delivers both fields unchanged.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize)]
pub struct Closure {
    /// The application close code.
    pub code: u64,
    /// The close reason, sent as its ASCII bytes.
    pub reason: &'static str,
}

impl Closure {
    /// An orderly close: a detach. Never cancels work the peer started on the node.
    pub const CLOSED: Self = Self::of(0, "closed");
    /// The session closed after too many refused `auth` frames on it.
    pub const AUTH_FAILURES: Self = Self::of(4401, "auth_failures");
    /// A canvas connection from a peer without an authenticated App Server session.
    pub const NOT_AUTHENTICATED: Self = Self::of(4401, "not_authenticated");
    /// The peer's pairing was revoked while connected.
    pub const PEER_REVOKED: Self = Self::of(4403, "peer_revoked");
    /// The endpoint's admission check refused the peer.
    pub const PEER_NOT_ALLOWED: Self = Self::of(4403, "peer_not_allowed");
    /// A protocol handler refused the peer.
    pub const PROTOCOL_NOT_AUTHORIZED: Self = Self::of(4403, "protocol_not_authorized");
    /// A connection on an ALPN the node registered but does not route.
    pub const UNKNOWN_ALPN: Self = Self::of(4404, "unknown_alpn");
    /// The peer's NodeId is locked out after too many auth failures.
    pub const AUTH_RATE_LIMITED: Self = Self::of(4429, "auth_rate_limited");

    /// Every closure the node protocol v1 core defines.
    pub const ALL: [Self; 8] = [
        Self::CLOSED,
        Self::AUTH_FAILURES,
        Self::NOT_AUTHENTICATED,
        Self::PEER_REVOKED,
        Self::PEER_NOT_ALLOWED,
        Self::PROTOCOL_NOT_AUTHORIZED,
        Self::UNKNOWN_ALPN,
        Self::AUTH_RATE_LIMITED,
    ];

    const fn of(code: u64, reason: &'static str) -> Self {
        Self { code, reason }
    }
}
