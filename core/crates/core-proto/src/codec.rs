//! The stream framing every node protocol lane uses: length-prefixed UTF-8 JSON frames, and
//! `frame_part` chunking for logical frames larger than one frame.
//!
//! Plain frame: a u32 big-endian length below [`PART_SENTINEL`], then exactly that many payload
//! bytes. Part: the 4-byte [`PART_SENTINEL`], one flags byte (only [`PART_FLAG_FIN`] is defined), a
//! u32 big-endian part index (0-based, strictly sequential), a u32 big-endian part length
//! (`1..=max_frame_bytes`), then the part's bytes. A peer that never advertised `frame_part` reads the
//! sentinel as an oversized plain length and rejects it, which is why parts go only to peers that did.
//!
//! The decoder rejects a bad frame or part from its header alone, before buffering its payload, so a
//! peer can never make it hold more than [`Limits`] allow.

use thiserror::Error;

/// Largest plain frame, and largest single part, by default (1 MiB).
pub const MAX_FRAME_BYTES: usize = 1_048_576;
/// Largest logical frame a `frame_part` sequence may reassemble to by default (32 MiB).
pub const MAX_REASSEMBLED_BYTES: usize = 33_554_432;
/// Length prefix that marks a `frame_part` instead of a plain frame.
pub const PART_SENTINEL: u32 = 0x7FFF_FFFF;
/// Bytes in a plain frame's length prefix (and in a part's sentinel).
pub const LENGTH_PREFIX_BYTES: usize = 4;
/// Bytes after a part's sentinel and before its payload: flags, index and length.
pub const PART_HEADER_BYTES: usize = 9;
/// The flag set on the final part of a logical frame. Every other flag bit must be zero.
pub const PART_FLAG_FIN: u8 = 0x01;

/// The bounds a codec enforces on one stream.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Limits {
    max_frame_bytes: usize,
    max_reassembled_bytes: usize,
}

impl Limits {
    /// The node protocol's bounds for an authenticated peer that advertised `frame_part`.
    pub const DEFAULT: Self = Self {
        max_frame_bytes: MAX_FRAME_BYTES,
        max_reassembled_bytes: MAX_REASSEMBLED_BYTES,
    };

    /// The bounds before a peer authenticated and advertised `frame_part`: reassembly may hold no
    /// more than one plain frame's worth of memory.
    pub const PRE_AUTH: Self = Self {
        max_frame_bytes: MAX_FRAME_BYTES,
        max_reassembled_bytes: MAX_FRAME_BYTES,
    };

    /// Custom bounds (tests and fixtures use small ones).
    ///
    /// # Errors
    ///
    /// [`LimitsError`] when `max_frame_bytes` is zero or would collide with [`PART_SENTINEL`], or
    /// when `max_reassembled_bytes` is zero.
    pub const fn new(
        max_frame_bytes: usize,
        max_reassembled_bytes: usize,
    ) -> Result<Self, LimitsError> {
        if max_frame_bytes == 0 || max_frame_bytes >= PART_SENTINEL as usize {
            return Err(LimitsError::MaxFrameBytes(max_frame_bytes));
        }
        if max_reassembled_bytes == 0 {
            return Err(LimitsError::MaxReassembledBytes);
        }
        Ok(Self {
            max_frame_bytes,
            max_reassembled_bytes,
        })
    }

    /// Largest plain frame payload, and largest part payload.
    #[must_use]
    pub const fn max_frame_bytes(&self) -> usize {
        self.max_frame_bytes
    }

    /// Largest logical frame a part sequence may reassemble to.
    #[must_use]
    pub const fn max_reassembled_bytes(&self) -> usize {
        self.max_reassembled_bytes
    }
}

impl Default for Limits {
    fn default() -> Self {
        Self::DEFAULT
    }
}

/// Invalid [`Limits`].
#[derive(Clone, Copy, Debug, Error, PartialEq, Eq)]
pub enum LimitsError {
    /// `max_frame_bytes` must be in `1..0x7FFF_FFFF`.
    #[error("max_frame_bytes {0} must be in 1..0x7FFFFFFF")]
    MaxFrameBytes(usize),
    /// `max_reassembled_bytes` must be at least 1.
    #[error("max_reassembled_bytes must be at least 1")]
    MaxReassembledBytes,
}

