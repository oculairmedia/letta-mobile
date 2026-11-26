//! Typed ids. A function that takes an agent never takes a bare string, so ids of different kinds
//! cannot be swapped by accident.

use std::fmt;
use std::str::FromStr;

use serde::{Deserialize, Deserializer, Serialize, Serializer};
use thiserror::Error;

/// A node's identity: its Ed25519 public key (the Iroh endpoint id), 32 bytes.
///
/// Its text form is 64 lowercase hex characters, the form the node protocol, pairing records and
/// telemetry use. Parsing refuses upper case so one key has exactly one spelling. The order is the
/// order of the key bytes, which is also the order of the hex text.
#[derive(Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct NodeId([u8; 32]);

impl NodeId {
    /// Bytes in a node id.
    pub const BYTES: usize = 32;

    /// The node id of a 32-byte public key.
    #[must_use]
    pub const fn from_bytes(bytes: [u8; 32]) -> Self {
        Self(bytes)
    }

    /// The public key bytes.
    #[must_use]
    pub const fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }

    /// The canonical text form: 64 lowercase hex characters.
    #[must_use]
    pub fn to_hex(&self) -> String {
        hex::encode(self.0)
    }
}

/// Text that is not a canonical [`NodeId`].
#[derive(Clone, Debug, Error, PartialEq, Eq)]
#[error("a node id is 64 lowercase hex characters")]
pub struct NodeIdError;

impl FromStr for NodeId {
    type Err = NodeIdError;

    fn from_str(text: &str) -> Result<Self, Self::Err> {
        // `hex` accepts either case; the canonical spelling is lowercase only.
        if text.bytes().any(|digit| digit.is_ascii_uppercase()) {
            return Err(NodeIdError);
        }
        let mut bytes = [0_u8; 32];
        hex::decode_to_slice(text, &mut bytes).map_err(|_| NodeIdError)?;
        Ok(Self(bytes))
    }
}

impl fmt::Display for NodeId {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.to_hex())
    }
}

impl fmt::Debug for NodeId {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "NodeId({})", self.to_hex())
    }
}

impl Serialize for NodeId {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&self.to_hex())
    }
}

impl<'de> Deserialize<'de> for NodeId {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let text = String::deserialize(deserializer)?;
        text.parse().map_err(serde::de::Error::custom)
    }
}

/// Declares an opaque string id: the protocol never looks inside it, it only compares and echoes it.
macro_rules! opaque_id {
    ($(#[$doc:meta])* $name:ident) => {
        $(#[$doc])*
        #[derive(Clone, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
        #[serde(transparent)]
        pub struct $name(String);

        impl $name {
            /// Wraps `value` as this kind of id.
            #[must_use]
            pub fn new(value: impl Into<String>) -> Self {
                Self(value.into())
            }

            /// The id's text.
            #[must_use]
            pub fn as_str(&self) -> &str {
                &self.0
            }

            /// The id's text, owned.
            #[must_use]
            pub fn into_string(self) -> String {
                self.0
            }
        }

        impl fmt::Display for $name {
            fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
                formatter.write_str(&self.0)
            }
        }

        impl fmt::Debug for $name {
            fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
                write!(formatter, "{}({:?})", stringify!($name), self.0)
            }
        }

        impl From<&str> for $name {
            fn from(value: &str) -> Self {
                Self::new(value)
            }
        }
    };
}

opaque_id!(
    /// An agent, as the runtime names it (for example `agent-3f2a...`).
    AgentId
);

opaque_id!(
    /// A conversation of an agent. `default` is a valid id: the agent's default conversation.
    ConversationId
);

opaque_id!(
    /// The id a peer puts on a request so it can match the reply. The node echoes it unchanged and
    /// sends an empty one when the request carried none, so an empty id is valid on the wire.
    RequestId
);

opaque_id!(
    /// The id a client gives the user message that starts a turn; it names the turn on the node.
    ClientMessageId
);

#[cfg(test)]
mod tests {
    use super::*;

    const HEX: &str = "00ff10a0b1c2d3e4f5061728394a5b6c7d8e9fa0b1c2d3e4f5061728394a5b6c";

    #[test]
    fn node_id_round_trips_its_canonical_text() {
        let id: NodeId = HEX.parse().expect("canonical hex");
        assert_eq!(id.to_string(), HEX);
        assert_eq!(id.as_bytes()[1], 0xff);
        let json = serde_json::to_string(&id).expect("serializes");
        assert_eq!(json, format!("\"{HEX}\""));
        assert_eq!(serde_json::from_str::<NodeId>(&json).expect("parses"), id);
    }

    #[test]
    fn node_id_refuses_other_spellings() {
        assert!(HEX.to_uppercase().parse::<NodeId>().is_err());
        assert!(HEX[..62].parse::<NodeId>().is_err());
        assert!(format!("{HEX}00").parse::<NodeId>().is_err());
        assert!(HEX.replace('a', "g").parse::<NodeId>().is_err());
    }

    #[test]
    fn opaque_ids_serialize_as_bare_strings() {
        let id = RequestId::new("node-1");
        assert_eq!(
            serde_json::to_string(&id).expect("serializes"),
            "\"node-1\""
        );
        assert_eq!(
            RequestId::new(""),
            serde_json::from_str("\"\"").expect("empty is valid")
        );
    }
}
