//! Event streams: a listener the platform implements, and the [`TaskHandle`] that ends the stream.

use std::future::Future;
use std::sync::Arc;

use tokio::runtime::Handle;
use tokio::sync::broadcast;
use tokio::task::AbortHandle;

use crate::records::{MembershipView, NodeEvent};

/// A running stream (or other long task). `cancel()` ends it; so does dropping the handle (Kotlin
/// `close()`). Cancelling twice, or after the stream ended, does nothing.
#[derive(uniffi::Object)]
pub struct TaskHandle {
    abort: AbortHandle,
}

impl TaskHandle {
    /// Runs `task` on `runtime` and hands back its handle.
    pub(crate) fn spawn(
        runtime: &Handle,
        task: impl Future<Output = ()> + Send + 'static,
    ) -> Arc<Self> {
        Arc::new(Self {
            abort: runtime.spawn(task).abort_handle(),
        })
    }
}

#[uniffi::export]
impl TaskHandle {
    /// Ends the task. No listener call starts after this returns.
    pub fn cancel(&self) {
        self.abort.abort();
    }

    /// Whether the task ended (cancelled, or its source closed when the node stopped).
    #[must_use]
    pub fn is_finished(&self) -> bool {
        self.abort.is_finished()
    }
}

impl Drop for TaskHandle {
    fn drop(&mut self) {
        self.abort.abort();
    }
}

/// Receives membership views: the current one at once, then one per change.
#[uniffi::export(foreign)]
pub trait MembershipListener: Send + Sync {
    /// The view changed (or this is the first delivery).
    fn on_view(&self, view: MembershipView);
}

/// Something the catalog reports.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum CatalogEvent {
    /// A peer sent changes it may not make, or malformed ones; they were quarantined.
    Violation {
        /// The peer, hex.
        principal: String,
        /// The document, as an `automerge:` URL.
        document: String,
        /// `unauthorized_change`, `malformed_change` or `oversized_change`.
        reason: String,
    },
    /// A catalog link ended.
    LinkClosed {
        /// The peer, hex.
        principal: String,
        /// The close code, or none when the transport was lost.
        code: Option<u64>,
        /// The close reason (empty when lost).
        reason: String,
    },
    /// The listener fell behind and missed this many events.
    Lagged {
        /// How many were missed.
        missed: u64,
    },
}

impl From<core_catalog::CatalogEvent> for CatalogEvent {
    fn from(event: core_catalog::CatalogEvent) -> Self {
        match event {
            core_catalog::CatalogEvent::Violation {
                principal,
                doc_id,
                reason,
            } => Self::Violation {
                principal: principal.to_hex(),
                document: core_catalog::DocUrl::new(doc_id).to_string(),
                reason: reason.as_str().to_owned(),
            },
            core_catalog::CatalogEvent::LinkClosed { principal, closure } => {
                let app = closure.app();
                Self::LinkClosed {
                    principal: principal.to_hex(),
                    code: app.map(|close| close.code),
                    reason: app.map(|close| close.reason.clone()).unwrap_or_default(),
                }
            }
        }
    }
}

/// Receives catalog events.
#[uniffi::export(foreign)]
pub trait CatalogEventListener: Send + Sync {
    /// One event.
    fn on_event(&self, event: CatalogEvent);
}

/// Receives the node's own events.
#[uniffi::export(foreign)]
pub trait NodeEventListener: Send + Sync {
    /// One event.
    fn on_event(&self, event: NodeEvent);
}

/// Forwards a broadcast channel to `deliver` until the channel closes. A lagging receiver reports
/// how much it missed through `lagged` and keeps going.
pub(crate) async fn forward<T: Clone + Send>(
    mut receiver: broadcast::Receiver<T>,
    deliver: impl Fn(T) + Send,
    lagged: impl Fn(u64) + Send,
) {
    loop {
        match receiver.recv().await {
            Ok(event) => deliver(event),
            Err(broadcast::error::RecvError::Lagged(missed)) => lagged(missed),
            Err(broadcast::error::RecvError::Closed) => break,
        }
    }
}
