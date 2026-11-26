//! Byte-stream protocols: an ALPN whose wire is one raw bidirectional byte stream rather than
//! node-protocol frames.
//!
//! Some protocols a node speaks predate node protocol v1 and keep their own framing, such as
//! `meridian/automerge-repo/1` (a u32 big-endian length before each samod message). For those the
//! carrier hands the handler one byte stream per link: the dialler's first bidirectional stream on
//! Iroh, an in-memory duplex in process. The carrier still never parses what crosses it, and the
//! access hook still runs before any handler.

use std::sync::Arc;

use async_trait::async_trait;
use tokio::io::{AsyncRead, AsyncWrite};

use crate::LinkControl;

/// One raw byte stream: both directions of a link's single stream.
pub trait ByteStream: AsyncRead + AsyncWrite + Send + Unpin + 'static {}

impl<T: AsyncRead + AsyncWrite + Send + Unpin + 'static> ByteStream for T {}

/// A link on a byte-stream protocol, as either end holds it.
pub struct StreamLink {
    /// The other end's identity, the ALPN and closure.
    pub control: Arc<dyn LinkControl>,
    /// The link's one stream.
    pub stream: Box<dyn ByteStream>,
}

impl std::fmt::Debug for StreamLink {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("StreamLink")
            .field("remote", &self.control.remote())
            .finish_non_exhaustive()
    }
}

/// Serves the links accepted for one byte-stream ALPN.
#[async_trait]
pub trait StreamHandler: Send + Sync + 'static {
    /// Serves one link. Called once per link, only after the access hook admitted the peer. When it
    /// returns, the carrier closes the link with `0 closed` if it is still open.
    async fn serve(&self, link: StreamLink);
}
