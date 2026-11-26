//! Session authentication: the error vocabulary of a refused `auth` frame, the limits, and the
//! per-NodeId failure limiter every session of one endpoint shares.

use std::collections::VecDeque;
use std::fmt;
use std::hash::Hash;

use lru::LruCache;
use serde::{Deserialize, Serialize};

/// Failed credential attempts one NodeId may make inside [`LOCKOUT_WINDOW_MS`] before it is locked out.
pub const MAX_FAILURES_PER_WINDOW: usize = 5;
/// The sliding window, in milliseconds, failures are counted over.
pub const LOCKOUT_WINDOW_MS: u64 = 60_000;
/// Most NodeIds holding failure state at once.
pub const MAX_TRACKED_PEERS: usize = 4_096;
/// Refused `auth` frames on one session after which the node closes it with `4401 auth_failures`.
pub const MAX_FAILURES_BEFORE_CLOSE: u32 = 3;
/// Shortest bootstrap bearer token a node accepts in its configuration, in characters.
pub const MIN_TOKEN_LENGTH: usize = 16;
/// The token prefix that makes an `auth` frame an invite redemption.
pub const INVITE_TOKEN_PREFIX: &str = "invite:";
/// Longest a node waits for an incoming connection's TLS handshake, in milliseconds.
pub const HANDSHAKE_TIMEOUT_MS: u64 = 15_000;

/// Why an `auth` frame was refused: the `error` of a failed `auth_response`.
///
/// Every value is a fixed label, never derived from the credential. Unknown labels from a newer node
/// are kept as [`AuthErrorCode::Other`].
#[derive(Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(from = "String", into = "String")]
pub enum AuthErrorCode {
    /// The frame had no `request_id`. The reply then carries no `request_id` either.
    RequestIdRequired,
    /// A bootstrap token is configured and the frame carried none (or a blank one).
    MissingToken,
    /// A bootstrap token is configured and the frame's token is not it.
    InvalidToken,
    /// No bootstrap credential is configured, the NodeId is not paired, and no invite was redeemed.
    PairingRequired,
    /// A static allowlist is configured and the NodeId is not on it, so the token is not honoured.
    PeerNotAllowlisted,
    /// The invite secret is unknown or already used.
    InviteInvalid,
    /// The invite expired before it was redeemed.
    InviteExpired,
    /// The NodeId is locked out. The node closes the connection with `4429 auth_rate_limited`.
    RateLimited,
    /// A refusal without a more specific label.
    Denied,
    /// A label this version does not know.
    Other(String),
}

impl AuthErrorCode {
    /// The label on the wire.
    #[must_use]
    pub fn as_str(&self) -> &str {
        match self {
            Self::RequestIdRequired => "request_id is required",
            Self::MissingToken => "missing_token",
            Self::InvalidToken => "invalid_token",
            Self::PairingRequired => "pairing_required",
            Self::PeerNotAllowlisted => "peer_not_allowlisted",
            Self::InviteInvalid => "invite_invalid",
            Self::InviteExpired => "invite_expired",
            Self::RateLimited => "rate_limited",
            Self::Denied => "denied",
            Self::Other(label) => label,
        }
    }

    /// Whether this refusal counts against the session's [`MAX_FAILURES_BEFORE_CLOSE`]. A missing
    /// `request_id` is a malformed frame, not a credential attempt, and a rate-limited refusal
    /// closes the connection on its own.
    #[must_use]
    pub const fn counts_against_session(&self) -> bool {
        !matches!(self, Self::RequestIdRequired | Self::RateLimited)
    }
}

impl From<String> for AuthErrorCode {
    fn from(label: String) -> Self {
        match label.as_str() {
            "request_id is required" => Self::RequestIdRequired,
            "missing_token" => Self::MissingToken,
            "invalid_token" => Self::InvalidToken,
            "pairing_required" => Self::PairingRequired,
            "peer_not_allowlisted" => Self::PeerNotAllowlisted,
            "invite_invalid" => Self::InviteInvalid,
            "invite_expired" => Self::InviteExpired,
            "rate_limited" => Self::RateLimited,
            "denied" => Self::Denied,
            _ => Self::Other(label),
        }
    }
}

impl From<AuthErrorCode> for String {
    fn from(code: AuthErrorCode) -> Self {
        match code {
            AuthErrorCode::Other(label) => label,
            known => known.as_str().to_owned(),
        }
    }
}

impl fmt::Display for AuthErrorCode {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.as_str())
    }
}

impl fmt::Debug for AuthErrorCode {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "AuthErrorCode({:?})", self.as_str())
    }
}

/// How an accepted session authenticated.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AuthMethod {
    /// The configured bootstrap allowance: the bearer token, a static allowlist, or anonymous test mode.
    Bootstrap,
    /// The NodeId is in the paired-peer store; the TLS handshake proved the key.
    PairedNodeId,
    /// The frame redeemed a one-time invite and the NodeId is now paired.
    InviteRedeemed,
}

/// What [`AuthFailureLimiter::deny`] decided for one failed attempt.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum FailureOutcome {
    /// Refuse with this label; the NodeId is not locked out.
    Denied(AuthErrorCode),
    /// Refuse with `rate_limited` and close the connection with `4429 auth_rate_limited`.
    RateLimited,
}