/// Why a frame could not be encoded or decoded. Every decode error is a protocol error that ends the
/// stream: the decoder refuses further input after one.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
pub enum FrameError {
    /// A plain frame longer than `max_frame_bytes` (encoding, or a decoded length prefix).
    #[error("frame of {size} bytes exceeds the {max}-byte frame limit")]
    FrameTooLarge {
        /// The frame's payload length.
        size: u64,
        /// The limit it broke.
        max: usize,
    },
    /// A logical frame too large even for a `frame_part` sequence (encoding).
    #[error("logical frame of {size} bytes exceeds the {max}-byte reassembly limit")]
    LogicalFrameTooLarge {
        /// The logical frame's length.
        size: usize,
        /// The limit it broke.
        max: usize,
    },
    /// A part sequence that would reassemble past `max_reassembled_bytes` (decoding).
    #[error("reassembled frame of {size} bytes exceeds the {max}-byte reassembly limit")]
    ReassemblyTooLarge {
        /// The reassembled length the next part would reach.
        size: u64,
        /// The limit it broke.
        max: usize,
    },
    /// A part whose length is zero or above `max_frame_bytes`.
    #[error("frame_part length {length} is outside 1..={max}")]
    InvalidPartLength {
        /// The part's declared length.
        length: u32,
        /// The largest allowed.
        max: usize,
    },
    /// A part with a flag bit other than [`PART_FLAG_FIN`] set.
    #[error("frame_part has unknown flags 0x{flags:02x}")]
    UnknownPartFlags {
        /// The flags byte as received.
        flags: u8,
    },
    /// A part whose index is not the next one expected (a gap, a repeat, or a sequence not starting at 0).
    #[error("frame_part {actual} arrived out of sequence (expected part {expected})")]
    PartOutOfSequence {
        /// The index expected next.
        expected: u64,
        /// The index received.
        actual: u32,
    },
    /// A plain frame in the middle of a part sequence.
    #[error("plain frame interleaved mid frame_part sequence (expected part {expected})")]
    PlainFrameMidSequence {
        /// The part index expected next.
        expected: u64,
    },
    /// The stream ended inside a frame, a part, or a part sequence.
    #[error("stream ended mid-frame")]
    Truncated,
    /// A complete frame whose payload is not UTF-8.
    #[error("frame payload is not valid UTF-8")]
    InvalidUtf8,
    /// Input given to a decoder that already failed.
    #[error("decoder already failed; the stream must be closed")]
    Poisoned,
}

impl FrameError {
    /// The stable name of this error, as the codec fixtures spell it.
    #[must_use]
    pub const fn kind(&self) -> &'static str {
        match self {
            Self::FrameTooLarge { .. } => "frame_too_large",
            Self::LogicalFrameTooLarge { .. } => "logical_frame_too_large",
            Self::ReassemblyTooLarge { .. } => "reassembly_too_large",
            Self::InvalidPartLength { .. } => "invalid_part_length",
            Self::UnknownPartFlags { .. } => "unknown_part_flags",
            Self::PartOutOfSequence { .. } => "part_out_of_sequence",
            Self::PlainFrameMidSequence { .. } => "plain_frame_mid_sequence",
            Self::Truncated => "truncated",
            Self::InvalidUtf8 => "invalid_utf8",
            Self::Poisoned => "poisoned",
        }
    }
}

/// Encodes `payload` as one plain frame.
///
/// # Errors
///
/// [`FrameError::FrameTooLarge`] when `payload` is longer than `limits.max_frame_bytes()`. Use
/// [`encode_frame_parts`] for a peer that advertised `frame_part`.
pub fn encode_frame(payload: &str, limits: Limits) -> Result<Vec<u8>, FrameError> {
    let bytes = payload.as_bytes();
    if bytes.len() > limits.max_frame_bytes {
        return Err(FrameError::FrameTooLarge {
            size: bytes.len() as u64,
            max: limits.max_frame_bytes,
        });
    }
    let length = u32_of(bytes.len());
    let mut frame = Vec::with_capacity(LENGTH_PREFIX_BYTES + bytes.len());
    frame.extend_from_slice(&length.to_be_bytes());
    frame.extend_from_slice(bytes);
    Ok(frame)
}

/// Encodes `payload` as one plain frame when it fits in one frame, or else as the ordered
/// `frame_part`s of one logical frame: full parts of `max_frame_bytes`, the last one shorter or equal
/// and flagged [`PART_FLAG_FIN`]. Each returned buffer is written to the stream in order.
///
/// Only send parts to a peer whose accepted `auth` advertised `frame_part`.
///
/// # Errors
///
/// [`FrameError::LogicalFrameTooLarge`] when `payload` is longer than `limits.max_reassembled_bytes()`.
pub fn encode_frame_parts(payload: &str, limits: Limits) -> Result<Vec<Vec<u8>>, FrameError> {
    let bytes = payload.as_bytes();
    if bytes.len() <= limits.max_frame_bytes {
        return encode_frame(payload, limits).map(|frame| vec![frame]);
    }
    let too_large = FrameError::LogicalFrameTooLarge {
        size: bytes.len(),
        max: limits.max_reassembled_bytes,
    };
    if bytes.len() > limits.max_reassembled_bytes {
        return Err(too_large);
    }
    let chunks = bytes.chunks(limits.max_frame_bytes);
    let last = chunks.len() - 1;
    chunks
        .enumerate()
        .map(|(position, chunk)| {
            let index = u32::try_from(position).map_err(|_| too_large.clone())?;
            Ok(encode_part(index, position == last, chunk))
        })
        .collect()
}

