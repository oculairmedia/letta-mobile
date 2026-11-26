//! Admission: whether a peer may reach any protocol handler at all.

use std::collections::BTreeSet;

use async_trait::async_trait;
use core_proto::NodeId;
use core_proto::closure::Closure;

use crate::{AppClose, VerifiedPeer};

/// The verdict of an [`AccessHook`].
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum AccessDecision {
    /// Let the connection reach its protocol handler.
    Admit,
    /// Close the connection with this closure before any handler runs.
    Reject(AppClose),
}

/// Decides, from the peer's verified identity and the ALPN it asked for, whether a connection may
/// reach a protocol handler. Carriers run it before any handler: the Iroh carrier inside the
/// endpoint's connection hook, the in-process carrier in `dial`.
///
/// It admits or rejects; it never authenticates. A session's own `auth` still runs after it.
#[async_trait]
pub trait AccessHook: Send + Sync + 'static {
    /// Admits or rejects `peer` for `alpn`.
    async fn check(&self, peer: &VerifiedPeer, alpn: &[u8]) -> AccessDecision;
}

/// Admits every peer. For nodes whose protocols authorize peers themselves, and for tests.
#[derive(Clone, Copy, Debug, Default)]
pub struct AdmitAll;

#[async_trait]
impl AccessHook for AdmitAll {
    async fn check(&self, _peer: &VerifiedPeer, _alpn: &[u8]) -> AccessDecision {
        AccessDecision::Admit
    }
}

/// Answers whether a NodeId is a member of this node's constellation. The membership log owns the
/// answer; the carrier only asks.
#[async_trait]
pub trait MembershipQuery: Send + Sync + 'static {
    /// Whether `node` is a current member.
    async fn is_member(&self, node: &NodeId) -> bool;
}

/// A fixed member set, for tests and simple deployments.
#[async_trait]
impl MembershipQuery for BTreeSet<NodeId> {
    async fn is_member(&self, node: &NodeId) -> bool {
        self.contains(node)
    }
}

/// The membership access hook: members are admitted, other NodeIds are rejected with
/// `4403 peer_not_allowed` before any handler runs.
///
/// The in-process shell ([`VerifiedPeer::LocalShell`]) is admitted: it lives in the node's own
/// process. ALPNs named with [`open_to_all`](Self::open_to_all) admit non-members too, so that, for
/// example, a device redeeming an invite can reach the session's `auth` while pairing is open.
#[derive(Debug)]
pub struct MembershipAccess<M> {
    membership: M,
    open: Vec<Vec<u8>>,
}

impl<M: MembershipQuery> MembershipAccess<M> {
    /// A hook that admits exactly the members `membership` reports.
    pub const fn new(membership: M) -> Self {
        Self {
            membership,
            open: Vec::new(),
        }
    }

    /// Also admits non-members on `alpn`.
    #[must_use]
    pub fn open_to_all(mut self, alpn: &[u8]) -> Self {
        self.open.push(alpn.to_vec());
        self
    }
}

#[async_trait]
impl<M: MembershipQuery> AccessHook for MembershipAccess<M> {
    async fn check(&self, peer: &VerifiedPeer, alpn: &[u8]) -> AccessDecision {
        let admitted = match peer {
            VerifiedPeer::LocalShell => true,
            VerifiedPeer::Node(id) => {
                self.open.iter().any(|open| open == alpn) || self.membership.is_member(id).await
            }
        };
        if admitted {
            AccessDecision::Admit
        } else {
            AccessDecision::Reject(Closure::PEER_NOT_ALLOWED.into())
        }
    }
}

#[cfg(test)]
mod tests {
    use core_proto::alpn::Alpn;

    use super::*;

    fn node(byte: u8) -> NodeId {
        NodeId::from_bytes([byte; 32])
    }

    #[tokio::test]
    async fn members_are_admitted_and_others_refused_peer_not_allowed() {
        let hook = MembershipAccess::new(BTreeSet::from([node(1)]));
        let session = Alpn::AppServer.as_bytes();
        assert_eq!(
            hook.check(&VerifiedPeer::Node(node(1)), session).await,
            AccessDecision::Admit
        );
        assert_eq!(
            hook.check(&VerifiedPeer::Node(node(2)), session).await,
            AccessDecision::Reject(Closure::PEER_NOT_ALLOWED.into())
        );
        assert_eq!(
            hook.check(&VerifiedPeer::LocalShell, session).await,
            AccessDecision::Admit
        );
    }

    #[tokio::test]
    async fn an_open_alpn_admits_non_members_only_on_that_alpn() {
        let hook = MembershipAccess::new(BTreeSet::new()).open_to_all(Alpn::AppServer.as_bytes());
        let stranger = VerifiedPeer::Node(node(9));
        assert_eq!(
            hook.check(&stranger, Alpn::AppServer.as_bytes()).await,
            AccessDecision::Admit
        );
        assert!(matches!(
            hook.check(&stranger, Alpn::AgentToAgent.as_bytes()).await,
            AccessDecision::Reject(_)
        ));
    }
}
