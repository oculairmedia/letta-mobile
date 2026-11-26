//! The fixed 168-byte entry header and the 232-byte signed entry.

use core_proto::crypto::{NodeKey, Signature, verify_strict};
use core_proto::{Epoch, NodeId};
use core_store::{BlobHash, EntryHash, LogId, PayloadState, StoredEntry};
use thiserror::Error;

use crate::hashes::entry_hash;
use crate::{EPOCH_N_LIMIT, MAX_PAYLOAD};

pub use core_store::log::{HEADER_LEN, SIGNATURE_LEN};

/// The header magic, `"BKL1"`.
pub const MAGIC: [u8; 4] = *b"BKL1";
/// Length of a signed entry: header plus signature.
pub const SIGNED_LEN: usize = HEADER_LEN + SIGNATURE_LEN;
/// Header flag bit 0: the payload may be pruned once superseded.
pub const FLAG_COMPACTABLE: u16 = core_store::log::FLAG_COMPACTABLE;

/// Why bytes are not a valid header or signed entry.
#[derive(Clone, Copy, Debug, Error, PartialEq, Eq)]
pub enum HeaderError {
    /// Not 168 (header) or 232 (signed entry) bytes.
    #[error("wrong length")]
    WrongLength,
    /// The magic is not `BKL1`.
    #[error("bad magic")]
    BadMagic,
    /// A reserved flag bit (1-15) is set.
    #[error("reserved flag bit set")]
    ReservedFlags,
    /// `epoch.n` is 0 or above `EPOCH_N_LIMIT`.
    #[error("epoch number out of range")]
    EpochOutOfRange,
    /// `payload_len` is above `MAX_PAYLOAD`.
    #[error("payload too large")]
    PayloadTooLarge,
    /// `seq == 0` and a zero `prev` do not go together.
    #[error("seq 0 exactly when prev is zero")]
    GenesisPrev,
    /// The signature does not verify under the epoch's claimant.
    #[error("bad signature")]
    BadSignature,
    /// The signing key is not the epoch's claimant.
    #[error("signer is not the epoch claimant")]
    WrongSigner,
}

impl HeaderError {
    /// The stable code fixtures and wire errors use.
    #[must_use]
    pub const fn code(self) -> &'static str {
        match self {
            Self::WrongLength => "wrong_length",
            Self::BadMagic => "bad_magic",
            Self::ReservedFlags => "reserved_flags",
            Self::EpochOutOfRange => "epoch_out_of_range",
            Self::PayloadTooLarge => "payload_too_large",
            Self::GenesisPrev => "genesis_prev",
            Self::BadSignature => "bad_signature",
            Self::WrongSigner => "wrong_signer",
        }
    }
}

/// A decoded entry header (C1 §3.2).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct EntryHeader {
    /// `derive_key("agent log id", agent_id)`.
    pub log_id: LogId,
    /// The segment's epoch; its claimant signs.
    pub epoch: Epoch,
    /// Agent-global height.
    pub seq: u64,
    /// Hash of the entry at `seq - 1` on this line; zero at seq 0.
    pub prev: EntryHash,
    /// Payload kind.
    pub kind: u16,
    /// Flags; only bit 0 (`compactable`) may be set.
    pub flags: u16,
    /// Writer's wall clock, informational.
    pub ts_ms: u64,
    /// Payload length.
    pub payload_len: u64,
    /// `blake3(payload)`.
    pub payload_hash: BlobHash,
}

impl EntryHeader {
    /// The 168-byte big-endian encoding.
    #[must_use]
    pub fn encode(&self) -> [u8; HEADER_LEN] {
        let mut out = [0_u8; HEADER_LEN];
        out[0..4].copy_from_slice(&MAGIC);
        out[4..36].copy_from_slice(self.log_id.as_bytes());
        out[36..44].copy_from_slice(&self.epoch.n.to_be_bytes());
        out[44..76].copy_from_slice(self.epoch.claimant.as_bytes());
        out[76..84].copy_from_slice(&self.seq.to_be_bytes());
        out[84..116].copy_from_slice(self.prev.as_bytes());
        out[116..118].copy_from_slice(&self.kind.to_be_bytes());
        out[118..120].copy_from_slice(&self.flags.to_be_bytes());
        out[120..128].copy_from_slice(&self.ts_ms.to_be_bytes());
        out[128..136].copy_from_slice(&self.payload_len.to_be_bytes());
        out[136..168].copy_from_slice(self.payload_hash.as_bytes());
        out
    }

    /// Decodes and checks a header.
    ///
    /// # Errors
    ///
    /// The first [`HeaderError`] the bytes break.
    pub fn decode(bytes: &[u8]) -> Result<Self, HeaderError> {
        if bytes.len() != HEADER_LEN {
            return Err(HeaderError::WrongLength);
        }
        if bytes[0..4] != MAGIC {
            return Err(HeaderError::BadMagic);
        }
        let header = Self {
            log_id: LogId::from_slice(&bytes[4..36]).ok_or(HeaderError::WrongLength)?,
            epoch: Epoch {
                n: be_u64(&bytes[36..44]),
                claimant: NodeId::from_bytes(array32(&bytes[44..76])),
            },
            seq: be_u64(&bytes[76..84]),
            prev: EntryHash::from_bytes(array32(&bytes[84..116])),
            kind: u16::from_be_bytes([bytes[116], bytes[117]]),
            flags: u16::from_be_bytes([bytes[118], bytes[119]]),
            ts_ms: be_u64(&bytes[120..128]),
            payload_len: be_u64(&bytes[128..136]),
            payload_hash: BlobHash::from_bytes(array32(&bytes[136..168])),
        };
        header.check()?;
        Ok(header)
    }

