//! Frames and lanes.

use std::fmt;

/// One frame: the UTF-8 JSON text of one logical node-protocol frame.
///
/// A carrier delivers it byte for byte; it never parses, re-encodes or rewrites it. Chunking
/// (`frame_part`) happens below this type and is never visible above a carrier.
#[derive(Clone, PartialEq, Eq, Hash)]
pub struct Frame(String);

impl Frame {
    /// Wraps the JSON text of a frame. The carrier does not check that it is JSON.
    #[must_use]
    pub fn new(text: impl Into<String>) -> Self {
        Self(text.into())
    }

    /// The frame's text.
    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }

    /// The frame's text, owned.
    #[must_use]
    pub fn into_string(self) -> String {
        self.0
    }

    /// The frame's length in bytes.
    #[must_use]
    pub fn len(&self) -> usize {
        self.0.len()
    }

    /// Whether the frame is empty (a valid, if useless, frame on the wire).
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }
}

impl From<String> for Frame {
    fn from(text: String) -> Self {
        Self(text)
    }
}

impl From<&str> for Frame {
    fn from(text: &str) -> Self {
        Self(text.to_owned())
    }
}

impl fmt::Debug for Frame {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        const SHOWN: usize = 160;
        if self.0.len() <= SHOWN {
            write!(formatter, "Frame({:?})", self.0)
        } else {
            let mut end = SHOWN;
            while !self.0.is_char_boundary(end) {
                end -= 1;
            }
            write!(
                formatter,
                "Frame({:?}... {} bytes)",
                &self.0[..end],
                self.0.len()
            )
        }
    }
}

/// The lane a frame arrived on at the dialling end. Call lanes are separate objects
/// ([`CallLane`](crate::CallLane)), so only the two long-lived lanes are named here.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Lane {
    /// The first lane: `auth`, runtime commands and their replies, `error` frames.
    Control,
    /// The second lane, node to dialler only: turn frames in `event_seq` order and other pushes.
    Push,
}
