//! Membership ops: the typed body, its canonical BCBOR encoding, and the signed envelope.
//!
//! A body is a BCBOR map with the keys of `membership-v1.md` section 3. Decoding is strict: the body
//! must be canonical, its kind known, and it must carry exactly the fields its kind allows. Keys 64
//! and up are extensions: they are kept and re-encoded but carry no meaning in v1.

use std::collections::{BTreeMap, BTreeSet};

use core_proto::NodeId;
use thiserror::Error;

use crate::capability::{CapSet, Capability};
use crate::crypto::{self, NodeKey, OpId, Signature};
use crate::policy::node_id;
use core_proto::bcbor::{self, BcborError, Value};

/// The largest op body, in bytes.
pub const MAX_OP_BYTES: usize = 8 * 1024;

/// The only body version this crate reads and writes.
pub const OP_VERSION: u64 = 1;

/// The longest node name, in bytes.
pub const MAX_NAME_BYTES: usize = 64;

/// The longest constellation label, in bytes.
pub const MAX_LABEL_BYTES: usize = 64;

/// The longest revoke reason, in bytes.
pub const MAX_REASON_BYTES: usize = 256;

/// Body map keys.
pub mod key {
    /// `v`, the body version.
    pub const V: u64 = 0;
    /// `kind`.
    pub const KIND: u64 = 1;
    /// `constellation`: the genesis op id.
    pub const CONSTELLATION: u64 = 2;
    /// `issuer`: the signing node id.
    pub const ISSUER: u64 = 3;
    /// `parents`: sorted set of op ids.
    pub const PARENTS: u64 = 4;
    /// `ts_ms`, informational.
    pub const TS_MS: u64 = 5;
    /// `subject`.
    pub const SUBJECT: u64 = 6;
    /// `caps_add`: sorted set of capability strings.
    pub const CAPS_ADD: u64 = 7;
    /// `caps_remove`: sorted set of capability strings.
    pub const CAPS_REMOVE: u64 = 8;
    /// `name`.
    pub const NAME: u64 = 9;
    /// `new_key`.
    pub const NEW_KEY: u64 = 10;
    /// `new_key_sig`: the proof of possession.
    pub const NEW_KEY_SIG: u64 = 11;
    /// `reason`.
    pub const REASON: u64 = 12;
    /// `policy`.
    pub const POLICY: u64 = 13;
    /// `label`.
    pub const LABEL: u64 = 14;
    /// `catalog_root`.
    pub const CATALOG_ROOT: u64 = 15;
    /// `new_root`.
    pub const NEW_ROOT: u64 = 16;
    /// `prev_root`.
    pub const PREV_ROOT: u64 = 17;
    /// The first extension key; keys below it are reserved for the spec.
    pub const FIRST_EXTENSION: u64 = 64;
}

/// The kind of a membership op.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum OpKind {
    /// 0: creates the constellation and its first admin.
    Genesis,
    /// 1: admits a node.
    Add,
    /// 2: tombstones a node id for ever.
    Revoke,
    /// 3: adds and removes capabilities of an active member.
    Grant,
    /// 4: moves a member to a new key.
    Rotate,
    /// 5: sets the failover policy.
    PolicySet,
    /// 6: replaces the catalog root document.
    CatalogRootMove,
}

impl OpKind {
    /// The kind's wire number.
    #[must_use]
    pub const fn code(self) -> u64 {
        match self {
            Self::Genesis => 0,
            Self::Add => 1,
            Self::Revoke => 2,
            Self::Grant => 3,
            Self::Rotate => 4,
            Self::PolicySet => 5,
            Self::CatalogRootMove => 6,
        }
    }

    /// The kind with wire number `code`.
    #[must_use]
    pub const fn from_code(code: u64) -> Option<Self> {
        Some(match code {
            0 => Self::Genesis,
            1 => Self::Add,
            2 => Self::Revoke,
            3 => Self::Grant,
            4 => Self::Rotate,
            5 => Self::PolicySet,
            6 => Self::CatalogRootMove,
            _ => return None,
        })
    }

