//! BCBOR profile v1: the deterministic CBOR subset every signed body uses (membership ops, log
//! payloads, wire messages, votes).
//!
//! One value has exactly one encoding, and [`decode`] rejects every other byte string, so a hash of
//! the bytes is a hash of the value. The rules (normative text in `membership/membership-v1.md`
//! section 2 and `log/log-v1.md` section 1; vectors in `membership/bcbor-v1.json`):
//!
//! 1. Only unsigned ints, byte strings, UTF-8 text strings, arrays, maps, `false`, `true` and
//!    `null`. No negative ints, floats, tags, `undefined`, other simple values or indefinite lengths.
//! 2. Ints and lengths use the shortest encoding.
//! 3. Map keys are unsigned ints, strictly ascending, so no duplicates.
//! 4. Exactly one top-level item; trailing bytes are an error.
//! 5. Nesting depth at most [`MAX_DEPTH`]; at most [`MAX_ITEMS`] items per array or map.
//!
//! Sorted sets (arrays whose elements are sorted by their encodings, without duplicates) are a
//! schema-level rule, checked by [`check_sorted_set`].
//!
//! The CBOR itself (heads, lengths, byte and text slicing, UTF-8, truncation, encoding) is the
//! `minicbor` crate's. This module owns only the profile: which major types are allowed, the
//! shortest-form rule (minicbor's decoder accepts longer heads, so each head's width is checked
//! against the value it carries), key order, and the depth and size limits, which it enforces
//! before descending so hostile input never recurses or allocates past them.

use std::collections::BTreeMap;

use thiserror::Error;

/// Maximum nesting depth of arrays and maps (the top-level item is depth 1).
pub const MAX_DEPTH: usize = 16;

/// Maximum number of items in one array or entries in one map.
pub const MAX_ITEMS: u64 = 65_536;

/// A BCBOR value. Maps are keyed by unsigned ints and kept sorted, so [`encode`] is canonical by
/// construction.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Value {
    /// Major type 0.
    Uint(u64),
    /// Major type 2.
    Bytes(Vec<u8>),
    /// Major type 3, valid UTF-8.
    Text(String),
    /// Major type 4.
    Array(Vec<Value>),
    /// Major type 5 with unsigned-int keys.
    Map(BTreeMap<u64, Value>),
    /// `false` (0xf4) or `true` (0xf5).
    Bool(bool),
    /// `null` (0xf6).
    Null,
}

/// Why bytes are not a BCBOR value. Each variant has a stable snake_case code ([`BcborError::code`])
/// that the fixtures name.
#[derive(Clone, Copy, Debug, Error, PartialEq, Eq)]
pub enum BcborError {
    /// The input ended inside an item.
    #[error("truncated input")]
    Truncated,
    /// Bytes follow the top-level item.
    #[error("trailing bytes after the top-level item")]
    TrailingBytes,
    /// An int or length is not in its shortest form.
    #[error("int or length not in shortest form")]
    NonShortest,
    /// An indefinite-length item (additional info 31).
    #[error("indefinite length")]
    IndefiniteLength,
    /// Additional info 28-30, which CBOR reserves.
    #[error("reserved additional info")]
    ReservedInfo,
    /// A negative int (major type 1).
    #[error("negative int")]
    NegativeInt,
    /// A tag (major type 6).
    #[error("tag")]
    Tag,
    /// A float or a simple value other than false, true and null.
    #[error("float or forbidden simple value")]
    ForbiddenSimple,
    /// A map key that is not an unsigned int.
    #[error("map key is not an unsigned int")]
    NonUintKey,
    /// Map keys out of ascending order.
    #[error("map keys not ascending")]
    UnsortedKeys,
    /// The same map key twice.
    #[error("duplicate map key")]
    DuplicateKey,
    /// A text string that is not UTF-8.
    #[error("text string is not UTF-8")]
    InvalidUtf8,
    /// Nesting deeper than [`MAX_DEPTH`].
    #[error("nesting too deep")]
    TooDeep,
    /// More than [`MAX_ITEMS`] items in one array or map.
    #[error("too many items")]
    TooManyItems,
    /// A sorted-set array whose elements are not strictly ascending by encoding.
    #[error("sorted set not strictly ascending")]
    UnsortedSet,
}

impl BcborError {
    /// The stable code the fixtures use for this error.
    #[must_use]
    pub const fn code(self) -> &'static str {
        match self {
            Self::Truncated => "truncated",
            Self::TrailingBytes => "trailing_bytes",
            Self::NonShortest => "non_shortest",
            Self::IndefiniteLength => "indefinite_length",
            Self::ReservedInfo => "reserved_info",
            Self::NegativeInt => "negative_int",
            Self::Tag => "tag",
            Self::ForbiddenSimple => "forbidden_simple",
            Self::NonUintKey => "non_uint_key",
            Self::UnsortedKeys => "unsorted_keys",
            Self::DuplicateKey => "duplicate_key",
            Self::InvalidUtf8 => "invalid_utf8",
            Self::TooDeep => "too_deep",
            Self::TooManyItems => "too_many_items",
            Self::UnsortedSet => "unsorted_set",
        }
    }
}