fn encode_part(index: u32, fin: bool, chunk: &[u8]) -> Vec<u8> {
    let mut part = Vec::with_capacity(LENGTH_PREFIX_BYTES + PART_HEADER_BYTES + chunk.len());
    part.extend_from_slice(&PART_SENTINEL.to_be_bytes());
    part.push(if fin { PART_FLAG_FIN } else { 0 });
    part.extend_from_slice(&index.to_be_bytes());
    part.extend_from_slice(&u32_of(chunk.len()).to_be_bytes());
    part.extend_from_slice(chunk);
    part
}

/// A length already checked against a limit below [`PART_SENTINEL`].
fn u32_of(length: usize) -> u32 {
    u32::try_from(length).expect("length checked against a limit below 0x7FFFFFFF")
}

/// Decodes one ordered byte stream into frames, reassembling `frame_part` sequences.
///
/// Feed it chunks of any size with [`push`](Self::push), then call [`finish`](Self::finish) when the
/// stream ends. After any error the stream must be closed; the decoder answers further input with
/// [`FrameError::Poisoned`].
#[derive(Debug)]
pub struct FrameDecoder {
    limits: Limits,
    buffer: Vec<u8>,
    /// Start of the unread bytes in `buffer`.
    read: usize,
    reassembled: Vec<u8>,
    /// Index of the next part; zero when no sequence is in progress.
    next_part: u64,
    failed: bool,
}

impl FrameDecoder {
    /// A decoder enforcing `limits`.
    #[must_use]
    pub const fn new(limits: Limits) -> Self {
        Self {
            limits,
            buffer: Vec::new(),
            read: 0,
            reassembled: Vec::new(),
            next_part: 0,
            failed: false,
        }
    }

    /// The bounds in force.
    #[must_use]
    pub const fn limits(&self) -> Limits {
        self.limits
    }

    /// Changes the bounds from the next frame or part header on. A node widens the reassembly limit
    /// from [`Limits::PRE_AUTH`] to [`Limits::DEFAULT`] once the peer authenticated and advertised
    /// `frame_part`.
    pub const fn set_limits(&mut self, limits: Limits) {
        self.limits = limits;
    }

    /// Whether a part sequence is partly received.
    #[must_use]
    pub const fn reassembly_in_progress(&self) -> bool {
        self.next_part != 0
    }

    /// Appends `chunk` and returns every frame it completed, in order.
    ///
    /// # Errors
    ///
    /// Any decode [`FrameError`]; frames completed earlier in the same chunk are dropped with it, as
    /// the stream is unusable.
    pub fn push(&mut self, chunk: &[u8]) -> Result<Vec<String>, FrameError> {
        if self.failed {
            return Err(FrameError::Poisoned);
        }
        self.compact();
        self.buffer.extend_from_slice(chunk);
        let mut frames = Vec::new();
        loop {
            match self.step() {
                Ok(Step::NeedMore) => break,
                Ok(Step::Part) => {}
                Ok(Step::Frame(frame)) => frames.push(frame),
                Err(error) => {
                    self.failed = true;
                    return Err(error);
                }
            }
        }
        Ok(frames)
    }

    /// Ends the stream.
    ///
    /// # Errors
    ///
    /// [`FrameError::Truncated`] when the stream ended inside a frame, part or part sequence, and
    /// [`FrameError::Poisoned`] after an earlier error.
    pub fn finish(self) -> Result<(), FrameError> {
        if self.failed {
            Err(FrameError::Poisoned)
        } else if self.read < self.buffer.len() || self.reassembly_in_progress() {
            Err(FrameError::Truncated)
        } else {
            Ok(())
        }
    }

    fn compact(&mut self) {
        if self.read == self.buffer.len() {
            self.buffer.clear();
            self.read = 0;
        } else if self.read > self.buffer.len() / 2 {
            self.buffer.drain(..self.read);
            self.read = 0;
        }
    }

    fn unread(&self) -> &[u8] {
        &self.buffer[self.read..]
    }

    fn step(&mut self) -> Result<Step, FrameError> {
        let Some(prefix) = read_u32(self.unread(), 0) else {
            return Ok(Step::NeedMore);
        };
        if prefix == PART_SENTINEL {
            self.take_part()
        } else {
            self.take_plain(prefix)
        }
    }

