//! BCBOR invariants over generated values: every value round-trips, and one value has one encoding
//! (a longer head for any int is refused).

use std::collections::BTreeMap;

use core_proto::bcbor::{self, BcborError, Value};
use proptest::prelude::*;

fn value() -> impl Strategy<Value = Value> {
    let leaf = prop_oneof![
        any::<u64>().prop_map(Value::Uint),
        proptest::collection::vec(any::<u8>(), 0..40).prop_map(Value::Bytes),
        ".{0,20}".prop_map(Value::Text),
        any::<bool>().prop_map(Value::Bool),
        Just(Value::Null),
    ];
    leaf.prop_recursive(4, 64, 8, |inner| {
        prop_oneof![
            proptest::collection::vec(inner.clone(), 0..8).prop_map(Value::Array),
            proptest::collection::btree_map(any::<u64>(), inner, 0..8)
                .prop_map(|map: BTreeMap<u64, Value>| Value::Map(map)),
        ]
    })
}

proptest! {
    #[test]
    fn every_value_round_trips(value in value()) {
        let bytes = bcbor::encode(&value);
        prop_assert_eq!(bcbor::decode(&bytes), Ok(value));
    }

    #[test]
    fn a_longer_head_for_an_int_is_refused(n in any::<u32>()) {
        // The u64-width head (0x1b) is never the shortest for a value that fits in 32 bits.
        let mut bytes = vec![0x1b];
        bytes.extend_from_slice(&u64::from(n).to_be_bytes());
        prop_assert_eq!(bcbor::decode(&bytes), Err(BcborError::NonShortest));
    }
}
