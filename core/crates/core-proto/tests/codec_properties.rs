//! Property tests: the codec round-trips any payload under any limits and any chunking, never
//! panics on arbitrary input, and the epoch order is total.

use core_proto::codec::{FrameDecoder, Limits, encode_frame, encode_frame_parts};
use core_proto::{Epoch, NodeId};
use proptest::prelude::*;

/// Small limits so chunking is exercised on short payloads.
fn limits() -> impl Strategy<Value = Limits> {
    (1_usize..64, 1_usize..512)
        .prop_map(|(frame, extra)| Limits::new(frame, frame + extra).expect("valid limits"))
}

/// Splits `bytes` at the given cut points (taken modulo the length).
fn split(bytes: &[u8], cuts: &[usize]) -> Vec<Vec<u8>> {
    if bytes.is_empty() {
        return vec![Vec::new()];
    }
    let mut points: Vec<usize> = cuts.iter().map(|cut| cut % bytes.len()).collect();
    points.push(0);
    points.push(bytes.len());
    points.sort_unstable();
    points.dedup();
    points
        .windows(2)
        .map(|pair| bytes[pair[0]..pair[1]].to_vec())
        .collect()
}

fn decode_all(chunks: &[Vec<u8>], limits: Limits) -> Vec<String> {
    let mut decoder = FrameDecoder::new(limits);
    let mut frames = Vec::new();
    for chunk in chunks {
        frames.extend(decoder.push(chunk).expect("valid stream decodes"));
    }
    decoder.finish().expect("stream ends on a frame boundary");
    frames
}

proptest! {
    #[test]
    fn frame_parts_round_trip_under_any_chunking(
        payload in "\\PC{0,400}",
        limits in limits(),
        cuts in prop::collection::vec(any::<usize>(), 0..12),
    ) {
        prop_assume!(payload.len() <= limits.max_reassembled_bytes());
        let wire: Vec<u8> = encode_frame_parts(&payload, limits).expect("fits the limits").concat();
        prop_assert_eq!(decode_all(&split(&wire, &cuts), limits), vec![payload]);
    }

    #[test]
    fn every_part_respects_the_frame_limit(payload in "\\PC{1,400}", limits in limits()) {
        prop_assume!(payload.len() <= limits.max_reassembled_bytes());
        let buffers = encode_frame_parts(&payload, limits).expect("fits the limits");
        let overhead = if buffers.len() == 1 { 4 } else { 13 };
        for buffer in &buffers {
            prop_assert!(buffer.len() <= limits.max_frame_bytes() + overhead);
        }
        prop_assert_eq!(buffers.len(), payload.len().div_ceil(limits.max_frame_bytes()));
    }

    #[test]
    fn a_stream_of_mixed_frames_round_trips(
        payloads in prop::collection::vec("\\PC{0,120}", 0..8),
        limits in limits(),
        cuts in prop::collection::vec(any::<usize>(), 0..16),
    ) {
        let payloads: Vec<String> = payloads
            .into_iter()
            .filter(|payload| payload.len() <= limits.max_reassembled_bytes())
            .collect();
        let wire: Vec<u8> = payloads
            .iter()
            .flat_map(|payload| encode_frame_parts(payload, limits).expect("fits").concat())
            .collect();
        prop_assert_eq!(decode_all(&split(&wire, &cuts), limits), payloads);
    }

    #[test]
    fn plain_frames_round_trip(payload in "\\PC{0,64}") {
        let limits = Limits::new(256, 256).expect("valid limits");
        let frame = encode_frame(&payload, limits).expect("fits");
        prop_assert_eq!(decode_all(&[frame], limits), vec![payload]);
    }

    #[test]
    fn arbitrary_bytes_never_panic_and_never_exceed_the_limits(
        bytes in prop::collection::vec(any::<u8>(), 0..256),
        limits in limits(),
    ) {
        let mut decoder = FrameDecoder::new(limits);
        if let Ok(frames) = decoder.push(&bytes) {
            for frame in frames {
                prop_assert!(frame.len() <= limits.max_reassembled_bytes().max(limits.max_frame_bytes()));
            }
        }
    }

    #[test]
    fn a_logical_frame_over_the_reassembly_limit_is_refused_by_both_ends(
        extra in 1_usize..64,
        limits in limits(),
    ) {
        let payload = "x".repeat(limits.max_reassembled_bytes() + extra);
        prop_assert!(encode_frame_parts(&payload, limits).is_err());
        let generous = Limits::new(limits.max_frame_bytes(), payload.len()).expect("valid limits");
        let wire: Vec<u8> = encode_frame_parts(&payload, generous).expect("fits the generous limits").concat();
        let mut decoder = FrameDecoder::new(limits);
        prop_assert!(decoder.push(&wire).is_err());
    }

    #[test]
    fn epoch_order_is_total_and_matches_the_pair(
        a in (any::<u64>(), any::<[u8; 32]>()),
        b in (any::<u64>(), any::<[u8; 32]>()),
    ) {
        let left = Epoch { n: a.0, claimant: NodeId::from_bytes(a.1) };
        let right = Epoch { n: b.0, claimant: NodeId::from_bytes(b.1) };
        prop_assert_eq!(left.cmp(&right), a.cmp(&b));
        prop_assert_eq!(left == right, a == b);
        prop_assert_eq!(left.cmp(&right), right.cmp(&left).reverse());
    }
}