    fn take_plain(&mut self, length: u32) -> Result<Step, FrameError> {
        if self.reassembly_in_progress() {
            return Err(FrameError::PlainFrameMidSequence {
                expected: self.next_part,
            });
        }
        let length = length as usize;
        if length > self.limits.max_frame_bytes {
            return Err(FrameError::FrameTooLarge {
                size: length as u64,
                max: self.limits.max_frame_bytes,
            });
        }
        let end = LENGTH_PREFIX_BYTES + length;
        if self.unread().len() < end {
            return Ok(Step::NeedMore);
        }
        let payload = self.unread()[LENGTH_PREFIX_BYTES..end].to_vec();
        self.read += end;
        String::from_utf8(payload)
            .map(Step::Frame)
            .map_err(|_| FrameError::InvalidUtf8)
    }

    fn take_part(&mut self) -> Result<Step, FrameError> {
        let start = LENGTH_PREFIX_BYTES + PART_HEADER_BYTES;
        let unread = self.unread();
        if unread.len() < start {
            return Ok(Step::NeedMore);
        }
        let flags = unread[LENGTH_PREFIX_BYTES];
        let index = read_u32(unread, LENGTH_PREFIX_BYTES + 1).unwrap_or_default();
        let length = read_u32(unread, LENGTH_PREFIX_BYTES + 5).unwrap_or_default();
        self.check_part_header(flags, index, length)?;
        let end = start + length as usize;
        if self.unread().len() < end {
            return Ok(Step::NeedMore);
        }
        let chunk = &self.buffer[self.read + start..self.read + end];
        self.reassembled.extend_from_slice(chunk);
        self.read += end;
        self.next_part += 1;
        if flags & PART_FLAG_FIN == 0 {
            return Ok(Step::Part);
        }
        self.next_part = 0;
        String::from_utf8(std::mem::take(&mut self.reassembled))
            .map(Step::Frame)
            .map_err(|_| FrameError::InvalidUtf8)
    }

    /// The order of these checks is the Kotlin node's, so both report the same first error.
    fn check_part_header(&self, flags: u8, index: u32, length: u32) -> Result<(), FrameError> {
        if flags & !PART_FLAG_FIN != 0 {
            return Err(FrameError::UnknownPartFlags { flags });
        }
        if u64::from(index) != self.next_part {
            return Err(FrameError::PartOutOfSequence {
                expected: self.next_part,
                actual: index,
            });
        }
        if length == 0 || length as usize > self.limits.max_frame_bytes {
            return Err(FrameError::InvalidPartLength {
                length,
                max: self.limits.max_frame_bytes,
            });
        }
        let reassembled = self.reassembled.len() as u64 + u64::from(length);
        if reassembled > self.limits.max_reassembled_bytes as u64 {
            return Err(FrameError::ReassemblyTooLarge {
                size: reassembled,
                max: self.limits.max_reassembled_bytes,
            });
        }
        Ok(())
    }
}

impl Default for FrameDecoder {
    fn default() -> Self {
        Self::new(Limits::DEFAULT)
    }
}

enum Step {
    /// The buffer ends inside a frame or part.
    NeedMore,
    /// A non-final part was taken.
    Part,
    /// A plain frame, or the final part of a sequence, completed a frame.
    Frame(String),
}

fn read_u32(bytes: &[u8], offset: usize) -> Option<u32> {
    let word = bytes.get(offset..offset + 4)?;
    Some(u32::from_be_bytes([word[0], word[1], word[2], word[3]]))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn limits_reject_a_frame_limit_that_collides_with_the_sentinel() {
        assert!(Limits::new(PART_SENTINEL as usize, 10).is_err());
        assert!(Limits::new(0, 10).is_err());
        assert!(Limits::new(8, 0).is_err());
        assert!(
            Limits::new(8, 4).is_ok(),
            "a reassembly limit below one frame is allowed"
        );
    }

    #[test]
    fn a_failed_decoder_refuses_more_input() {
        let mut decoder = FrameDecoder::new(Limits::new(4, 8).expect("valid limits"));
        assert!(decoder.push(&[0, 0, 0, 5]).is_err());
        assert_eq!(decoder.push(&[0]), Err(FrameError::Poisoned));
        assert_eq!(decoder.finish(), Err(FrameError::Poisoned));
    }

    #[test]
    fn widening_the_limits_mid_stream_applies_from_the_next_header() {
        let small = Limits::new(4, 4).expect("valid limits");
        let parts = encode_frame_parts("abcdefgh", Limits::new(4, 8).expect("valid limits"))
            .expect("two parts");
        let mut decoder = FrameDecoder::new(small);
        assert!(decoder.push(&parts[0]).expect("first part fits").is_empty());
        decoder.set_limits(Limits::new(4, 8).expect("valid limits"));
        assert_eq!(
            decoder.push(&parts[1]).expect("second part fits"),
            vec!["abcdefgh"]
        );
    }
}
