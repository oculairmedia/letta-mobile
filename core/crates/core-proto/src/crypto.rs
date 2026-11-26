//! Hashing and signatures shared by every signed structure of the core, behind the core's own types.
//!
//! This is the only module that names the BLAKE3 and Ed25519 libraries. The primitives are fixed by
//! the protocol (BLAKE3 derive-key with a dated context per purpose, plain BLAKE3 for payloads,
//! Ed25519 with strict verification over a 32-byte id), so the byte vectors of each area's fixtures
//! are the contract tests: any replacement library must reproduce them exactly.
//!
//! Each crate owns its context strings (`"baken 2026-11-26 <purpose> v1"`) and passes them to
//! [`derive_key`].

use core::fmt;

use ed25519_dalek::{Signer as _, SigningKey, VerifyingKey};

use crate::ids::NodeId;

/// BLAKE3 in derive-key mode: a 32-byte key for `input` under the domain-separating `context`.
#[must_use]
pub fn derive_key(context: &str, input: &[u8]) -> [u8; 32] {
    blake3::derive_key(context, input)
}

/// Plain BLAKE3 of `bytes` (payloads and blobs; iroh-blobs compatible).
#[must_use]
pub fn blake3_hash(bytes: &[u8]) -> [u8; 32] {
    *blake3::hash(bytes).as_bytes()
}

/// An Ed25519 signature, 64 bytes.
#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub struct Signature([u8; 64]);

impl Signature {
    /// Wraps 64 bytes as a signature. Validity is checked only by [`verify_strict`].
    #[must_use]
    pub const fn from_bytes(bytes: [u8; 64]) -> Self {
        Self(bytes)
    }

    /// The signature's bytes.
    #[must_use]
    pub const fn as_bytes(&self) -> &[u8; 64] {
        &self.0
    }
}

impl fmt::Debug for Signature {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "Signature({})", hex::encode(self.0))
    }
}

/// A node's Ed25519 secret key. Signing is deterministic, so the same key and message always give
/// the same signature.
pub struct NodeKey(SigningKey);

impl NodeKey {
    /// The key whose RFC 8032 secret seed is `seed`.
    #[must_use]
    pub fn from_seed(seed: &[u8; 32]) -> Self {
        Self(SigningKey::from_bytes(seed))
    }

    /// The node id (public key) of this key.
    #[must_use]
    pub fn node_id(&self) -> NodeId {
        NodeId::from_bytes(self.0.verifying_key().to_bytes())
    }

    /// Signs a 32-byte id. What the protocol signs is always an id, never a raw body.
    #[must_use]
    pub fn sign(&self, message: &[u8; 32]) -> Signature {
        Signature(self.0.sign(message).to_bytes())
    }
}

impl fmt::Debug for NodeKey {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "NodeKey({})", self.node_id().to_hex())
    }
}

/// Verifies `signature` over the 32-byte `message` under `signer` with the strict rules: rejects a
/// non-canonical `S`, a small-order public key and a small-order `R`.
#[must_use]
pub fn verify_strict(signer: &NodeId, message: &[u8; 32], signature: &Signature) -> bool {
    let Ok(key) = VerifyingKey::from_bytes(signer.as_bytes()) else {
        return false;
    };
    let signature = ed25519_dalek::Signature::from_bytes(&signature.0);
    key.verify_strict(message, &signature).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn signatures_verify_and_bit_flips_fail() {
        let key = NodeKey::from_seed(&[1; 32]);
        let message = [7; 32];
        let signature = key.sign(&message);
        assert!(verify_strict(&key.node_id(), &message, &signature));
        let mut flipped = *signature.as_bytes();
        flipped[10] ^= 1;
        assert!(!verify_strict(
            &key.node_id(),
            &message,
            &Signature::from_bytes(flipped)
        ));
    }

    #[test]
    fn small_order_keys_are_rejected() {
        // The identity point: a small-order key that a non-strict verifier could accept.
        let mut identity = [0_u8; 32];
        identity[0] = 1;
        let signature = Signature::from_bytes([0; 64]);
        assert!(!verify_strict(
            &NodeId::from_bytes(identity),
            &[0; 32],
            &signature
        ));
    }

    #[test]
    fn rfc8032_test_vector_one() {
        // RFC 8032 section 7.1, TEST 1: the public key of the all-zero-message vector.
        let seed: [u8; 32] = [
            0x9d, 0x61, 0xb1, 0x9d, 0xef, 0xfd, 0x5a, 0x60, 0xba, 0x84, 0x4a, 0xf4, 0x92, 0xec,
            0x2c, 0xc4, 0x44, 0x49, 0xc5, 0x69, 0x7b, 0x32, 0x69, 0x19, 0x70, 0x3b, 0xac, 0x03,
            0x1c, 0xae, 0x7f, 0x60,
        ];
        assert_eq!(
            NodeKey::from_seed(&seed).node_id().to_hex(),
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        );
    }

    #[test]
    fn derive_key_separates_contexts_and_plain_hash_is_blake3() {
        assert_ne!(derive_key("a", b"x"), derive_key("b", b"x"));
        // BLAKE3 of the empty input (the reference test vector).
        assert_eq!(
            hex::encode(blake3_hash(b"")),
            "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262"
        );
    }
}