    /// The kind's snake_case name, as fixtures write it.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::Genesis => "genesis",
            Self::Add => "add",
            Self::Revoke => "revoke",
            Self::Grant => "grant",
            Self::Rotate => "rotate",
            Self::PolicySet => "policy_set",
            Self::CatalogRootMove => "catalog_root_move",
        }
    }

    /// The tie-break class of the linear order: genesis 0, add 1, grant 2, rotate 3, policy_set 4,
    /// catalog_root_move 5, revoke 6.
    #[must_use]
    pub const fn linear_class(self) -> u8 {
        match self {
            Self::Genesis => 0,
            Self::Add => 1,
            Self::Grant => 2,
            Self::Rotate => 3,
            Self::PolicySet => 4,
            Self::CatalogRootMove => 5,
            Self::Revoke => 6,
        }
    }

    /// (required, optional) reserved keys beyond the common header.
    const fn fields(self) -> (&'static [u64], &'static [u64]) {
        use key::{
            CAPS_ADD, CAPS_REMOVE, CATALOG_ROOT, CONSTELLATION, LABEL, NAME, NEW_KEY, NEW_KEY_SIG,
            NEW_ROOT, POLICY, PREV_ROOT, REASON, SUBJECT,
        };
        match self {
            Self::Genesis => (&[SUBJECT, CAPS_ADD, CATALOG_ROOT], &[NAME, LABEL]),
            Self::Add => (&[CONSTELLATION, SUBJECT, CAPS_ADD], &[NAME]),
            Self::Revoke => (&[CONSTELLATION, SUBJECT], &[REASON]),
            Self::Grant => (&[CONSTELLATION, SUBJECT, CAPS_ADD, CAPS_REMOVE], &[]),
            Self::Rotate => (&[CONSTELLATION, SUBJECT, NEW_KEY, NEW_KEY_SIG], &[]),
            Self::PolicySet => (&[CONSTELLATION, POLICY], &[]),
            Self::CatalogRootMove => (&[CONSTELLATION, NEW_ROOT, PREV_ROOT], &[]),
        }
    }
}

/// A catalog document id (16 bytes), as C2 assigns it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct CatalogRoot(pub [u8; 16]);

/// The fields every op carries.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct OpHeader {
    /// The constellation (genesis op id). `None` exactly for genesis.
    pub constellation: Option<OpId>,
    /// The signing node.
    pub issuer: NodeId,
    /// The issuer's DAG heads when it wrote the op. Empty exactly for genesis.
    pub parents: BTreeSet<OpId>,
    /// Wall-clock milliseconds, informational only.
    pub ts_ms: u64,
}

/// The kind-specific fields of an op.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum OpPayload {
    /// Creates the constellation. The subject is the issuer.
    Genesis {
        /// The first admin's capabilities; must contain `admin.full`.
        caps: CapSet,
        /// The first admin's display name.
        name: Option<String>,
        /// The constellation's display name.
        label: Option<String>,
        /// The constellation catalog document.
        catalog_root: CatalogRoot,
    },
    /// Admits `subject` with `caps`.
    Add {
        /// The admitted node.
        subject: NodeId,
        /// Its capabilities.
        caps: CapSet,
        /// Its display name.
        name: Option<String>,
    },
    /// Tombstones `subject`.
    Revoke {
        /// The revoked node id.
        subject: NodeId,
        /// Why, for display.
        reason: Option<String>,
    },
    /// Changes an active member's capabilities.
    Grant {
        /// The member.
        subject: NodeId,
        /// Capabilities to add.
        caps_add: CapSet,
        /// Capabilities to remove.
        caps_remove: CapSet,
    },
    /// Moves the issuer to `new_key`. The subject is the issuer.
    Rotate {
        /// The new node id.
        new_key: NodeId,
        /// `new_key`'s signature over [`crypto::rotate_pop_message`].
        new_key_sig: Signature,
    },
    /// Sets the failover policy. The value is kept as received; it is decoded when the op is
    /// authorized ([`crate::policy::FailoverPolicy::from_value`]).
    PolicySet {
        /// The policy map.
        policy: Value,
    },
    /// Replaces the catalog root.
    CatalogRootMove {
        /// The new root document.
        new_root: CatalogRoot,
        /// The root it replaces.
        prev_root: CatalogRoot,
    },
}

impl OpPayload {
    /// The payload's kind.
    #[must_use]
    pub const fn kind(&self) -> OpKind {
        match self {
            Self::Genesis { .. } => OpKind::Genesis,
            Self::Add { .. } => OpKind::Add,
            Self::Revoke { .. } => OpKind::Revoke,
            Self::Grant { .. } => OpKind::Grant,
            Self::Rotate { .. } => OpKind::Rotate,
            Self::PolicySet { .. } => OpKind::PolicySet,
            Self::CatalogRootMove { .. } => OpKind::CatalogRootMove,
        }
    }
}

