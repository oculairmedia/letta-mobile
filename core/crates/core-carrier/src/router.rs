//! The node's protocols: one handler per ALPN behind one access hook.

use std::fmt;
use std::sync::Arc;

use async_trait::async_trait;
use core_proto::closure::Closure;

use crate::{AcceptedLink, AccessDecision, AccessHook, AdmitAll, AppClose, VerifiedPeer};

/// Serves the links accepted for one ALPN.
#[async_trait]
pub trait ProtocolHandler: Send + Sync + 'static {
    /// Serves one link. Called once per link, only after the access hook admitted the peer. When it
    /// returns, the carrier closes the link with `0 closed` if it is still open.
    async fn serve(&self, link: AcceptedLink);
}

/// What a node serves: its access hook and a handler per ALPN. Carriers hand every accepted link to
/// [`serve`](Self::serve) after [`check`](Self::check) admitted the peer.
#[derive(Clone)]
pub struct Protocols {
    access: Arc<dyn AccessHook>,
    handlers: Vec<(Vec<u8>, Arc<dyn ProtocolHandler>)>,
}

impl Protocols {
    /// Starts a set of protocols that admits every peer until [`ProtocolsBuilder::access`] says
    /// otherwise.
    #[must_use]
    pub fn builder() -> ProtocolsBuilder {
        ProtocolsBuilder {
            access: Arc::new(AdmitAll),
            handlers: Vec::new(),
        }
    }

    /// No protocols: an endpoint that only dials.
    #[must_use]
    pub fn none() -> Self {
        Self::builder().build()
    }

    /// The registered ALPNs, in registration order (the preference order for negotiation).
    pub fn alpns(&self) -> impl Iterator<Item = &[u8]> {
        self.handlers.iter().map(|(alpn, _)| alpn.as_slice())
    }

    /// Whether `alpn` has a handler.
    #[must_use]
    pub fn serves(&self, alpn: &[u8]) -> bool {
        self.handler(alpn).is_some()
    }

    /// The access hook.
    #[must_use]
    pub fn access(&self) -> Arc<dyn AccessHook> {
        Arc::clone(&self.access)
    }

    /// Runs the access hook for `peer` on `alpn`.
    pub async fn check(&self, peer: &VerifiedPeer, alpn: &[u8]) -> AccessDecision {
        self.access.check(peer, alpn).await
    }

    /// Serves an admitted link with the handler for its ALPN, then closes it with `0 closed` if the
    /// handler left it open. A link on an ALPN with no handler is closed `4404 unknown_alpn`.
    pub async fn serve(&self, accepted: AcceptedLink) {
        let link = Arc::clone(&accepted.link);
        let Some(handler) = self.handler(link.alpn()) else {
            link.close(Closure::UNKNOWN_ALPN.into());
            return;
        };
        handler.serve(accepted).await;
        if link.closure().is_none() {
            link.close(AppClose::closed());
        }
    }

    fn handler(&self, alpn: &[u8]) -> Option<&Arc<dyn ProtocolHandler>> {
        self.handlers
            .iter()
            .find(|(registered, _)| registered == alpn)
            .map(|(_, handler)| handler)
    }
}

impl fmt::Debug for Protocols {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        let alpns: Vec<String> = self
            .alpns()
            .map(|alpn| String::from_utf8_lossy(alpn).into_owned())
            .collect();
        formatter
            .debug_struct("Protocols")
            .field("alpns", &alpns)
            .finish_non_exhaustive()
    }
}

/// Builds [`Protocols`].
pub struct ProtocolsBuilder {
    access: Arc<dyn AccessHook>,
    handlers: Vec<(Vec<u8>, Arc<dyn ProtocolHandler>)>,
}

impl ProtocolsBuilder {
    /// Sets the access hook every connection passes before its handler.
    #[must_use]
    pub fn access(mut self, hook: impl AccessHook) -> Self {
        self.access = Arc::new(hook);
        self
    }

    /// Serves `alpn` with `handler`.
    ///
    /// # Panics
    ///
    /// When `alpn` is already registered: ALPNs are unique, and registering one twice is a
    /// configuration error caught at startup.
    #[must_use]
    pub fn accept(mut self, alpn: &[u8], handler: impl ProtocolHandler) -> Self {
        assert!(
            self.handlers
                .iter()
                .all(|(registered, _)| registered != alpn),
            "ALPN {} registered twice",
            String::from_utf8_lossy(alpn)
        );
        self.handlers.push((alpn.to_vec(), Arc::new(handler)));
        self
    }

    /// The finished set.
    #[must_use]
    pub fn build(self) -> Protocols {
        Protocols {
            access: self.access,
            handlers: self.handlers,
        }
    }
}
