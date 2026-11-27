//! Carrier failures.

use thiserror::Error;

use crate::LinkClosure;

/// Why a carrier operation failed.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
pub enum CarrierError {
    /// The carrier does not dial this address, or no node answered there.
    #[error("unreachable: {0}")]
    Unreachable(String),
    /// The link is closed (by either end, or lost); the value is how it ended.
    #[error("link closed: {0}")]
    Closed(LinkClosure),
    /// The frame cannot be sent to this peer (for example larger than one frame to a peer that did
    /// not advertise `frame_part`). The link stays open.
    #[error("frame refused: {0}")]
    Frame(String),
    /// This half of a call lane was already finished.
    #[error("lane already finished")]
    Finished,
    /// The ALPN is not one the node serves; the handshake failed before any link existed.
    #[error("no application protocol: {0}")]
    NoProtocol(String),
}
