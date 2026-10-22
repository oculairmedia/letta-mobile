//! The browser's persistent Iroh identity (letta-mobile-wisqe S1).
//!
//! A fresh endpoint key per connect gave the browser a new NodeId on every
//! reload, so a host could never keep a pairing grant for it. The page now
//! stores one 32-byte Ed25519 secret (encrypted at rest under a non-extractable
//! WebCrypto key, see `iroh-identity.js`) and hands it to [`crate::IrohAppServerSession::connect`].
//!
//! Iroh signs TLS synchronously with the raw key (`iroh::tls::resolver`), so a
//! non-extractable WebCrypto signing key cannot back it (S0, recorded on the bead).

use iroh::SecretKey;
use wasm_bindgen::{JsError, prelude::wasm_bindgen};

/// Length of an Ed25519 secret key.
pub const SECRET_KEY_LEN: usize = 32;

/// Parses a stored identity. Anything but exactly 32 bytes is refused, never
/// padded or truncated, so a corrupt record cannot silently become another identity.
pub fn parse_secret_key(bytes: &[u8]) -> Result<SecretKey, String> {
    let array: [u8; SECRET_KEY_LEN] = bytes.try_into().map_err(|_| {
        format!(
            "identity key must be {SECRET_KEY_LEN} bytes, got {}",
            bytes.len()
        )
    })?;
    Ok(SecretKey::from_bytes(&array))
}

/// The NodeId (public key, lowercase hex) that `secret` dials as.
pub fn node_id_of(secret: &SecretKey) -> String {
    secret.public().to_string()
}

/// The NodeId a stored identity dials as; lets the page show "this browser" before connecting.
#[wasm_bindgen(js_name = identityNodeId)]
pub fn identity_node_id(secret_key: &[u8]) -> Result<String, JsError> {
    parse_secret_key(secret_key)
        .map(|key| node_id_of(&key))
        .map_err(|message| JsError::new(&message))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn same_bytes_give_the_same_node_id() {
        let bytes = [7u8; SECRET_KEY_LEN];
        let first = node_id_of(&parse_secret_key(&bytes).unwrap());
        let second = node_id_of(&parse_secret_key(&bytes).unwrap());
        assert_eq!(
            first, second,
            "a stored identity must reconnect as the same NodeId"
        );
    }

    #[test]
    fn different_bytes_give_different_node_ids() {
        let a = node_id_of(&parse_secret_key(&[1u8; SECRET_KEY_LEN]).unwrap());
        let b = node_id_of(&parse_secret_key(&[2u8; SECRET_KEY_LEN]).unwrap());
        assert_ne!(a, b);
    }

    #[test]
    fn wrong_length_is_refused_not_padded() {
        assert!(parse_secret_key(&[0u8; 31]).is_err());
        assert!(parse_secret_key(&[0u8; 33]).is_err());
        assert!(parse_secret_key(&[]).is_err());
    }

    #[test]
    fn round_trips_through_bytes() {
        let key = SecretKey::from_bytes(&[9u8; SECRET_KEY_LEN]);
        let back = parse_secret_key(&key.to_bytes()).unwrap();
        assert_eq!(node_id_of(&key), node_id_of(&back));
    }
}
