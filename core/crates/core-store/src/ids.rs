//! Fixed-size ids the store keys rows by.
//!
//! The store never computes these. The domain crates derive them (BLAKE3 for the 32-byte hashes,
//! random bytes for catalog document ids) and the store keeps them as opaque bytes.

use std::fmt;

macro_rules! byte_id {
    ($(#[$meta:meta])* $name:ident, $len:literal) => {
        $(#[$meta])*
        #[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
        pub struct $name([u8; $len]);

        impl $name {
            /// The length of the id in bytes.
            pub const LEN: usize = $len;

            /// Wraps the raw bytes.
            #[must_use]
            pub const fn from_bytes(bytes: [u8; $len]) -> Self {
                Self(bytes)
            }

            /// The raw bytes.
            #[must_use]
            pub const fn as_bytes(&self) -> &[u8; $len] {
                &self.0
            }

            /// Copies the id out of a slice, or `None` when the slice has the wrong length.
            #[must_use]
            pub fn from_slice(bytes: &[u8]) -> Option<Self> {
                <[u8; $len]>::try_from(bytes).ok().map(Self)
            }
        }

        impl fmt::Debug for $name {
            fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
                write!(formatter, "{}(", stringify!($name))?;
                for byte in &self.0 {
                    write!(formatter, "{byte:02x}")?;
                }
                write!(formatter, ")")
            }
        }
    };
}

byte_id!(
    /// An agent log: `derive_key("agent log id", agent_id)`.
    LogId,
    32
);
byte_id!(
    /// A log entry: `derive_key("log entry", header)`. Also used for the zero `prev` of genesis.
    EntryHash,
    32
);
byte_id!(
    /// A payload or blob: plain `blake3(bytes)`.
    BlobHash,
    32
);
byte_id!(
    /// A membership op: `derive_key("membership op", body)`.
    OpId,
    32
);
byte_id!(
    /// A recorded divergence: `derive_key("divergence id", ...)`.
    DivergenceId,
    32
);
byte_id!(
    /// A catalog document id: the 16 bytes behind `automerge:<bs58check>`.
    DocId,
    16
);

impl EntryHash {
    /// The all-zero hash a genesis entry names as its `prev`.
    pub const ZERO: Self = Self([0; 32]);
}