/// Encodes `value` canonically.
#[must_use]
pub fn encode(value: &Value) -> Vec<u8> {
    let mut out = Vec::new();
    encode_into(value, &mut out);
    out
}

/// Appends the canonical encoding of `value` to `out`. minicbor writes every int and length in its
/// shortest form, and [`Value::Map`] iterates its keys in ascending order.
pub fn encode_into(value: &Value, out: &mut Vec<u8>) {
    let mut encoder = minicbor::Encoder::new(out);
    write_value(&mut encoder, value);
}

fn write_value(encoder: &mut minicbor::Encoder<&mut Vec<u8>>, value: &Value) {
    // Writing to a Vec cannot fail, so every result is Ok.
    let _ = match value {
        Value::Uint(n) => encoder.u64(*n),
        Value::Bytes(bytes) => encoder.bytes(bytes),
        Value::Text(text) => encoder.str(text),
        Value::Array(items) => {
            let _ = encoder.array(len_u64(items.len()));
            for item in items {
                write_value(encoder, item);
            }
            Ok(&mut *encoder)
        }
        Value::Map(entries) => {
            let _ = encoder.map(len_u64(entries.len()));
            for (key, item) in entries {
                let _ = encoder.u64(*key);
                write_value(encoder, item);
            }
            Ok(&mut *encoder)
        }
        Value::Bool(flag) => encoder.bool(*flag),
        Value::Null => encoder.null(),
    };
}

fn len_u64(len: usize) -> u64 {
    u64::try_from(len).unwrap_or(u64::MAX)
}

/// Decodes exactly one canonical BCBOR value from `bytes`.
///
/// # Errors
///
/// The first rule the input breaks, as a [`BcborError`].
pub fn decode(bytes: &[u8]) -> Result<Value, BcborError> {
    let mut reader = Reader {
        decoder: minicbor::Decoder::new(bytes),
    };
    let value = reader.item(1)?;
    if reader.decoder.position() != bytes.len() {
        return Err(BcborError::TrailingBytes);
    }
    Ok(value)
}

/// Checks that `items` is a sorted set: strictly ascending by the bytes of each element's encoding.
///
/// # Errors
///
/// [`BcborError::UnsortedSet`] when two neighbours are equal or out of order.
pub fn check_sorted_set(items: &[Value]) -> Result<(), BcborError> {
    let encoded: Vec<Vec<u8>> = items.iter().map(encode).collect();
    if encoded.windows(2).all(|pair| pair[0] < pair[1]) {
        Ok(())
    } else {
        Err(BcborError::UnsortedSet)
    }
}

/// Builds a sorted-set array from `items`: sorts them by encoding and drops duplicates.
#[must_use]
pub fn sorted_set(items: impl IntoIterator<Item = Value>) -> Value {
    let mut keyed: Vec<(Vec<u8>, Value)> = items.into_iter().map(|v| (encode(&v), v)).collect();
    keyed.sort_by(|left, right| left.0.cmp(&right.0));
    keyed.dedup_by(|left, right| left.0 == right.0);
    Value::Array(keyed.into_iter().map(|(_, v)| v).collect())
}

/// The width in bytes of the shortest head carrying `n`.
const fn shortest_head(n: u64) -> usize {
    match n {
        0..=23 => 1,
        24..=0xff => 2,
        0x100..=0xffff => 3,
        0x1_0000..=0xffff_ffff => 5,
        _ => 9,
    }
}

/// Maps a minicbor error: running out of input is truncation; any other failure is `other` (for a
/// text string that is invalid UTF-8, otherwise a length this platform cannot address, which is
/// truncation too).
fn cbor_error(error: &minicbor::decode::Error, other: BcborError) -> BcborError {
    if error.is_end_of_input() {
        BcborError::Truncated
    } else {
        other
    }
}

struct Reader<'b> {
    decoder: minicbor::Decoder<'b>,
}

impl<'b> Reader<'b> {
    /// The next head byte, without consuming it.
    fn peek_head(&self) -> Result<u8, BcborError> {
        self.decoder
            .input()
            .get(self.decoder.position())
            .copied()
            .ok_or(BcborError::Truncated)
    }

    fn remaining(&self) -> usize {
        self.decoder.input().len() - self.decoder.position()
    }

    /// Rejects the additional-info values no BCBOR head uses: 28-30 (reserved) and 31 (indefinite).
    fn check_info(head: u8) -> Result<(), BcborError> {
        match head & 0x1f {
            31 => Err(BcborError::IndefiniteLength),
            28..=30 => Err(BcborError::ReservedInfo),
            _ => Ok(()),
        }
    }

    /// Runs `read`, which consumes one head and `content` further bytes, and checks that the head
    /// was the shortest one for the `n` it carried.
    fn shortest<T>(
        &mut self,
        other: BcborError,
        read: impl FnOnce(
            &mut minicbor::Decoder<'b>,
        ) -> Result<(T, u64, usize), minicbor::decode::Error>,
    ) -> Result<T, BcborError> {
        let start = self.decoder.position();
        let (value, n, content) =
            read(&mut self.decoder).map_err(|error| cbor_error(&error, other))?;
        if self.decoder.position() - start - content == shortest_head(n) {
            Ok(value)
        } else {
            Err(BcborError::NonShortest)
        }
    }

