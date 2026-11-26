//! The node's access hook, run in iroh's connection hook.

use std::fmt;
use std::sync::Arc;

use core_carrier::{AccessDecision, AccessHook, VerifiedPeer};
use iroh::endpoint::{AfterHandshakeOutcome, Connection, EndpointHooks, Side, VarInt};

use crate::node_id;

/// Runs an [`AccessHook`] on every incoming connection once the TLS handshake proved the peer's
/// key, before the router hands the connection to any protocol handler. A rejection closes the
/// connection with the hook's code and reason. Outgoing connections (this node dialling) pass.
pub(crate) struct AccessHookAdapter {
    access: Arc<dyn AccessHook>,
}

impl AccessHookAdapter {
    pub(crate) fn new(access: Arc<dyn AccessHook>) -> Self {
        Self { access }
    }
}

impl fmt::Debug for AccessHookAdapter {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("AccessHookAdapter")
    }
}

impl EndpointHooks for AccessHookAdapter {
    async fn after_handshake<'a>(&'a self, connection: &'a Connection) -> AfterHandshakeOutcome {
        if connection.side() == Side::Client {
            return AfterHandshakeOutcome::Accept;
        }
        let peer = VerifiedPeer::Node(node_id(connection.remote_id()));
        match self.access.check(&peer, connection.alpn()).await {
            AccessDecision::Admit => AfterHandshakeOutcome::Accept,
            AccessDecision::Reject(close) => AfterHandshakeOutcome::Reject {
                error_code: VarInt::from_u64(close.code).unwrap_or(VarInt::MAX),
                reason: close.reason.into_bytes(),
            },
        }
    }
}