/// An unsigned membership op.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Op {
    /// The common fields.
    pub header: OpHeader,
    /// The kind-specific fields.
    pub payload: OpPayload,
    /// Extension keys (64 and up), kept as received.
    pub extensions: BTreeMap<u64, Value>,
}

/// Why bytes are not a well-formed op or envelope. [`OpError::code`] gives the fixture code.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
pub enum OpError {
    /// The bytes are not canonical BCBOR.
    #[error("bad encoding: {0}")]
    Encoding(#[from] BcborError),
    /// The body is larger than [`MAX_OP_BYTES`].
    #[error("op body larger than {MAX_OP_BYTES} bytes")]
    TooLarge,
    /// The envelope is not `[body bstr, sig bstr64]`, or the body is not a map.
    #[error("malformed envelope or body shape")]
    Shape,
    /// `v` is not 1.
    #[error("unsupported op version")]
    Version,
    /// `kind` is not a known kind.
    #[error("unknown op kind")]
    UnknownKind,
    /// A field the kind requires is absent.
    #[error("missing field {0}")]
    MissingField(u64),
    /// A reserved field the kind does not allow is present.
    #[error("unexpected field {0}")]
    UnexpectedField(u64),
    /// A field has the wrong type, size or content.
    #[error("bad field {0}")]
    BadField(u64),
    /// Genesis with parents, or another op without them.
    #[error("parents empty for a non-genesis op, or present on genesis")]
    Parents,
    /// A genesis or rotate whose subject is not its issuer.
    #[error("subject must equal issuer")]
    SubjectNotIssuer,
    /// The signature does not verify under the issuer.
    #[error("bad signature")]
    BadSignature,
    /// [`SignedOp::sign`] was given a key that is not the issuer's.
    #[error("signing key is not the issuer")]
    WrongKey,
}

impl OpError {
    /// The stable code the fixtures use.
    #[must_use]
    pub const fn code(&self) -> &'static str {
        match self {
            Self::Encoding(_) => "bad_encoding",
            Self::TooLarge => "too_large",
            Self::Shape => "bad_shape",
            Self::Version => "bad_version",
            Self::UnknownKind => "unknown_kind",
            Self::MissingField(_) => "missing_field",
            Self::UnexpectedField(_) => "unexpected_field",
            Self::BadField(_) => "bad_field",
            Self::Parents => "bad_parents",
            Self::SubjectNotIssuer => "subject_not_issuer",
            Self::BadSignature => "bad_signature",
            Self::WrongKey => "wrong_key",
        }
    }
}

impl Op {
    /// The op's kind.
    #[must_use]
    pub const fn kind(&self) -> OpKind {
        self.payload.kind()
    }

    /// The node the op is about: the subject of genesis, add, revoke, grant and rotate.
    #[must_use]
    pub const fn subject(&self) -> Option<&NodeId> {
        match &self.payload {
            OpPayload::Add { subject, .. }
            | OpPayload::Revoke { subject, .. }
            | OpPayload::Grant { subject, .. } => Some(subject),
            OpPayload::Genesis { .. } | OpPayload::Rotate { .. } => Some(&self.header.issuer),
            OpPayload::PolicySet { .. } | OpPayload::CatalogRootMove { .. } => None,
        }
    }

