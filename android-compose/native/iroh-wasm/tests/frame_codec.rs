use letta_iroh_wasm::frame::{
    FrameDecoder, FrameError, MAX_FRAME_BYTES, MAX_REASSEMBLED_BYTES, encode_frame,
    encode_frame_parts,
};

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

/// One `frame_part` exactly as `IrohFrameCodec.encodeFrameParts` writes it.
fn part(index: u32, fin: bool, payload: &[u8]) -> Vec<u8> {
    let mut out = vec![0x7F, 0xFF, 0xFF, 0xFF, u8::from(fin)];
    out.extend_from_slice(&index.to_be_bytes());
    out.extend_from_slice(
        &u32::try_from(payload.len())
            .expect("part fits u32")
            .to_be_bytes(),
    );
    out.extend_from_slice(payload);
    out
}

/// Splits [payload] into `frame_part`s of at most [part_size] bytes.
fn parts(payload: &[u8], part_size: usize) -> Vec<u8> {
    let chunks: Vec<&[u8]> = payload.chunks(part_size).collect();
    let last = chunks.len() - 1;
    chunks
        .iter()
        .enumerate()
        .flat_map(|(index, chunk)| {
            part(
                u32::try_from(index).expect("index fits u32"),
                index == last,
                chunk,
            )
        })
        .collect()
}

#[test]
fn decoder_reassembles_a_logical_frame_larger_than_one_frame() {
    // The shape that broke the web client: a ~1.8 MB agent.list reply split at 1 MiB.
    let reply = format!("{{\"agents\":\"{}\"}}", "a".repeat(1_833_000));
    let mut decoder = FrameDecoder::new();

    let decoded = decoder
        .push(&parts(reply.as_bytes(), MAX_FRAME_BYTES))
        .expect("parts should reassemble");

    assert_eq!(decoded, vec![reply]);
    decoder
        .finish()
        .expect("decoder should end at a frame boundary");
}

#[test]
fn decoder_reassembles_parts_fed_one_byte_at_a_time_between_plain_frames() {
    let mut bytes = encode_frame("before").expect("plain frame should encode");
    bytes.extend(parts(b"split across three parts", 10));
    bytes.extend(encode_frame("after").expect("plain frame should encode"));
    let mut decoder = FrameDecoder::new();

    let mut decoded = Vec::new();
    for byte in bytes {
        decoded.extend(decoder.push(&[byte]).expect("fragment should decode"));
    }

    assert_eq!(decoded, vec!["before", "split across three parts", "after"]);
    decoder
        .finish()
        .expect("decoder should end at a frame boundary");
}

#[test]
fn decoder_reassembles_utf8_split_mid_character() {
    // "é" is two bytes; a part boundary between them must not be decoded on its own.
    let text = "aé";
    let mut decoder = FrameDecoder::new();

    let decoded = decoder
        .push(&parts(text.as_bytes(), 2))
        .expect("parts should reassemble");

    assert_eq!(decoded, vec![text]);
}

#[test]
fn decoder_reuses_part_indexes_for_the_next_logical_frame() {
    let bytes = [parts(b"first logical", 5), parts(b"second logical", 5)].concat();
    let mut decoder = FrameDecoder::new();

    let decoded = decoder
        .push(&bytes)
        .expect("both sequences should reassemble");

    assert_eq!(decoded, vec!["first logical", "second logical"]);
}

#[test]
fn decoder_rejects_out_of_sequence_parts() {
    let bytes = [part(0, false, b"one"), part(2, true, b"three")].concat();
    let mut decoder = FrameDecoder::new();

    let error = decoder.push(&bytes).expect_err("skipped part must fail");

    assert_eq!(
        error,
        FrameError::PartOutOfSequence {
            expected: 1,
            actual: 2
        }
    );
}

