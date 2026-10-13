use thiserror::Error;

/// Largest plain frame, and largest single `frame_part` payload. Matches `IrohFrameCodec`.
pub const MAX_FRAME_BYTES: usize = 1024 * 1024;
/// Largest logical frame a `frame_part` sequence may reassemble to. Matches `IrohFrameCodec`.
pub const MAX_REASSEMBLED_BYTES: usize = 32 * 1024 * 1024;

const HEADER_BYTES: usize = 4;
/// Length prefix that marks a `frame_part` instead of a plain frame.
const PART_SENTINEL: u32 = 0x7FFF_FFFF;
/// flags(1) + part index(4) + part length(4), after the sentinel.
const PART_HEADER_BYTES: usize = 9;
const PART_FLAG_FIN: u8 = 0x01;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum FrameError {
    #[error("frame size {size} exceeds maximum {maximum}")]
    TooLarge { size: usize, maximum: usize },
    #[error("frame stream ended with a truncated frame")]
    Truncated,
    #[error("frame payload is not valid UTF-8")]
    InvalidUtf8,
    #[error("frame part {actual} arrived out of sequence (expected part {expected})")]
    PartOutOfSequence { expected: u32, actual: u32 },
    #[error("frame part is empty")]
    EmptyPart,
    #[error("plain frame interleaved mid frame_part sequence (expected part {expected})")]
    PlainFrameMidSequence { expected: u32 },
}

pub fn encode_frame(payload: &str) -> Result<Vec<u8>, FrameError> {
    let payload = payload.as_bytes();
    if payload.len() > MAX_FRAME_BYTES {
        return Err(FrameError::TooLarge {
            size: payload.len(),
            maximum: MAX_FRAME_BYTES,
        });
    }

    let length = u32::try_from(payload.len()).map_err(|_| FrameError::TooLarge {
        size: payload.len(),
        maximum: MAX_FRAME_BYTES,
    })?;
    let mut frame = Vec::with_capacity(HEADER_BYTES + payload.len());
    frame.extend_from_slice(&length.to_be_bytes());
    frame.extend_from_slice(payload);
    Ok(frame)
}

/// Encodes [payload] as one plain frame when it fits in [`MAX_FRAME_BYTES`], or as an ordered list
/// of `frame_part`s otherwise (the inverse of [`FrameDecoder`], matching
/// `IrohFrameCodec.encodeFrameParts`). Only send parts to a server whose `auth_response`
/// advertised `frame_part`; any other server rejects the sentinel as an oversized frame.
pub fn encode_frame_parts(payload: &str) -> Result<Vec<Vec<u8>>, FrameError> {
    let bytes = payload.as_bytes();
    if bytes.len() <= MAX_FRAME_BYTES {
        return encode_frame(payload).map(|frame| vec![frame]);
    }
    if bytes.len() > MAX_REASSEMBLED_BYTES {
        return Err(FrameError::TooLarge {
            size: bytes.len(),
            maximum: MAX_REASSEMBLED_BYTES,
        });
    }
    let chunks: Vec<&[u8]> = bytes.chunks(MAX_FRAME_BYTES).collect();
    let last = chunks.len() - 1;
    chunks
        .iter()
        .enumerate()
        .map(|(index, chunk)| encode_part(index, index == last, chunk))
        .collect()
}

fn encode_part(index: usize, fin: bool, payload: &[u8]) -> Result<Vec<u8>, FrameError> {
    // Both fit in u32: index < MAX_REASSEMBLED_BYTES / MAX_FRAME_BYTES and a part is <= 1 MiB.
    let too_large = |_| FrameError::TooLarge {
        size: payload.len(),
        maximum: MAX_FRAME_BYTES,
    };
    let index = u32::try_from(index).map_err(too_large)?;
    let length = u32::try_from(payload.len()).map_err(too_large)?;
    let mut part = Vec::with_capacity(HEADER_BYTES + PART_HEADER_BYTES + payload.len());
    part.extend_from_slice(&PART_SENTINEL.to_be_bytes());
    part.push(if fin { PART_FLAG_FIN } else { 0 });
    part.extend_from_slice(&index.to_be_bytes());
    part.extend_from_slice(&length.to_be_bytes());
    part.extend_from_slice(payload);
    Ok(part)
}

/// Decodes plain frames and reassembles `frame_part` sequences (see `IrohFrameCodec` for the
/// wire format). Parts of one logical frame are strictly sequential on a stream, so any plain
/// frame or out-of-order index mid-sequence is a protocol error.
#[derive(Debug, Default)]
pub struct FrameDecoder {
    buffered: Vec<u8>,
    pending_parts: Vec<u8>,
    expected_part_index: u32,
}