    /// The op as a BCBOR map.
    #[must_use]
    pub fn to_value(&self) -> Value {
        let mut map: BTreeMap<u64, Value> = self.extensions.clone();
        let header = &self.header;
        map.insert(key::V, Value::Uint(OP_VERSION));
        map.insert(key::KIND, Value::Uint(self.kind().code()));
        if let Some(constellation) = &header.constellation {
            map.insert(key::CONSTELLATION, bytes(constellation.as_bytes()));
        }
        map.insert(key::ISSUER, bytes(header.issuer.as_bytes()));
        map.insert(
            key::PARENTS,
            bcbor::sorted_set(header.parents.iter().map(|p| bytes(p.as_bytes()))),
        );
        map.insert(key::TS_MS, Value::Uint(header.ts_ms));
        if let Some(subject) = self.subject() {
            map.insert(key::SUBJECT, bytes(subject.as_bytes()));
        }
        let mut put_text = |field: u64, text: &Option<String>| {
            if let Some(text) = text {
                map.insert(field, Value::Text(text.clone()));
            }
        };
        match &self.payload {
            OpPayload::Genesis { name, label, .. } => {
                put_text(key::NAME, name);
                put_text(key::LABEL, label);
            }
            OpPayload::Add { name, .. } => put_text(key::NAME, name),
            OpPayload::Revoke { reason, .. } => put_text(key::REASON, reason),
            _ => {}
        }
        match &self.payload {
            OpPayload::Genesis {
                caps, catalog_root, ..
            } => {
                map.insert(key::CAPS_ADD, cap_value(caps));
                map.insert(key::CATALOG_ROOT, bytes(&catalog_root.0));
            }
            OpPayload::Add { caps, .. } => {
                map.insert(key::CAPS_ADD, cap_value(caps));
            }
            OpPayload::Grant {
                caps_add,
                caps_remove,
                ..
            } => {
                map.insert(key::CAPS_ADD, cap_value(caps_add));
                map.insert(key::CAPS_REMOVE, cap_value(caps_remove));
            }
            OpPayload::Rotate {
                new_key,
                new_key_sig,
            } => {
                map.insert(key::NEW_KEY, bytes(new_key.as_bytes()));
                map.insert(key::NEW_KEY_SIG, bytes(new_key_sig.as_bytes()));
            }
            OpPayload::PolicySet { policy } => {
                map.insert(key::POLICY, policy.clone());
            }
            OpPayload::CatalogRootMove {
                new_root,
                prev_root,
            } => {
                map.insert(key::NEW_ROOT, bytes(&new_root.0));
                map.insert(key::PREV_ROOT, bytes(&prev_root.0));
            }
            OpPayload::Revoke { .. } => {}
        }
        Value::Map(map)
    }

    /// The op's canonical body bytes.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        bcbor::encode(&self.to_value())
    }

    /// Decodes a body. The body must be canonical and well-formed for its kind.
    ///
    /// # Errors
    ///
    /// [`OpError`] naming the first problem.
    pub fn decode(body: &[u8]) -> Result<Self, OpError> {
        if body.len() > MAX_OP_BYTES {
            return Err(OpError::TooLarge);
        }
        let Value::Map(map) = bcbor::decode(body)? else {
            return Err(OpError::Shape);
        };
        Self::from_map(map)
    }

    fn from_map(mut map: BTreeMap<u64, Value>) -> Result<Self, OpError> {
        if map.get(&key::V) != Some(&Value::Uint(OP_VERSION)) {
            return Err(if map.contains_key(&key::V) {
                OpError::Version
            } else {
                OpError::MissingField(key::V)
            });
        }
        let kind = match map.get(&key::KIND) {
            Some(Value::Uint(code)) => OpKind::from_code(*code).ok_or(OpError::UnknownKind)?,
            Some(_) => return Err(OpError::BadField(key::KIND)),
            None => return Err(OpError::MissingField(key::KIND)),
        };
        let (required, optional) = kind.fields();
        let common = [key::V, key::KIND, key::ISSUER, key::PARENTS, key::TS_MS];
        for field in common.iter().chain(required) {
            if !map.contains_key(field) {
                return Err(OpError::MissingField(*field));
            }
        }
        if let Some(extra) = map.keys().copied().find(|field| {
            *field < key::FIRST_EXTENSION
                && !common.contains(field)
                && !required.contains(field)
                && !optional.contains(field)
        }) {
            return Err(OpError::UnexpectedField(extra));
        }
        let extensions = map.split_off(&key::FIRST_EXTENSION);
        let mut fields = Fields(map);
        let header = OpHeader {
            constellation: fields.optional(key::CONSTELLATION, op_id)?,
            issuer: fields.required(key::ISSUER, node_id)?,
            parents: fields.op_set(key::PARENTS)?,
            ts_ms: fields.required(key::TS_MS, uint)?,
        };
        if header.parents.is_empty() != (kind == OpKind::Genesis) {
            return Err(OpError::Parents);
        }
        let subject = fields.optional(key::SUBJECT, node_id)?;
        if matches!(kind, OpKind::Genesis | OpKind::Rotate) && subject != Some(header.issuer) {
            return Err(OpError::SubjectNotIssuer);
        }
        let payload = fields.payload(kind, subject)?;
        Ok(Self {
            header,
            payload,
            extensions,
        })
    }
}

struct Fields(BTreeMap<u64, Value>);

