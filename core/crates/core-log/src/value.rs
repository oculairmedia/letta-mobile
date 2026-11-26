//! Helpers for reading and building the BCBOR maps payloads and wire messages use.

use std::collections::BTreeMap;

use core_proto::bcbor::{BcborError, Value};
use core_proto::{Epoch, NodeId};
use core_store::EntryHash;
use thiserror::Error;

/// A position on a line: `{0: seq, 1: hash}`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Pos {
    /// The seq.
    pub seq: u64,
    /// The entry hash at that seq.
    pub hash: EntryHash,
}

/// Why a BCBOR value does not have the shape a schema needs.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
pub enum ShapeError {
    /// The bytes are not BCBOR.
    #[error("bcbor: {0}")]
    Bcbor(#[from] BcborError),
    /// A required key is absent.
    #[error("missing key {0}")]
    Missing(u64),
    /// A key holds the wrong type.
    #[error("key {0} has the wrong type")]
    Type(u64),
    /// A rule of the schema is broken.
    #[error("{0}")]
    Invalid(String),
}

impl ShapeError {
    /// The stable code fixtures name.
    #[must_use]
    pub fn code(&self) -> String {
        match self {
            Self::Bcbor(error) => format!("bcbor_{}", error.code()),
            Self::Missing(_) => "missing_key".into(),
            Self::Type(_) => "wrong_type".into(),
            Self::Invalid(_) => "invalid".into(),
        }
    }
}

pub(crate) type Map = BTreeMap<u64, Value>;

/// Reads fields from a BCBOR map. Absent and `null` are the same for optional fields.
pub(crate) struct Fields<'a>(pub(crate) &'a Map);

impl<'a> Fields<'a> {
    pub(crate) fn of(value: &'a Value) -> Result<Self, ShapeError> {
        match value {
            Value::Map(map) => Ok(Self(map)),
            _ => Err(ShapeError::Invalid("expected a map".into())),
        }
    }

    pub(crate) fn opt(&self, key: u64) -> Option<&'a Value> {
        match self.0.get(&key) {
            None | Some(Value::Null) => None,
            Some(value) => Some(value),
        }
    }

    pub(crate) fn req(&self, key: u64) -> Result<&'a Value, ShapeError> {
        self.opt(key).ok_or(ShapeError::Missing(key))
    }

    pub(crate) fn uint(&self, key: u64) -> Result<u64, ShapeError> {
        as_uint(self.req(key)?, key)
    }

    pub(crate) fn opt_uint(&self, key: u64) -> Result<Option<u64>, ShapeError> {
        self.opt(key).map(|v| as_uint(v, key)).transpose()
    }

    pub(crate) fn text(&self, key: u64) -> Result<String, ShapeError> {
        as_text(self.req(key)?, key)
    }

    pub(crate) fn opt_text(&self, key: u64) -> Result<Option<String>, ShapeError> {
        self.opt(key).map(|v| as_text(v, key)).transpose()
    }

    pub(crate) fn bytes(&self, key: u64) -> Result<Vec<u8>, ShapeError> {
        as_bytes(self.req(key)?, key).map(<[u8]>::to_vec)
    }

    pub(crate) fn opt_bytes(&self, key: u64) -> Result<Option<Vec<u8>>, ShapeError> {
        self.opt(key)
            .map(|v| as_bytes(v, key).map(<[u8]>::to_vec))
            .transpose()
    }

    pub(crate) fn bytes32(&self, key: u64) -> Result<[u8; 32], ShapeError> {
        as_bytes32(self.req(key)?, key)
    }

    pub(crate) fn boolean(&self, key: u64) -> Result<bool, ShapeError> {
        match self.req(key)? {
            Value::Bool(b) => Ok(*b),
            _ => Err(ShapeError::Type(key)),
        }
    }

    pub(crate) fn opt_bool(&self, key: u64) -> Result<bool, ShapeError> {
        match self.opt(key) {
            None => Ok(false),
            Some(Value::Bool(b)) => Ok(*b),
            Some(_) => Err(ShapeError::Type(key)),
        }
    }

    pub(crate) fn array(&self, key: u64) -> Result<&'a [Value], ShapeError> {
        as_array(self.req(key)?, key)
    }

    pub(crate) fn opt_array(&self, key: u64) -> Result<&'a [Value], ShapeError> {
        match self.opt(key) {
            None => Ok(&[]),
            Some(value) => as_array(value, key),
        }
    }

    pub(crate) fn epoch(&self, key: u64) -> Result<Epoch, ShapeError> {
        as_epoch(self.req(key)?, key)
    }

    pub(crate) fn opt_epoch(&self, key: u64) -> Result<Option<Epoch>, ShapeError> {
        self.opt(key).map(|v| as_epoch(v, key)).transpose()
    }

    pub(crate) fn pos(&self, key: u64) -> Result<Pos, ShapeError> {
        as_pos(self.req(key)?, key)
    }

    pub(crate) fn opt_pos(&self, key: u64) -> Result<Option<Pos>, ShapeError> {
        self.opt(key).map(|v| as_pos(v, key)).transpose()
    }
}