    /// Checks the header-level rules (C1 §3.2 rules 1 and 3, header part).
    ///
    /// # Errors
    ///
    /// The first [`HeaderError`] the header breaks.
    pub fn check(&self) -> Result<(), HeaderError> {
        if self.flags & !FLAG_COMPACTABLE != 0 {
            return Err(HeaderError::ReservedFlags);
        }
        if self.epoch.n == 0 || self.epoch.n > EPOCH_N_LIMIT {
            return Err(HeaderError::EpochOutOfRange);
        }
        if self.payload_len > MAX_PAYLOAD {
            return Err(HeaderError::PayloadTooLarge);
        }
        if (self.seq == 0) != (self.prev == EntryHash::ZERO) {
            return Err(HeaderError::GenesisPrev);
        }
        Ok(())
    }

    /// Whether bit 0 (`compactable`) is set.
    #[must_use]
    pub const fn is_compactable(&self) -> bool {
        self.flags & FLAG_COMPACTABLE != 0
    }
}

/// A header with its signature, verified.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SignedEntry {
    header: EntryHeader,
    raw: [u8; HEADER_LEN],
    signature: Signature,
    hash: EntryHash,
}

impl SignedEntry {
    /// Signs `header` with `key`, which must be the epoch's claimant.
    ///
    /// # Errors
    ///
    /// [`HeaderError::WrongSigner`], or a header rule.
    pub fn sign(header: EntryHeader, key: &NodeKey) -> Result<Self, HeaderError> {
        header.check()?;
        if key.node_id() != header.epoch.claimant {
            return Err(HeaderError::WrongSigner);
        }
        let raw = header.encode();
        let hash = entry_hash(&raw);
        let signature = key.sign(hash.as_bytes());
        Ok(Self {
            header,
            raw,
            signature,
            hash,
        })
    }

    /// Decodes and verifies a 232-byte signed entry.
    ///
    /// # Errors
    ///
    /// A header rule, or [`HeaderError::BadSignature`].
    pub fn from_bytes(bytes: &[u8]) -> Result<Self, HeaderError> {
        if bytes.len() != SIGNED_LEN {
            return Err(HeaderError::WrongLength);
        }
        let mut signature = [0_u8; SIGNATURE_LEN];
        signature.copy_from_slice(&bytes[HEADER_LEN..]);
        Self::from_parts(&bytes[..HEADER_LEN], &signature)
    }

    /// Decodes and verifies a header and a signature.
    ///
    /// # Errors
    ///
    /// A header rule, or [`HeaderError::BadSignature`].
    pub fn from_parts(header: &[u8], signature: &[u8; SIGNATURE_LEN]) -> Result<Self, HeaderError> {
        let decoded = EntryHeader::decode(header)?;
        let mut raw = [0_u8; HEADER_LEN];
        raw.copy_from_slice(header);
        let hash = entry_hash(&raw);
        let signature = Signature::from_bytes(*signature);
        if !verify_strict(&decoded.epoch.claimant, hash.as_bytes(), &signature) {
            return Err(HeaderError::BadSignature);
        }
        Ok(Self {
            header: decoded,
            raw,
            signature,
            hash,
        })
    }

    /// Re-verifies a stored entry.
    ///
    /// # Errors
    ///
    /// A header rule, or [`HeaderError::BadSignature`].
    pub fn from_stored(entry: &StoredEntry) -> Result<Self, HeaderError> {
        Self::from_parts(&entry.header, &entry.signature)
    }

    /// The decoded header.
    #[must_use]
    pub const fn header(&self) -> &EntryHeader {
        &self.header
    }

    /// The header bytes, verbatim.
    #[must_use]
    pub const fn header_bytes(&self) -> &[u8; HEADER_LEN] {
        &self.raw
    }

    /// The signature.
    #[must_use]
    pub const fn signature(&self) -> &Signature {
        &self.signature
    }

    /// `entry_hash`.
    #[must_use]
    pub const fn hash(&self) -> EntryHash {
        self.hash
    }

    /// The 232 bytes `header ‖ signature`.
    #[must_use]
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(SIGNED_LEN);
        out.extend_from_slice(&self.raw);
        out.extend_from_slice(self.signature.as_bytes());
        out
    }

    /// The store record for this entry with the given payload state.
    #[must_use]
    pub fn to_stored(&self, payload_state: PayloadState) -> StoredEntry {
        StoredEntry {
            entry_hash: self.hash,
            log_id: self.header.log_id,
            epoch: self.header.epoch,
            seq: self.header.seq,
            prev: self.header.prev,
            kind: self.header.kind,
            flags: self.header.flags,
            header: self.raw.to_vec(),
            signature: *self.signature.as_bytes(),
            payload_hash: self.header.payload_hash,
            payload_len: self.header.payload_len,
            payload_state,
        }
    }
}

fn be_u64(bytes: &[u8]) -> u64 {
    let mut array = [0_u8; 8];
    array.copy_from_slice(bytes);
    u64::from_be_bytes(array)
}

fn array32(bytes: &[u8]) -> [u8; 32] {
    let mut array = [0_u8; 32];
    array.copy_from_slice(bytes);
    array
}