impl Fields {
    fn optional<T>(
        &mut self,
        field: u64,
        read: fn(&Value) -> Option<T>,
    ) -> Result<Option<T>, OpError> {
        self.0
            .remove(&field)
            .map(|value| read(&value).ok_or(OpError::BadField(field)))
            .transpose()
    }

    fn required<T>(&mut self, field: u64, read: fn(&Value) -> Option<T>) -> Result<T, OpError> {
        self.optional(field, read)?
            .ok_or(OpError::MissingField(field))
    }

    fn text(&mut self, field: u64, max: usize) -> Result<Option<String>, OpError> {
        match self.0.remove(&field) {
            None => Ok(None),
            Some(Value::Text(text)) if text.len() <= max => Ok(Some(text)),
            Some(_) => Err(OpError::BadField(field)),
        }
    }

    fn set(&mut self, field: u64) -> Result<Vec<Value>, OpError> {
        match self.0.remove(&field) {
            Some(Value::Array(items)) => {
                bcbor::check_sorted_set(&items).map_err(|_| OpError::BadField(field))?;
                Ok(items)
            }
            Some(_) => Err(OpError::BadField(field)),
            None => Err(OpError::MissingField(field)),
        }
    }

    fn op_set(&mut self, field: u64) -> Result<BTreeSet<OpId>, OpError> {
        self.set(field)?
            .iter()
            .map(|item| op_id(item).ok_or(OpError::BadField(field)))
            .collect()
    }

    fn caps(&mut self, field: u64) -> Result<CapSet, OpError> {
        self.set(field)?
            .iter()
            .map(|item| match item {
                Value::Text(text) => Capability::new(text).map_err(|_| OpError::BadField(field)),
                _ => Err(OpError::BadField(field)),
            })
            .collect()
    }

    fn payload(&mut self, kind: OpKind, subject: Option<NodeId>) -> Result<OpPayload, OpError> {
        let subject = || subject.ok_or(OpError::MissingField(key::SUBJECT));
        Ok(match kind {
            OpKind::Genesis => OpPayload::Genesis {
                caps: self.caps(key::CAPS_ADD)?,
                name: self.text(key::NAME, MAX_NAME_BYTES)?,
                label: self.text(key::LABEL, MAX_LABEL_BYTES)?,
                catalog_root: self.required(key::CATALOG_ROOT, catalog_root)?,
            },
            OpKind::Add => OpPayload::Add {
                subject: subject()?,
                caps: self.caps(key::CAPS_ADD)?,
                name: self.text(key::NAME, MAX_NAME_BYTES)?,
            },
            OpKind::Revoke => OpPayload::Revoke {
                subject: subject()?,
                reason: self.text(key::REASON, MAX_REASON_BYTES)?,
            },
            OpKind::Grant => OpPayload::Grant {
                subject: subject()?,
                caps_add: self.caps(key::CAPS_ADD)?,
                caps_remove: self.caps(key::CAPS_REMOVE)?,
            },
            OpKind::Rotate => OpPayload::Rotate {
                new_key: self.required(key::NEW_KEY, node_id)?,
                new_key_sig: self.required(key::NEW_KEY_SIG, signature)?,
            },
            OpKind::PolicySet => OpPayload::PolicySet {
                policy: match self.0.remove(&key::POLICY) {
                    Some(policy @ Value::Map(_)) => policy,
                    _ => return Err(OpError::BadField(key::POLICY)),
                },
            },
            OpKind::CatalogRootMove => OpPayload::CatalogRootMove {
                new_root: self.required(key::NEW_ROOT, catalog_root)?,
                prev_root: self.required(key::PREV_ROOT, catalog_root)?,
            },
        })
    }
}

fn bytes(slice: &[u8]) -> Value {
    Value::Bytes(slice.to_vec())
}

fn cap_value(caps: &CapSet) -> Value {
    bcbor::sorted_set(caps.iter().map(|cap| Value::Text(cap.as_str().to_owned())))
}

fn uint(value: &Value) -> Option<u64> {
    match value {
        Value::Uint(n) => Some(*n),
        _ => None,
    }
}

fn op_id(value: &Value) -> Option<OpId> {
    match value {
        Value::Bytes(raw) => <[u8; 32]>::try_from(raw.as_slice())
            .ok()
            .map(OpId::from_bytes),
        _ => None,
    }
}

fn catalog_root(value: &Value) -> Option<CatalogRoot> {
    match value {
        Value::Bytes(raw) => <[u8; 16]>::try_from(raw.as_slice()).ok().map(CatalogRoot),
        _ => None,
    }
}

