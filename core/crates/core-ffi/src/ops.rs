//! Membership bootstrap: signing the two ops a first device and a newly admitted device need
//! (genesis and add). The ops, their encoding and their validity are core-membership's; this only
//! fills in the op and signs it with the given key.

use std::collections::{BTreeMap, BTreeSet};

use core_membership::{CatalogRoot, Op, OpHeader, OpId, OpPayload, Preset, SignedOp};
use core_proto::crypto::NodeKey;

use crate::error::CoreError;
use crate::records::{MemberPreset, parse_hex32, parse_node_id};

/// A signed membership op: its id and the envelope a node's `membership_ops` takes.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct SignedMembershipOp {
    /// The op id, hex. A genesis op's id is the constellation id.
    pub op_id: String,
    /// The signed envelope.
    pub envelope: Vec<u8>,
}

/// A constellation's founding op.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct GenesisRequest {
    /// The first device's display name.
    pub node_name: Option<String>,
    /// The constellation's display name.
    pub label: Option<String>,
    /// The constellation catalog document's 16-byte id.
    pub catalog_root: Vec<u8>,
    /// The op's timestamp (informational).
    pub ts_ms: u64,
}

/// An op admitting a node.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct AddMemberRequest {
    /// The constellation id, hex.
    pub constellation: String,
    /// The issuer's current op DAG heads, hex.
    pub parents: Vec<String>,
    /// The node admitted, hex.
    pub subject: String,
    /// Its capabilities.
    pub preset: MemberPreset,
    /// Its display name.
    pub name: Option<String>,
    /// The op's timestamp (informational).
    pub ts_ms: u64,
}

fn key_of(secret: &[u8]) -> Result<NodeKey, CoreError> {
    let seed: [u8; 32] = secret
        .try_into()
        .map_err(|_| CoreError::invalid("a node secret key is 32 bytes"))?;
    Ok(NodeKey::from_seed(&seed))
}

fn signed(op: &Op, key: &NodeKey) -> Result<SignedMembershipOp, CoreError> {
    let signed = SignedOp::sign(op, key).map_err(|error| CoreError::invalid(error.to_string()))?;
    Ok(SignedMembershipOp {
        op_id: hex::encode(signed.id().as_bytes()),
        envelope: signed.envelope(),
    })
}

/// The node id (hex) a 32-byte secret key proves.
///
/// # Errors
///
/// [`CoreError::InvalidArgument`] when the key is not 32 bytes.
#[uniffi::export]
pub fn node_id_of_secret_key(secret_key: Vec<u8>) -> Result<String, CoreError> {
    Ok(key_of(&secret_key)?.node_id().to_hex())
}

/// Founds a constellation: the genesis op, signed by the first device's key, which gets every
/// capability.
///
/// # Errors
///
/// [`CoreError::InvalidArgument`] when the key or the catalog root has the wrong length.
#[uniffi::export]
pub fn sign_genesis_op(
    secret_key: Vec<u8>,
    request: GenesisRequest,
) -> Result<SignedMembershipOp, CoreError> {
    let key = key_of(&secret_key)?;
    let root: [u8; 16] = request
        .catalog_root
        .try_into()
        .map_err(|_| CoreError::invalid("a catalog root is 16 bytes"))?;
    let op = Op {
        header: OpHeader {
            constellation: None,
            issuer: key.node_id(),
            parents: BTreeSet::new(),
            ts_ms: request.ts_ms,
        },
        payload: OpPayload::Genesis {
            caps: Preset::FirstDevice.capabilities(),
            name: request.node_name,
            label: request.label,
            catalog_root: CatalogRoot(root),
        },
        extensions: BTreeMap::new(),
    };
    signed(&op, &key)
}

/// Admits a node: an add op issued by the admin whose key is `issuer_secret_key`.
///
/// # Errors
///
/// [`CoreError::InvalidArgument`] when a key, id or parent does not parse.
#[uniffi::export]
pub fn sign_add_member_op(
    issuer_secret_key: Vec<u8>,
    request: AddMemberRequest,
) -> Result<SignedMembershipOp, CoreError> {
    let key = key_of(&issuer_secret_key)?;
    let constellation = OpId::from_bytes(parse_hex32("constellation", &request.constellation)?);
    let parents = request
        .parents
        .iter()
        .map(|parent| parse_hex32("parent", parent).map(OpId::from_bytes))
        .collect::<Result<BTreeSet<_>, _>>()?;
    let op = Op {
        header: OpHeader {
            constellation: Some(constellation),
            issuer: key.node_id(),
            parents,
            ts_ms: request.ts_ms,
        },
        payload: OpPayload::Add {
            subject: parse_node_id(&request.subject)?,
            caps: Preset::from(request.preset).capabilities(),
            name: request.name,
        },
        extensions: BTreeMap::new(),
    };
    signed(&op, &key)
}
