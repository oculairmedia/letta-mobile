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

/// Appends the canonical encoding of `value` to `out`.
pub fn encode_into(value: &Value, out: &mut Vec<u8>) {
    match value {
        Value::Uint(n) => write_head(out, 0, *n),
        Value::Bytes(bytes) => {
            write_head(out, 2, len_u64(bytes.len()));
            out.extend_from_slice(bytes);
        }
        Value::Text(text) => {
            write_head(out, 3, len_u64(text.len()));
            out.extend_from_slice(text.as_bytes());
        }
        Value::Array(items) => {
            write_head(out, 4, len_u64(items.len()));
            for item in items {
                encode_into(item, out);
            }
        }
        Value::Map(entries) => {
            write_head(out, 5, len_u64(entries.len()));
            for (key, item) in entries {
                write_head(out, 0, *key);
                encode_into(item, out);
            }
        }
        Value::Bool(false) => out.push(0xf4),
        Value::Bool(true) => out.push(0xf5),
        Value::Null => out.push(0xf6),
    }
}

fn len_u64(len: usize) -> u64 {
    u64::try_from(len).unwrap_or(u64::MAX)
}

fn write_head(out: &mut Vec<u8>, major: u8, n: u64) {
    let major = major << 5;
    if n < 24 {
        // n < 24 fits in the low five bits.
        out.push(major | u8::try_from(n).unwrap_or(0));
    } else if let Ok(small) = u8::try_from(n) {
        out.push(major | 0x18);
        out.push(small);
    } else if let Ok(medium) = u16::try_from(n) {
        out.push(major | 0x19);
        out.extend_from_slice(&medium.to_be_bytes());
    } else if let Ok(large) = u32::try_from(n) {
        out.push(major | 0x1a);
        out.extend_from_slice(&large.to_be_bytes());
    } else {
        out.push(major | 0x1b);
        out.extend_from_slice(&n.to_be_bytes());
    }
}

/// Decodes exactly one canonical BCBOR value from `bytes`.
///
/// # Errors
///
/// The first rule the input breaks, as a [`BcborError`].
pub fn decode(bytes: &[u8]) -> Result<Value, BcborError> {
    let mut reader = Reader { bytes, pos: 0 };
    let value = reader.item(1)?;
    if reader.pos != bytes.len() {
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

struct Reader<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl Reader<'_> {
    fn take(&mut self, count: usize) -> Result<&[u8], BcborError> {
        let end = self.pos.checked_add(count).ok_or(BcborError::Truncated)?;
        let slice = self.bytes.get(self.pos..end).ok_or(BcborError::Truncated)?;
        self.pos = end;
        Ok(slice)
    }

    fn byte(&mut self) -> Result<u8, BcborError> {
        Ok(self.take(1)?[0])
    }

    /// Reads an item head's argument for additional info `info`, enforcing the shortest form.
    fn argument(&mut self, info: u8) -> Result<u64, BcborError> {
        let (value, minimum) = match info {
            0..=23 => return Ok(u64::from(info)),
            24 => (u64::from(self.byte()?), 24),
            25 => {
                let raw = self.take(2)?;
                (u64::from(u16::from_be_bytes([raw[0], raw[1]])), 0x100)
            }
            26 => {
                let raw = self.take(4)?;
                (
                    u64::from(u32::from_be_bytes([raw[0], raw[1], raw[2], raw[3]])),
                    0x1_0000,
                )
            }
            27 => {
                let raw = self.take(8)?;
                let mut array = [0_u8; 8];
                array.copy_from_slice(raw);
                (u64::from_be_bytes(array), 0x1_0000_0000)
            }
            31 => return Err(BcborError::IndefiniteLength),
            _ => return Err(BcborError::ReservedInfo),
        };
        if value < minimum {
            return Err(BcborError::NonShortest);
        }
        Ok(value)
    }

    fn length(&mut self, info: u8) -> Result<usize, BcborError> {
        let length = self.argument(info)?;
        usize::try_from(length).map_err(|_| BcborError::Truncated)
    }

    fn item(&mut self, depth: usize) -> Result<Value, BcborError> {
        let head = self.byte()?;
        let major = head >> 5;
        let info = head & 0x1f;
        match major {
            0 => Ok(Value::Uint(self.argument(info)?)),
            1 => Err(BcborError::NegativeInt),
            2 => {
                let length = self.length(info)?;
                Ok(Value::Bytes(self.take(length)?.to_vec()))
            }
            3 => {
                let length = self.length(info)?;
                let raw = self.take(length)?;
                let text = std::str::from_utf8(raw).map_err(|_| BcborError::InvalidUtf8)?;
                Ok(Value::Text(text.to_owned()))
            }
            4 => self.array(info, depth),
            5 => self.map(info, depth),
            6 => Err(BcborError::Tag),
            _ => match head {
                0xf4 => Ok(Value::Bool(false)),
                0xf5 => Ok(Value::Bool(true)),
                0xf6 => Ok(Value::Null),
                0xff => Err(BcborError::IndefiniteLength),
                _ => Err(BcborError::ForbiddenSimple),
            },
        }
    }

    fn count(&mut self, info: u8, depth: usize) -> Result<u64, BcborError> {
        if depth > MAX_DEPTH {
            return Err(BcborError::TooDeep);
        }
        let count = self.argument(info)?;
        if count > MAX_ITEMS {
            return Err(BcborError::TooManyItems);
        }
        // Every item takes at least one byte, so a count beyond the input is truncated.
        if count > len_u64(self.bytes.len() - self.pos) {
            return Err(BcborError::Truncated);
        }
        Ok(count)
    }

    fn array(&mut self, info: u8, depth: usize) -> Result<Value, BcborError> {
        let count = self.count(info, depth)?;
        let mut items = Vec::new();
        for _ in 0..count {
            items.push(self.item(depth + 1)?);
        }
        Ok(Value::Array(items))
    }

    fn map(&mut self, info: u8, depth: usize) -> Result<Value, BcborError> {
        let count = self.count(info, depth)?;
        let mut entries = BTreeMap::new();
        let mut previous: Option<u64> = None;
        for _ in 0..count {
            let head = self.byte()?;
            if head >> 5 != 0 {
                return Err(BcborError::NonUintKey);
            }
            let key = self.argument(head & 0x1f)?;
            match previous {
                Some(last) if key == last => return Err(BcborError::DuplicateKey),
                Some(last) if key < last => return Err(BcborError::UnsortedKeys),
                _ => {}
            }
            previous = Some(key);
            let value = self.item(depth + 1)?;
            entries.insert(key, value);
        }
        Ok(Value::Map(entries))
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