/// Per-NodeId auth-failure accounting, shared by every session of one endpoint so a redial cannot
/// reset a peer's budget.
///
/// A NodeId with [`MAX_FAILURES_PER_WINDOW`] failures inside the last [`LOCKOUT_WINDOW_MS`] is
/// locked out, even when it then presents a valid credential. At most `max_tracked` NodeIds hold
/// state: when the table is full, NodeIds whose newest failure left the window are dropped first,
/// and a NodeId still inside its window is never dropped (that would lift its lockout). A failure
/// from a new NodeId that cannot be tracked is answered [`FailureOutcome::RateLimited`].
///
/// Time is passed in by the caller in milliseconds, so the limiter is deterministic and wasm-safe.
///
/// The table is an [`lru::LruCache`] ordered by each NodeId's newest failure, so the eviction
/// candidate is always its least recently used entry.
#[derive(Debug)]
pub struct AuthFailureLimiter<K: Eq + Hash = crate::ids::NodeId> {
    max_failures: usize,
    window_ms: u64,
    max_tracked: usize,
    /// Each tracked NodeId's failure times, oldest first; the least recently used entry is the one
    /// whose newest failure is oldest.
    entries: LruCache<K, VecDeque<u64>>,
}

impl<K: Clone + Eq + Hash> AuthFailureLimiter<K> {
    /// A limiter with the node protocol's limits.
    #[must_use]
    pub fn new() -> Self {
        Self::with_limits(
            MAX_FAILURES_PER_WINDOW,
            LOCKOUT_WINDOW_MS,
            MAX_TRACKED_PEERS,
        )
    }

    /// A limiter with custom limits (tests use small ones).
    #[must_use]
    pub fn with_limits(max_failures: usize, window_ms: u64, max_tracked: usize) -> Self {
        Self {
            max_failures,
            window_ms,
            max_tracked,
            // Unbounded: the limiter enforces `max_tracked` itself, because the cache's own
            // eviction would drop a NodeId that is still inside its window.
            entries: LruCache::unbounded(),
        }
    }

    /// How many NodeIds hold failure state.
    #[must_use]
    pub fn tracked(&self) -> usize {
        self.entries.len()
    }

    /// Whether `peer` is locked out at `now_ms`. Forgets failures that left the window.
    pub fn is_rate_limited(&mut self, peer: &K, now_ms: u64) -> bool {
        let cutoff = now_ms.saturating_sub(self.window_ms);
        // `peek_mut` leaves the recency order alone: only a new failure moves a NodeId.
        let Some(failures) = self.entries.peek_mut(peer) else {
            return false;
        };
        while failures.front().is_some_and(|&at| at < cutoff) {
            failures.pop_front();
        }
        if failures.is_empty() {
            self.entries.pop(peer);
            return false;
        }
        failures.len() >= self.max_failures
    }

    /// Counts one failed attempt by `peer` with refusal `reason`, and decides the reply.
    pub fn deny(&mut self, peer: &K, reason: AuthErrorCode, now_ms: u64) -> FailureOutcome {
        if !self.record_failure(peer, now_ms) || self.is_rate_limited(peer, now_ms) {
            FailureOutcome::RateLimited
        } else {
            FailureOutcome::Denied(reason)
        }
    }

    /// False when `peer` is new and the table is full of NodeIds still inside their window.
    fn record_failure(&mut self, peer: &K, now_ms: u64) -> bool {
        if !self.entries.contains(peer) && !self.has_room(now_ms) {
            return false;
        }
        let failures = self.entries.get_or_insert_mut(peer.clone(), VecDeque::new);
        failures.push_back(now_ms);
        while failures.len() > self.max_failures {
            failures.pop_front();
        }
        true
    }

    /// Drops least recently failed NodeIds whose newest failure left the window while the table is
    /// full; true when a new NodeId then fits.
    fn has_room(&mut self, now_ms: u64) -> bool {
        let cutoff = now_ms.saturating_sub(self.window_ms);
        while self.entries.len() >= self.max_tracked {
            let stale = self
                .entries
                .peek_lru()
                .is_some_and(|(_, failures)| failures.back().is_none_or(|&at| at < cutoff));
            if !stale {
                break;
            }
            self.entries.pop_lru();
        }
        self.entries.len() < self.max_tracked
    }
}

impl<K: Clone + Eq + Hash> Default for AuthFailureLimiter<K> {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn labels_round_trip_including_unknown_ones() {
        for label in [
            "missing_token",
            "request_id is required",
            "rate_limited",
            "something_new",
        ] {
            let code = AuthErrorCode::from(label.to_owned());
            assert_eq!(String::from(code), label);
        }
        assert_eq!(
            AuthErrorCode::from("invite_expired".to_owned()),
            AuthErrorCode::InviteExpired
        );
    }

    #[test]
    fn the_fifth_failure_inside_the_window_locks_the_peer_out() {
        let mut limiter = AuthFailureLimiter::<&str>::new();
        for at in 0..4 {
            assert_eq!(
                limiter.deny(&"a", AuthErrorCode::InvalidToken, at),
                FailureOutcome::Denied(AuthErrorCode::InvalidToken)
            );
        }
        assert_eq!(
            limiter.deny(&"a", AuthErrorCode::InvalidToken, 4),
            FailureOutcome::RateLimited
        );
        assert!(limiter.is_rate_limited(&"a", 60_000));
        assert!(!limiter.is_rate_limited(&"a", 60_001));
        assert!(!limiter.is_rate_limited(&"b", 0));
    }
}