pub(crate) fn as_uint(value: &Value, key: u64) -> Result<u64, ShapeError> {
    match value {
        Value::Uint(n) => Ok(*n),
        _ => Err(ShapeError::Type(key)),
    }
}

pub(crate) fn as_text(value: &Value, key: u64) -> Result<String, ShapeError> {
    match value {
        Value::Text(text) => Ok(text.clone()),
        _ => Err(ShapeError::Type(key)),
    }
}

pub(crate) fn as_bytes(value: &Value, key: u64) -> Result<&[u8], ShapeError> {
    match value {
        Value::Bytes(bytes) => Ok(bytes),
        _ => Err(ShapeError::Type(key)),
    }
}

pub(crate) fn as_bytes32(value: &Value, key: u64) -> Result<[u8; 32], ShapeError> {
    <[u8; 32]>::try_from(as_bytes(value, key)?).map_err(|_| ShapeError::Type(key))
}

pub(crate) fn as_array(value: &Value, key: u64) -> Result<&[Value], ShapeError> {
    match value {
        Value::Array(items) => Ok(items),
        _ => Err(ShapeError::Type(key)),
    }
}

pub(crate) fn as_epoch(value: &Value, key: u64) -> Result<Epoch, ShapeError> {
    match as_array(value, key)? {
        [n, claimant] => Ok(Epoch {
            n: as_uint(n, key)?,
            claimant: NodeId::from_bytes(as_bytes32(claimant, key)?),
        }),
        _ => Err(ShapeError::Type(key)),
    }
}

pub(crate) fn as_pos(value: &Value, key: u64) -> Result<Pos, ShapeError> {
    let fields = Fields::of(value).map_err(|_| ShapeError::Type(key))?;
    Ok(Pos {
        seq: fields.uint(0)?,
        hash: EntryHash::from_bytes(fields.bytes32(1)?),
    })
}

/// Builds a map from `(key, value)` pairs, dropping `None`s.
pub(crate) fn map(entries: impl IntoIterator<Item = (u64, Option<Value>)>) -> Value {
    Value::Map(
        entries
            .into_iter()
            .filter_map(|(k, v)| v.map(|v| (k, v)))
            .collect(),
    )
}

#[allow(clippy::unnecessary_wraps)] // Map builders take optional fields.
pub(crate) fn uint(n: u64) -> Option<Value> {
    Some(Value::Uint(n))
}

#[allow(clippy::unnecessary_wraps)] // Map builders take optional fields.
pub(crate) fn text(text: &str) -> Option<Value> {
    Some(Value::Text(text.to_owned()))
}

#[allow(clippy::unnecessary_wraps)] // Map builders take optional fields.
pub(crate) fn bytes(bytes: &[u8]) -> Option<Value> {
    Some(Value::Bytes(bytes.to_vec()))
}

#[allow(clippy::unnecessary_wraps)] // Map builders take optional fields.
pub(crate) fn boolean(b: bool) -> Option<Value> {
    Some(Value::Bool(b))
}

/// An epoch as the array `[n, claimant]`.
#[must_use]
pub fn epoch_value(epoch: &Epoch) -> Value {
    Value::Array(vec![
        Value::Uint(epoch.n),
        Value::Bytes(epoch.claimant.as_bytes().to_vec()),
    ])
}

/// A position as the map `{0: seq, 1: hash}`.
#[must_use]
pub fn pos_value(pos: &Pos) -> Value {
    map([(0, uint(pos.seq)), (1, bytes(pos.hash.as_bytes()))])
}

/// Requires `items` to be strictly ascending by `key`.
pub(crate) fn check_ascending<T, K: Ord>(
    items: &[T],
    key: impl Fn(&T) -> K,
    what: &str,
) -> Result<(), ShapeError> {
    if items.windows(2).all(|pair| key(&pair[0]) < key(&pair[1])) {
        Ok(())
    } else {
        Err(ShapeError::Invalid(format!(
            "{what} must be strictly ascending"
        )))
    }
}