#[test]
fn decoder_rejects_a_plain_frame_mid_sequence() {
    let mut bytes = part(0, false, b"one");
    bytes.extend(encode_frame("plain").expect("plain frame should encode"));
    let mut decoder = FrameDecoder::new();

    let error = decoder
        .push(&bytes)
        .expect_err("interleaved plain frame must fail");

    assert_eq!(error, FrameError::PlainFrameMidSequence { expected: 1 });
}

#[test]
fn decoder_rejects_empty_and_oversized_parts_from_their_header() {
    let mut empty = FrameDecoder::new();
    assert_eq!(empty.push(&part(0, true, b"")), Err(FrameError::EmptyPart));

    let mut oversized_header = part(0, true, b"");
    let too_long = u32::try_from(MAX_FRAME_BYTES + 1).expect("fits u32");
    oversized_header[9..13].copy_from_slice(&too_long.to_be_bytes());
    let mut oversized = FrameDecoder::new();
    let error = oversized
        .push(&oversized_header)
        .expect_err("part longer than a frame must fail before its payload arrives");
    assert!(error.to_string().contains("exceeds maximum"));
}

#[test]
fn decoder_caps_the_reassembled_size() {
    let full_part = vec![b'x'; MAX_FRAME_BYTES];
    let parts_to_fill = MAX_REASSEMBLED_BYTES / MAX_FRAME_BYTES;
    let mut decoder = FrameDecoder::new();
    for index in 0..parts_to_fill {
        let index = u32::try_from(index).expect("fits u32");
        decoder
            .push(&part(index, false, &full_part))
            .expect("parts up to the cap should buffer");
    }

    let next = u32::try_from(parts_to_fill).expect("fits u32");
    let error = decoder
        .push(&part(next, true, b"one byte too many"))
        .expect_err("reassembly past the cap must fail");

    assert!(matches!(
        error,
        FrameError::TooLarge { maximum, .. } if maximum == MAX_REASSEMBLED_BYTES
    ));
}

#[test]
fn decoder_reports_a_stream_that_ends_mid_sequence_as_truncated() {
    let mut decoder = FrameDecoder::new();
    decoder
        .push(&part(0, false, b"one"))
        .expect("non-final part should buffer");

    assert_eq!(decoder.finish(), Err(FrameError::Truncated));
}

#[test]
fn part_encoder_leaves_a_frame_that_fits_plain() {
    let encoded = encode_frame_parts("hello").expect("small payload should encode");

    assert_eq!(
        encoded,
        vec![encode_frame("hello").expect("plain frame should encode")]
    );
}

#[test]
fn part_encoder_writes_the_native_wire_layout() {
    // One byte over a frame: a full first part and a one-byte FIN part.
    let payload = "x".repeat(MAX_FRAME_BYTES + 1);

    let encoded = encode_frame_parts(&payload).expect("payload should split");

    assert_eq!(encoded.len(), 2);
    assert_eq!(
        encoded[0],
        part(0, false, &payload.as_bytes()[..MAX_FRAME_BYTES])
    );
    assert_eq!(encoded[1], part(1, true, b"x"));
}

#[test]
fn part_encoder_round_trips_through_the_decoder() {
    let payload = format!("{{\"text\":\"{}é\"}}", "a".repeat(2 * MAX_FRAME_BYTES + 7));
    let mut decoder = FrameDecoder::new();

    let decoded = decoder
        .push(
            &encode_frame_parts(&payload)
                .expect("payload should split")
                .concat(),
        )
        .expect("parts should reassemble");

    assert_eq!(decoded, vec![payload]);
    decoder
        .finish()
        .expect("decoder should end at a frame boundary");
}

#[test]
fn part_encoder_rejects_payloads_past_the_reassembly_cap() {
    let payload = "x".repeat(MAX_REASSEMBLED_BYTES + 1);

    let error = encode_frame_parts(&payload).expect_err("oversized payload must fail");

    assert!(matches!(
        error,
        FrameError::TooLarge { maximum, .. } if maximum == MAX_REASSEMBLED_BYTES
    ));
}