    fn uint(&mut self) -> Result<u64, BcborError> {
        self.shortest(BcborError::Truncated, |decoder| {
            decoder.u64().map(|n| (n, n, 0))
        })
    }

    fn item(&mut self, depth: usize) -> Result<Value, BcborError> {
        let head = self.peek_head()?;
        match head >> 5 {
            0 => {
                Self::check_info(head)?;
                Ok(Value::Uint(self.uint()?))
            }
            1 => Err(BcborError::NegativeInt),
            2 => {
                Self::check_info(head)?;
                self.shortest(BcborError::Truncated, |decoder| {
                    decoder.bytes().map(|bytes| {
                        (
                            Value::Bytes(bytes.to_vec()),
                            len_u64(bytes.len()),
                            bytes.len(),
                        )
                    })
                })
            }
            3 => {
                Self::check_info(head)?;
                self.shortest(BcborError::InvalidUtf8, |decoder| {
                    decoder.str().map(|text| {
                        (
                            Value::Text(text.to_owned()),
                            len_u64(text.len()),
                            text.len(),
                        )
                    })
                })
            }
            4 => {
                let count = self.count(head, depth, minicbor::Decoder::array)?;
                let mut items = Vec::new();
                for _ in 0..count {
                    items.push(self.item(depth + 1)?);
                }
                Ok(Value::Array(items))
            }
            5 => {
                let count = self.count(head, depth, minicbor::Decoder::map)?;
                let mut entries = BTreeMap::new();
                let mut previous: Option<u64> = None;
                for _ in 0..count {
                    let key_head = self.peek_head()?;
                    if key_head >> 5 != 0 {
                        return Err(BcborError::NonUintKey);
                    }
                    Self::check_info(key_head)?;
                    let key = self.uint()?;
                    match previous {
                        Some(last) if key == last => return Err(BcborError::DuplicateKey),
                        Some(last) if key < last => return Err(BcborError::UnsortedKeys),
                        _ => {}
                    }
                    previous = Some(key);
                    entries.insert(key, self.item(depth + 1)?);
                }
                Ok(Value::Map(entries))
            }
            6 => Err(BcborError::Tag),
            _ => {
                let value = match head {
                    0xf4 | 0xf5 => Value::Bool(head == 0xf5),
                    0xf6 => Value::Null,
                    0xff => return Err(BcborError::IndefiniteLength),
                    _ => return Err(BcborError::ForbiddenSimple),
                };
                self.decoder.set_position(self.decoder.position() + 1);
                Ok(value)
            }
        }
    }

    /// Reads an array or map head: the depth limit first, then the count's form and size.
    fn count(
        &mut self,
        head: u8,
        depth: usize,
        read: impl FnOnce(&mut minicbor::Decoder<'b>) -> Result<Option<u64>, minicbor::decode::Error>,
    ) -> Result<u64, BcborError> {
        if depth > MAX_DEPTH {
            return Err(BcborError::TooDeep);
        }
        Self::check_info(head)?;
        // Info 31 was refused above, so minicbor always reports a definite count.
        let count = self.shortest(BcborError::Truncated, |decoder| {
            read(decoder).map(|count| {
                let count = count.unwrap_or(u64::MAX);
                (count, count, 0)
            })
        })?;
        if count > MAX_ITEMS {
            return Err(BcborError::TooManyItems);
        }
        // Every item takes at least one byte, so a count beyond the input is truncated.
        if count > len_u64(self.remaining()) {
            return Err(BcborError::Truncated);
        }
        Ok(count)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shortest_heads_round_trip() {
        for n in [
            0,
            23,
            24,
            255,
            256,
            65_535,
            65_536,
            u64::from(u32::MAX),
            u64::MAX,
        ] {
            let bytes = encode(&Value::Uint(n));
            assert_eq!(decode(&bytes), Ok(Value::Uint(n)));
        }
    }

    #[test]
    fn non_shortest_is_rejected() {
        assert_eq!(decode(&[0x18, 0x17]), Err(BcborError::NonShortest));
        assert_eq!(decode(&[0x19, 0x00, 0xff]), Err(BcborError::NonShortest));
    }

    #[test]
    fn depth_limit_is_enforced() {
        let mut deep = vec![0x81; MAX_DEPTH - 1];
        deep.push(0x80);
        assert!(decode(&deep).is_ok());
        let mut too_deep = vec![0x81; MAX_DEPTH];
        too_deep.push(0x80);
        assert_eq!(decode(&too_deep), Err(BcborError::TooDeep));
    }

    #[test]
    fn sorted_set_builder_orders_by_encoding() {
        let set = sorted_set([
            Value::Text("bb".into()),
            Value::Text("a".into()),
            Value::Text("a".into()),
        ]);
        let Value::Array(items) = set else {
            panic!("array")
        };
        assert_eq!(
            items,
            vec![Value::Text("a".into()), Value::Text("bb".into())]
        );
        assert!(check_sorted_set(&items).is_ok());
    }
}
