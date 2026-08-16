use letta_iroh_wasm::frame::{FrameDecoder, MAX_FRAME_BYTES, encode_frame};

#[test]
fn encodes_big_endian_length_prefix() {
    let encoded = encode_frame("hello").expect("frame should encode");

    assert_eq!(&encoded[..4], &[0, 0, 0, 5]);
    assert_eq!(&encoded[4..], b"hello");
}

#[test]
fn decoder_handles_fragmented_and_back_to_back_frames() {
    let first = encode_frame("one").expect("first frame should encode");
    let second = encode_frame("two").expect("second frame should encode");
    let bytes = [first, second].concat();
    let mut decoder = FrameDecoder::new();

    let mut decoded = Vec::new();
    for byte in bytes {
        decoded.extend(decoder.push(&[byte]).expect("fragment should decode"));
    }

    assert_eq!(decoded, vec!["one", "two"]);
    decoder
        .finish()
        .expect("decoder should end at a frame boundary");
}

#[test]
fn decoder_rejects_oversized_frames() {
    let oversized = u32::try_from(MAX_FRAME_BYTES + 1)
        .expect("configured maximum should fit u32")
        .to_be_bytes();
    let mut decoder = FrameDecoder::new();

    let error = decoder
        .push(&oversized)
        .expect_err("oversized frame must fail");

    assert!(error.to_string().contains("exceeds maximum"));
}

#[test]
fn decoder_rejects_truncated_frames() {
    let mut decoder = FrameDecoder::new();
    decoder
        .push(&[0, 0, 0, 5, b'h', b'i'])
        .expect("partial frame should wait for more bytes");

    let error = decoder.finish().expect_err("truncated frame must fail");

    assert!(error.to_string().contains("truncated"));
}

#[test]
fn encoder_rejects_oversized_payloads() {
    let payload = "x".repeat(MAX_FRAME_BYTES + 1);

    let error = encode_frame(&payload).expect_err("oversized payload must fail");

    assert!(error.to_string().contains("exceeds maximum"));
}