impl FrameDecoder {
    #[must_use]
    pub const fn new() -> Self {
        Self {
            buffered: Vec::new(),
            pending_parts: Vec::new(),
            expected_part_index: 0,
        }
    }

    pub fn push(&mut self, chunk: &[u8]) -> Result<Vec<String>, FrameError> {
        self.buffered.extend_from_slice(chunk);
        let mut frames = Vec::new();

        while self.buffered.len() >= HEADER_BYTES {
            let length = read_u32(&self.buffered, 0);
            let decoded = if length == PART_SENTINEL {
                self.take_part()?
            } else {
                self.take_plain(length as usize)?
            };
            match decoded {
                Step::Incomplete => break,
                Step::Consumed => {}
                Step::Frame(frame) => frames.push(frame),
            }
        }

        Ok(frames)
    }

    pub fn finish(self) -> Result<(), FrameError> {
        if self.buffered.is_empty() && !self.reassembly_in_progress() {
            Ok(())
        } else {
            Err(FrameError::Truncated)
        }
    }

    const fn reassembly_in_progress(&self) -> bool {
        self.expected_part_index > 0
    }

    fn take_plain(&mut self, length: usize) -> Result<Step, FrameError> {
        if self.reassembly_in_progress() {
            return Err(FrameError::PlainFrameMidSequence {
                expected: self.expected_part_index,
            });
        }
        if length > MAX_FRAME_BYTES {
            return Err(FrameError::TooLarge {
                size: length,
                maximum: MAX_FRAME_BYTES,
            });
        }
        let frame_end = HEADER_BYTES + length;
        if self.buffered.len() < frame_end {
            return Ok(Step::Incomplete);
        }
        let payload = String::from_utf8(self.buffered[HEADER_BYTES..frame_end].to_vec())
            .map_err(|_| FrameError::InvalidUtf8)?;
        self.buffered.drain(..frame_end);
        Ok(Step::Frame(payload))
    }

    fn take_part(&mut self) -> Result<Step, FrameError> {
        let payload_start = HEADER_BYTES + PART_HEADER_BYTES;
        if self.buffered.len() < payload_start {
            return Ok(Step::Incomplete);
        }
        let fin = self.buffered[HEADER_BYTES] & PART_FLAG_FIN != 0;
        let index = read_u32(&self.buffered, HEADER_BYTES + 1);
        let part_length = read_u32(&self.buffered, HEADER_BYTES + 5) as usize;
        self.check_part_header(index, part_length)?;

        let part_end = payload_start + part_length;
        if self.buffered.len() < part_end {
            return Ok(Step::Incomplete);
        }
        self.pending_parts
            .extend_from_slice(&self.buffered[payload_start..part_end]);
        self.buffered.drain(..part_end);
        self.expected_part_index += 1;
        if !fin {
            return Ok(Step::Consumed);
        }

        self.expected_part_index = 0;
        let payload = std::mem::take(&mut self.pending_parts);
        String::from_utf8(payload)
            .map(Step::Frame)
            .map_err(|_| FrameError::InvalidUtf8)
    }

    /// Rejects a bad part from its header alone, before its payload is buffered.
    const fn check_part_header(&self, index: u32, part_length: usize) -> Result<(), FrameError> {
        if index != self.expected_part_index {
            return Err(FrameError::PartOutOfSequence {
                expected: self.expected_part_index,
                actual: index,
            });
        }
        if part_length == 0 {
            return Err(FrameError::EmptyPart);
        }
        if part_length > MAX_FRAME_BYTES {
            return Err(FrameError::TooLarge {
                size: part_length,
                maximum: MAX_FRAME_BYTES,
            });
        }
        let reassembled = self.pending_parts.len() + part_length;
        if reassembled > MAX_REASSEMBLED_BYTES {
            return Err(FrameError::TooLarge {
                size: reassembled,
                maximum: MAX_REASSEMBLED_BYTES,
            });
        }
        Ok(())
    }
}

enum Step {
    /// The buffer ends partway through a frame or part.
    Incomplete,
    /// A non-final part was taken; more parts follow.
    Consumed,
    Frame(String),
}

fn read_u32(bytes: &[u8], offset: usize) -> u32 {
    u32::from_be_bytes([
        bytes[offset],
        bytes[offset + 1],
        bytes[offset + 2],
        bytes[offset + 3],
    ])
}
