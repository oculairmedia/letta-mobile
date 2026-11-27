//! Membership's hashing: the op id and the rotate proof of possession.
//!
//! The generic primitives (BLAKE3 derive-key, strict Ed25519, [`NodeKey`], [`Signature`]) live in
//! [`core_proto::crypto`] and are re-exported here. This module owns membership's two derive-key
//! contexts. The byte vectors in `membership-ops-v1.json` are the contract tests.

use core::fmt;

use core_proto::NodeId;
use core_proto::crypto::derive_key;

pub use core_proto::crypto::{NodeKey, Signature, verify_strict};

/// The BLAKE3 derive-key context for an op id.
pub const OP_ID_CONTEXT: &str = "baken 2026-11-26 membership op v1";

/// The BLAKE3 derive-key context for a rotate proof-of-possession message.
pub const ROTATE_POP_CONTEXT: &str = "baken 2026-11-26 rotate pop v1";

/// A membership op's id: BLAKE3 derive-key over the op's body with [`OP_ID_CONTEXT`]. The genesis
/// op's id is the constellation id.
#[derive(Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct OpId([u8; 32]);

impl OpId {
    /// Wraps 32 bytes as an op id.
    #[must_use]
    pub const fn from_bytes(bytes: [u8; 32]) -> Self {
        Self(bytes)
    }

    /// The id's bytes.
    #[must_use]
    pub const fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }

    /// The id of an op with this canonical body.
    #[must_use]
    pub fn of_body(body: &[u8]) -> Self {
        Self(derive_key(OP_ID_CONTEXT, body))
    }
}

impl fmt::Debug for OpId {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "OpId({})", hex::encode(self.0))
    }
}

impl fmt::Display for OpId {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&hex::encode(self.0))
    }
}

/// The message a rotate's new key signs to prove possession:
/// derive-key([`ROTATE_POP_CONTEXT`], `subject ‖ new_key ‖ constellation`).
#[must_use]
pub fn rotate_pop_message(subject: &NodeId, new_key: &NodeId, constellation: &OpId) -> [u8; 32] {
    let mut input = [0_u8; 96];
    input[..32].copy_from_slice(subject.as_bytes());
    input[32..64].copy_from_slice(new_key.as_bytes());
    input[64..].copy_from_slice(constellation.as_bytes());
    derive_key(ROTATE_POP_CONTEXT, &input)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn op_id_is_derive_key_under_the_op_context() {
        let body = b"body";
        assert_eq!(
            OpId::of_body(body).as_bytes(),
            &derive_key(OP_ID_CONTEXT, body)
        );
        assert_ne!(
            rotate_pop_message(
                &NodeId::from_bytes([1; 32]),
                &NodeId::from_bytes([2; 32]),
                &OpId::from_bytes([3; 32])
            ),
            derive_key(OP_ID_CONTEXT, &[0; 96])
        );
    }
}
