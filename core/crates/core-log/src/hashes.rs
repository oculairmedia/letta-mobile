//! The log's hash functions: BLAKE3 derive-key contexts (through `core_proto::crypto`), plain
//! BLAKE3 for payloads, SHA-256 for the checkpoint manifest and git object ids.
//!
//! This module and `core_proto::crypto` are the only places that name a hash library.

use core_proto::crypto::{blake3_hash, derive_key};
use core_proto::{AgentId, Epoch};
use core_store::{BlobHash, DivergenceId, EntryHash, LogId};
use sha1::Digest as _;

/// Context of `entry_hash`: the 168-byte header.
pub const CTX_LOG_ENTRY: &str = "baken 2026-11-26 log entry v1";
/// Context of `log_id`: the UTF-8 agent id.
pub const CTX_AGENT_LOG_ID: &str = "baken 2026-11-26 agent log id v1";
/// Context of the `baken/log/1` hello digest.
pub const CTX_LOG_DIGEST: &str = "baken 2026-11-26 log digest v1";
/// Context of `divergence_id`.
pub const CTX_DIVERGENCE_ID: &str = "baken 2026-11-26 divergence id v1";
/// The in-band prefix of C2's divergence catalog node id (plain BLAKE3, not derive-key).
pub const C2_DIVERGENCE_PREFIX: &[u8] = b"baken.divergence.v1";

/// The log id of an agent: `derive_key("agent log id", agent_id)`.
#[must_use]
pub fn log_id(agent: &AgentId) -> LogId {
    LogId::from_bytes(derive_key(CTX_AGENT_LOG_ID, agent.as_str().as_bytes()))
}

/// The hash of a header: `derive_key("log entry", header)`.
#[must_use]
pub fn entry_hash(header: &[u8]) -> EntryHash {
    EntryHash::from_bytes(derive_key(CTX_LOG_ENTRY, header))
}

/// The hash of a payload: plain `blake3(payload)`.
#[must_use]
pub fn payload_hash(payload: &[u8]) -> BlobHash {
    BlobHash::from_bytes(blake3_hash(payload))
}

/// An epoch as 40 bytes: `n` big-endian, then the claimant.
#[must_use]
pub fn epoch_bytes(epoch: &Epoch) -> [u8; 40] {
    let mut out = [0_u8; 40];
    out[..8].copy_from_slice(&epoch.n.to_be_bytes());
    out[8..].copy_from_slice(epoch.claimant.as_bytes());
    out
}

/// `derive_key("divergence id", log_id ‖ losing_epoch ‖ fork_seq BE)`.
#[must_use]
pub fn divergence_id(log_id: &LogId, losing_epoch: &Epoch, fork_seq: u64) -> DivergenceId {
    let mut input = Vec::with_capacity(80);
    input.extend_from_slice(log_id.as_bytes());
    input.extend_from_slice(&epoch_bytes(losing_epoch));
    input.extend_from_slice(&fork_seq.to_be_bytes());
    DivergenceId::from_bytes(derive_key(CTX_DIVERGENCE_ID, &input))
}

/// `derive_key("log digest", input)`; the caller assembles the input (see `wire`).
#[must_use]
pub fn log_digest(input: &[u8]) -> [u8; 32] {
    derive_key(CTX_LOG_DIGEST, input)
}

/// C2's divergence catalog node id: the first 16 bytes of
/// `blake3("baken.divergence.v1" ‖ agentId ‖ 0x00 ‖ conversationId ‖ 0x00 ‖ u64be(n) ‖ claimant)`,
/// with the UUID version set to 8 and the RFC 9562 variant bits.
#[must_use]
pub fn c2_divergence_node_id(agent: &str, conversation: &str, stranded: &Epoch) -> [u8; 16] {
    let mut input = Vec::new();
    input.extend_from_slice(C2_DIVERGENCE_PREFIX);
    input.extend_from_slice(agent.as_bytes());
    input.push(0);
    input.extend_from_slice(conversation.as_bytes());
    input.push(0);
    input.extend_from_slice(&epoch_bytes(stranded));
    let digest = blake3_hash(&input);
    let mut id = [0_u8; 16];
    id.copy_from_slice(&digest[..16]);
    id[6] = (id[6] & 0x0f) | 0x80;
    id[8] = (id[8] & 0x3f) | 0x80;
    id
}

/// The UUID text form of 16 bytes (lowercase, hyphenated).
#[must_use]
pub fn uuid_text(id: &[u8; 16]) -> String {
    let hex = hex(id);
    format!(
        "{}-{}-{}-{}-{}",
        &hex[..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..]
    )
}

/// SHA-256 of `bytes` (checkpoint manifest entries and `file_put`).
#[must_use]
pub fn sha256(bytes: &[u8]) -> [u8; 32] {
    sha2::Sha256::digest(bytes).into()
}

/// The hash algorithm of a memfs git repository.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum GitAlgo {
    /// SHA-1 object ids (20 bytes).
    Sha1,
    /// SHA-256 object ids (32 bytes).
    Sha256,
}

impl GitAlgo {
    /// The name the payload carries.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::Sha1 => "sha1",
            Self::Sha256 => "sha256",
        }
    }

    /// Parses the payload name.
    #[must_use]
    pub fn from_name(name: &str) -> Option<Self> {
        match name {
            "sha1" => Some(Self::Sha1),
            "sha256" => Some(Self::Sha256),
            _ => None,
        }
    }
}

/// Git's object id of a raw object: the hash of `"<type> <len>\0" ‖ content`.
#[must_use]
pub fn git_oid(algo: GitAlgo, type_name: &str, content: &[u8]) -> Vec<u8> {
    let mut framed = format!("{type_name} {}\0", content.len()).into_bytes();
    framed.extend_from_slice(content);
    match algo {
        GitAlgo::Sha1 => sha1::Sha1::digest(&framed).to_vec(),
        GitAlgo::Sha256 => sha2::Sha256::digest(&framed).to_vec(),
    }
}

pub(crate) fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut text = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        text.push(char::from(DIGITS[usize::from(byte >> 4)]));
        text.push(char::from(DIGITS[usize::from(byte & 0x0f)]));
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn git_blob_oid_matches_git() {
        // `printf 'hello\n' | git hash-object --stdin`
        assert_eq!(
            hex(&git_oid(GitAlgo::Sha1, "blob", b"hello\n")),
            "ce013625030ba8dba906f756967f9e9ca394464a"
        );
    }

    #[test]
    fn uuid_bits_are_set() {
        let id = c2_divergence_node_id(
            "agent-1",
            "conv-1",
            &Epoch {
                n: 2,
                claimant: core_proto::NodeId::from_bytes([1; 32]),
            },
        );
        assert_eq!(id[6] >> 4, 8);
        assert_eq!(id[8] >> 6, 2);
        assert_eq!(uuid_text(&id).len(), 36);
    }
}
