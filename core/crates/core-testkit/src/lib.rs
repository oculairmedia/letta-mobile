//! Test support for the Rust core: loads the language-neutral contract fixtures under
//! `docs/reference/contracts/`, which the Kotlin node's tests read too, and expands the byte-vector
//! notation the codec fixtures use.
//!
//! The fixtures are found relative to this crate (`core/crates/core-testkit` is three levels below
//! the repository root). Set `BAKEN_CONTRACTS_DIR` to read them from somewhere else.

use std::path::{Path, PathBuf};

use serde::Deserialize;
use serde::de::DeserializeOwned;
use serde_json::Value;
use thiserror::Error;

/// The environment variable that overrides where the contracts live.
pub const CONTRACTS_DIR_ENV: &str = "BAKEN_CONTRACTS_DIR";

/// A fixture that could not be loaded.
#[derive(Debug, Error)]
pub enum FixtureError {
    /// The file could not be read.
    #[error("reading fixture {path}: {source}")]
    Read {
        /// The fixture's path.
        path: PathBuf,
        /// The I/O error.
        source: std::io::Error,
    },
    /// The file is not the JSON the caller asked for.
    #[error("parsing fixture {path}: {source}")]
    Parse {
        /// The fixture's path.
        path: PathBuf,
        /// The JSON error.
        source: serde_json::Error,
    },
    /// A byte-vector segment is malformed.
    #[error("bad byte-vector segment: {0}")]
    Bytes(String),
}

/// The directory holding the contract specs and fixtures: `docs/reference/contracts`.
#[must_use]
pub fn contracts_dir() -> PathBuf {
    std::env::var_os(CONTRACTS_DIR_ENV).map_or_else(
        || Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../docs/reference/contracts"),
        PathBuf::from,
    )
}

/// The path of `relative` under [`contracts_dir`].
#[must_use]
pub fn contract_path(relative: &str) -> PathBuf {
    contracts_dir().join(relative)
}

/// Reads and parses the JSON fixture at `relative` under [`contracts_dir`].
///
/// # Errors
///
/// [`FixtureError::Read`] or [`FixtureError::Parse`].
pub fn load_json<T: DeserializeOwned>(relative: &str) -> Result<T, FixtureError> {
    let path = contract_path(relative);
    let text = std::fs::read_to_string(&path).map_err(|source| FixtureError::Read {
        path: path.clone(),
        source,
    })?;
    serde_json::from_str(&text).map_err(|source| FixtureError::Parse { path, source })
}

/// Reads the JSON fixture at `relative` as an untyped value.
///
/// # Errors
///
/// [`FixtureError::Read`] or [`FixtureError::Parse`].
pub fn load_value(relative: &str) -> Result<Value, FixtureError> {
    load_json(relative)
}

/// One segment of a byte vector, as fixtures write it.
///
/// A byte vector is a JSON array of segments, concatenated in order:
/// `{"hex": "0000000568656c6c6f"}` (lower- or upper-case hex), `{"utf8": "hello"}` (the text's UTF-8
/// bytes), or `{"repeat_hex": "78", "count": 1048576}` (the hex bytes repeated `count` times, for
/// payloads too large to write out).
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
#[serde(untagged)]
pub enum Segment {
    /// Literal bytes in hex.
    Hex {
        /// The hex digits.
        hex: String,
    },
    /// The UTF-8 bytes of a text.
    Utf8 {
        /// The text.
        utf8: String,
    },
    /// Hex bytes repeated.
    Repeat {
        /// The hex digits of the repeated unit.
        repeat_hex: String,
        /// How many times.
        count: usize,
    },
}

/// A byte vector: segments concatenated in order.
#[derive(Clone, Debug, Default, PartialEq, Eq, Deserialize)]
#[serde(transparent)]
pub struct ByteVector(pub Vec<Segment>);

impl ByteVector {
    /// The bytes.
    ///
    /// # Errors
    ///
    /// [`FixtureError::Bytes`] when a hex segment is malformed.
    pub fn to_bytes(&self) -> Result<Vec<u8>, FixtureError> {
        let mut bytes = Vec::new();
        for segment in &self.0 {
            match segment {
                Segment::Hex { hex } => bytes.extend(decode_hex(hex)?),
                Segment::Utf8 { utf8 } => bytes.extend_from_slice(utf8.as_bytes()),
                Segment::Repeat { repeat_hex, count } => {
                    let unit = decode_hex(repeat_hex)?;
                    bytes.reserve(unit.len() * count);
                    for _ in 0..*count {
                        bytes.extend_from_slice(&unit);
                    }
                }
            }
        }
        Ok(bytes)
    }
}

/// Decodes hex digits (either case, no separators).
///
/// # Errors
///
/// [`FixtureError::Bytes`] for an odd length or a non-hex digit.
pub fn decode_hex(text: &str) -> Result<Vec<u8>, FixtureError> {
    hex::decode(text).map_err(|error| FixtureError::Bytes(format!("{error} in {text:?}")))
}

/// Encodes bytes as lowercase hex, for readable assertion failures.
#[must_use]
pub fn encode_hex(bytes: &[u8]) -> String {
    hex::encode(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn segments_concatenate() {
        let vector: ByteVector = serde_json::from_str(
            r#"[{"hex":"0000000A"},{"utf8":"é"},{"repeat_hex":"7879","count":2}]"#,
        )
        .expect("valid vector");
        assert_eq!(
            vector.to_bytes().expect("valid bytes"),
            vec![0, 0, 0, 10, 0xc3, 0xa9, b'x', b'y', b'x', b'y']
        );
    }

    #[test]
    fn malformed_hex_is_an_error() {
        assert!(decode_hex("abc").is_err());
        assert!(decode_hex("zz").is_err());
        assert_eq!(encode_hex(&[0, 0xab]), "00ab");
    }

    #[test]
    fn the_contracts_directory_exists() {
        assert!(
            contracts_dir().join("README.md").is_file(),
            "{}",
            contracts_dir().display()
        );
    }
}
