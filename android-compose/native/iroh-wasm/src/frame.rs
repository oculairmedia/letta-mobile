use thiserror::Error;

pub const MAX_FRAME_BYTES: usize = 1024 * 1024;
const HEADER_BYTES: usize = 4;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum FrameError {
    #[error("frame size {size} exceeds maximum {maximum}")]
    TooLarge { size: usize, maximum: usize },
    #[error("frame stream ended with a truncated frame")]
    Truncated,
    #[error("frame payload is not valid UTF-8")]
    InvalidUtf8,
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

#[derive(Debug, Default)]
pub struct FrameDecoder {
    buffered: Vec<u8>,
}

impl FrameDecoder {
    #[must_use]
    pub const fn new() -> Self {
        Self {
            buffered: Vec::new(),
        }
    }

    pub fn push(&mut self, chunk: &[u8]) -> Result<Vec<String>, FrameError> {
        self.buffered.extend_from_slice(chunk);
        let mut frames = Vec::new();

        loop {
            if self.buffered.len() < HEADER_BYTES {
                break;
            }
            let length = u32::from_be_bytes([
                self.buffered[0],
                self.buffered[1],
                self.buffered[2],
                self.buffered[3],
            ]) as usize;
            if length > MAX_FRAME_BYTES {
                return Err(FrameError::TooLarge {
                    size: length,
                    maximum: MAX_FRAME_BYTES,
                });
            }
            let frame_end = HEADER_BYTES + length;
            if self.buffered.len() < frame_end {
                break;
            }
            let payload = String::from_utf8(self.buffered[HEADER_BYTES..frame_end].to_vec())
                .map_err(|_| FrameError::InvalidUtf8)?;
            self.buffered.drain(..frame_end);
            frames.push(payload);
        }

        Ok(frames)
    }

    pub fn finish(self) -> Result<(), FrameError> {
        if self.buffered.is_empty() {
            Ok(())
        } else {
            Err(FrameError::Truncated)
        }
    }
}
