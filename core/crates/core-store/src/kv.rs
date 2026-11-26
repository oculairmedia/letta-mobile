//! Namespaced, ordered key-value bytes, plus the key-path encoding that gives component-prefix
//! scans (what samod's storage adapter needs for `load_range`).

use crate::error::StoreError;

/// A KV namespace: a short lowercase name that keeps one user's keys apart from another's.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Namespace(&'static str);

impl Namespace {
    /// The catalog's samod chunk storage.
    pub const SAMOD: Self = Self::new("samod");

    /// A namespace. The name matches `^[a-z][a-z0-9_.-]{0,63}$`.
    ///
    /// # Panics
    ///
    /// When the name breaks that pattern (at compile time for a `const`).
    #[must_use]
    pub const fn new(name: &'static str) -> Self {
        let bytes = name.as_bytes();
        assert!(
            !bytes.is_empty() && bytes.len() <= 64,
            "namespace length 1..=64"
        );
        assert!(bytes[0].is_ascii_lowercase(), "namespace starts with a-z");
        let mut index = 1;
        while index < bytes.len() {
            let byte = bytes[index];
            assert!(
                byte.is_ascii_lowercase()
                    || byte.is_ascii_digit()
                    || byte == b'_'
                    || byte == b'.'
                    || byte == b'-',
                "namespace characters are a-z 0-9 _ . -"
            );
            index += 1;
        }
        Self(name)
    }

    /// The name.
    #[must_use]
    pub const fn as_str(&self) -> &'static str {
        self.0
    }
}

/// One (key, value) pair from a scan.
pub type KvEntry = (Vec<u8>, Vec<u8>);

/// The KV area of a transaction. Keys order bytewise within a namespace.
pub trait KvStore {
    /// The value under `key`.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn kv_get(&self, namespace: Namespace, key: &[u8]) -> Result<Option<Vec<u8>>, StoreError>;

    /// Every `(key, value)` whose key starts with `prefix`, ordered by key. An empty prefix scans the
    /// namespace.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn kv_scan_prefix(
        &self,
        namespace: Namespace,
        prefix: &[u8],
    ) -> Result<Vec<KvEntry>, StoreError>;

    /// Inserts or replaces the value under `key`.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn kv_put(&mut self, namespace: Namespace, key: &[u8], value: &[u8]) -> Result<(), StoreError>;

    /// Deletes `key`. Returns `true` when it existed.
    ///
    /// # Errors
    ///
    /// A backend [`StoreError`].
    fn kv_delete(&mut self, namespace: Namespace, key: &[u8]) -> Result<bool, StoreError>;
}

const TERMINATOR: u8 = 0x00;
const ESCAPE: u8 = 0x01;

/// Encodes a key path (a list of string components) so that the encoding of a component prefix is
/// a byte prefix of the encoding of every path that extends it, and of no other path.
///
/// Each component is its UTF-8 bytes with `0x00` written as `01 01` and `0x01` as `01 02`, followed
/// by a `0x00` terminator.
#[must_use]
pub fn encode_key_path<S: AsRef<str>>(components: &[S]) -> Vec<u8> {
    let mut out = Vec::new();
    for component in components {
        for &byte in component.as_ref().as_bytes() {
            match byte {
                TERMINATOR => out.extend_from_slice(&[ESCAPE, 0x01]),
                ESCAPE => out.extend_from_slice(&[ESCAPE, 0x02]),
                other => out.push(other),
            }
        }
        out.push(TERMINATOR);
    }
    out
}

/// Decodes [`encode_key_path`] output.
///
/// # Errors
///
/// [`StoreError::Corrupt`] for a bad escape, a missing terminator or a component that is not UTF-8.
pub fn decode_key_path(bytes: &[u8]) -> Result<Vec<String>, StoreError> {
    let mut components = Vec::new();
    let mut current = Vec::new();
    let mut iter = bytes.iter();
    while let Some(&byte) = iter.next() {
        match byte {
            TERMINATOR => {
                let text = String::from_utf8(std::mem::take(&mut current))
                    .map_err(|_| StoreError::Corrupt("key path component is not UTF-8".into()))?;
                components.push(text);
            }
            ESCAPE => match iter.next() {
                Some(0x01) => current.push(TERMINATOR),
                Some(0x02) => current.push(ESCAPE),
                _ => return Err(StoreError::Corrupt("bad escape in key path".into())),
            },
            other => current.push(other),
        }
    }
    if current.is_empty() {
        Ok(components)
    } else {
        Err(StoreError::Corrupt(
            "key path ends without a terminator".into(),
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_component_prefix_is_a_byte_prefix_and_nothing_else_is() {
        let doc = encode_key_path(&["doc1"]);
        let chunk = encode_key_path(&["doc1", "incremental", "abc"]);
        let other = encode_key_path(&["doc10", "snapshot"]);
        assert!(chunk.starts_with(&doc));
        assert!(!other.starts_with(&doc));
    }

    #[test]
    fn control_bytes_round_trip() {
        let path = vec!["a\u{0}b".to_string(), "\u{1}".to_string(), String::new()];
        let encoded = encode_key_path(&path);
        assert_eq!(decode_key_path(&encoded).expect("decodes"), path);
    }

    #[test]
    fn malformed_paths_are_rejected() {
        assert!(decode_key_path(b"abc").is_err());
        assert!(decode_key_path(&[0x01, 0x07, 0x00]).is_err());
        assert!(decode_key_path(&[0xff, 0x00]).is_err());
    }
}
