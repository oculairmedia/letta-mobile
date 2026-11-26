//! The placement epoch: which claim to hold an agent is current.
//!
//! An epoch is the pair `(n, claimant)`. Epochs compare by `n` first and then by the claimant's node
//! id, so the order is total: two nodes that claim the same `n` at the same moment still get two
//! different epochs, and every replica picks the same winner without talking to anyone. The losing
//! claim's entries are kept as a divergent branch, never silently dropped.

use std::cmp::Ordering;
use std::fmt;

use serde::{Deserialize, Serialize};

use crate::ids::NodeId;

/// One claim to hold an agent: claim number `n`, made by `claimant`.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct Epoch {
    /// The claim number. A new claim takes one more than the highest epoch its claimant has accepted.
    pub n: u64,
    /// The node that made the claim.
    pub claimant: NodeId,
}

impl Epoch {
    /// The first epoch, claimed by `claimant`.
    #[must_use]
    pub const fn genesis(claimant: NodeId) -> Self {
        Self { n: 0, claimant }
    }

    /// The epoch `claimant` claims on top of this one: `n + 1`.
    ///
    /// # Panics
    ///
    /// When `n` is `u64::MAX`; no real history reaches it.
    #[must_use]
    pub fn next(&self, claimant: NodeId) -> Self {
        Self {
            n: self.n.checked_add(1).expect("epoch number overflow"),
            claimant,
        }
    }
}

impl Ord for Epoch {
    fn cmp(&self, other: &Self) -> Ordering {
        self.n
            .cmp(&other.n)
            .then_with(|| self.claimant.cmp(&other.claimant))
    }
}

impl PartialOrd for Epoch {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl fmt::Debug for Epoch {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "Epoch({}, {})", self.n, self.claimant)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn node(byte: u8) -> NodeId {
        NodeId::from_bytes([byte; 32])
    }

    #[test]
    fn a_higher_number_wins_whatever_the_claimant() {
        assert!(
            Epoch {
                n: 2,
                claimant: node(0)
            } > Epoch {
                n: 1,
                claimant: node(0xff)
            }
        );
    }

    #[test]
    fn simultaneous_claims_resolve_by_claimant() {
        let low = Epoch {
            n: 3,
            claimant: node(1),
        };
        let high = Epoch {
            n: 3,
            claimant: node(2),
        };
        assert!(low < high);
        assert_eq!(low.max(high), high);
    }

    #[test]
    fn next_raises_the_number_and_names_the_new_claimant() {
        let first = Epoch::genesis(node(9));
        let second = first.next(node(1));
        assert_eq!(
            second,
            Epoch {
                n: 1,
                claimant: node(1)
            }
        );
        assert!(second > first);
    }

    #[test]
    fn serializes_as_number_and_hex_claimant() {
        let epoch = Epoch {
            n: 7,
            claimant: node(0xab),
        };
        let json = serde_json::to_string(&epoch).expect("serializes");
        assert_eq!(
            json,
            format!("{{\"n\":7,\"claimant\":\"{}\"}}", "ab".repeat(32))
        );
        assert_eq!(serde_json::from_str::<Epoch>(&json).expect("parses"), epoch);
    }
}