fn signature(value: &Value) -> Option<Signature> {
    match value {
        Value::Bytes(raw) => <[u8; 64]>::try_from(raw.as_slice())
            .ok()
            .map(Signature::from_bytes),
        _ => None,
    }
}

/// A well-formed op with a signature that verifies under its issuer.
///
/// The body is kept exactly as received; the op id is taken over those bytes and nothing is ever
/// re-encoded for hashing or verification.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SignedOp {
    id: OpId,
    body: Vec<u8>,
    sig: Signature,
    op: Op,
}

impl SignedOp {
    /// Encodes and signs `op` with `key`, which must be the issuer's.
    ///
    /// # Errors
    ///
    /// [`OpError::WrongKey`] when `key` is not the issuer's, [`OpError::TooLarge`] when the body
    /// exceeds [`MAX_OP_BYTES`], or the decode error when `op` is not well-formed (for example a
    /// genesis with parents).
    pub fn sign(op: &Op, key: &NodeKey) -> Result<Self, OpError> {
        if key.node_id() != op.header.issuer {
            return Err(OpError::WrongKey);
        }
        let body = op.encode();
        let id = OpId::of_body(&body);
        let sig = key.sign(id.as_bytes());
        Self::from_parts(body, sig)
    }

    /// Checks a body and signature: the body must decode and the signature verify strictly under
    /// the issuer over the op id.
    ///
    /// # Errors
    ///
    /// The body's [`OpError`], or [`OpError::BadSignature`].
    pub fn from_parts(body: Vec<u8>, sig: Signature) -> Result<Self, OpError> {
        let op = Op::decode(&body)?;
        let id = OpId::of_body(&body);
        if !crypto::verify_strict(&op.header.issuer, id.as_bytes(), &sig) {
            return Err(OpError::BadSignature);
        }
        Ok(Self { id, body, sig, op })
    }

    /// Decodes and checks an envelope `[body bstr, sig bstr64]`.
    ///
    /// # Errors
    ///
    /// [`OpError`] for a non-canonical or malformed envelope, a bad body or a bad signature.
    pub fn decode_envelope(envelope: &[u8]) -> Result<Self, OpError> {
        // A body at the limit plus its header, the signature and the array head.
        if envelope.len() > MAX_OP_BYTES + 3 + 66 + 1 {
            return Err(OpError::TooLarge);
        }
        let Value::Array(parts) = bcbor::decode(envelope)? else {
            return Err(OpError::Shape);
        };
        let [Value::Bytes(body), Value::Bytes(sig)] =
            <[Value; 2]>::try_from(parts).map_err(|_| OpError::Shape)?
        else {
            return Err(OpError::Shape);
        };
        let sig = <[u8; 64]>::try_from(sig.as_slice()).map_err(|_| OpError::Shape)?;
        Self::from_parts(body, Signature::from_bytes(sig))
    }

    /// The canonical envelope bytes.
    #[must_use]
    pub fn envelope(&self) -> Vec<u8> {
        bcbor::encode(&Value::Array(vec![
            Value::Bytes(self.body.clone()),
            Value::Bytes(self.sig.as_bytes().to_vec()),
        ]))
    }

    /// The op id.
    #[must_use]
    pub const fn id(&self) -> OpId {
        self.id
    }

    /// The body bytes as received.
    #[must_use]
    pub fn body(&self) -> &[u8] {
        &self.body
    }

    /// The issuer's signature over the op id.
    #[must_use]
    pub const fn signature(&self) -> &Signature {
        &self.sig
    }

    /// The decoded op.
    #[must_use]
    pub const fn op(&self) -> &Op {
        &self.op
    }
}

/// The proof of possession a rotate carries: `new_key` signs
/// [`crypto::rotate_pop_message`]`(subject, new_key, constellation)`.
#[must_use]
pub fn rotate_proof(new_key: &NodeKey, subject: &NodeId, constellation: &OpId) -> Signature {
    new_key.sign(&crypto::rotate_pop_message(
        subject,
        &new_key.node_id(),
        constellation,
    ))
}

/// Whether a rotate's proof of possession verifies.
#[must_use]
pub fn rotate_proof_valid(
    subject: &NodeId,
    new_key: &NodeId,
    constellation: &OpId,
    proof: &Signature,
) -> bool {
    crypto::verify_strict(
        new_key,
        &crypto::rotate_pop_message(subject, new_key, constellation),
        proof,
    )
}
