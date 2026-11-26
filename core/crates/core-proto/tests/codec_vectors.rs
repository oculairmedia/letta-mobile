//! Runs every vector of `node-protocol/codec-vectors-v1.json` through the codec: encoders must
//! produce the vector's buffers exactly, and decoders fed the wire whole and one byte at a time must
//! produce its frames or fail with its error.

use core_proto::codec::{FrameDecoder, FrameError, Limits, encode_frame, encode_frame_parts};
use core_testkit::{ByteVector, encode_hex, load_json};
use serde::Deserialize;

const FIXTURE: &str = "node-protocol/codec-vectors-v1.json";

#[derive(Deserialize)]
struct Fixture {
    contract: String,
    version: u32,
    default_limits: LimitsSpec,
    vectors: Vec<Vector>,
}

#[derive(Clone, Copy, Deserialize)]
struct LimitsSpec {
    max_frame_bytes: usize,
    max_reassembled_bytes: usize,
}

#[derive(Deserialize)]
struct Vector {
    name: String,
    limits: Option<LimitsSpec>,
    encode: Option<String>,
    payload: Option<ByteVector>,
    wire_parts: Option<Vec<ByteVector>>,
    wire: Option<ByteVector>,
    frames: Option<Vec<ByteVector>>,
    error: Option<ErrorSpec>,
    encode_error: Option<String>,
}

#[derive(Deserialize)]
struct ErrorSpec {
    kind: String,
    at: String,
    #[serde(default)]
    frames_before: Vec<ByteVector>,
}

fn fixture() -> Fixture {
    load_json(FIXTURE).expect("codec vectors load")
}

fn bytes(vector: &ByteVector) -> Vec<u8> {
    vector.to_bytes().expect("valid byte vector")
}

fn text(vector: &ByteVector) -> String {
    String::from_utf8(bytes(vector)).expect("payloads in vectors are UTF-8")
}

fn limits_of(spec: LimitsSpec) -> Limits {
    Limits::new(spec.max_frame_bytes, spec.max_reassembled_bytes).expect("valid limits")
}

fn encode(kind: &str, payload: &str, limits: Limits) -> Result<Vec<Vec<u8>>, FrameError> {
    match kind {
        "plain" => encode_frame(payload, limits).map(|frame| vec![frame]),
        "parts" => encode_frame_parts(payload, limits),
        other => panic!("unknown encoder {other}"),
    }
}

/// Feeds `wire` in chunks of `chunk` bytes; returns the frames and the first error with where it happened.
fn decode(
    wire: &[u8],
    limits: Limits,
    chunk: usize,
) -> (Vec<String>, Option<(FrameError, &'static str)>) {
    let mut decoder = FrameDecoder::new(limits);
    let mut frames = Vec::new();
    for piece in wire.chunks(chunk.max(1)) {
        match decoder.push(piece) {
            Ok(completed) => frames.extend(completed),
            Err(error) => return (frames, Some((error, "push"))),
        }
    }
    match decoder.finish() {
        Ok(()) => (frames, None),
        Err(error) => (frames, Some((error, "finish"))),
    }
}

#[test]
fn the_fixture_is_the_v1_codec_contract() {
    let fixture = fixture();
    assert_eq!(fixture.contract, "node-protocol-codec");
    assert_eq!(fixture.version, 1);
    assert_eq!(limits_of(fixture.default_limits), Limits::DEFAULT);
    let mut names: Vec<&str> = fixture
        .vectors
        .iter()
        .map(|vector| vector.name.as_str())
        .collect();
    let total = names.len();
    names.sort_unstable();
    names.dedup();
    assert_eq!(names.len(), total, "vector names are unique");
}

#[test]
fn encoders_produce_every_vector_exactly() {
    let fixture = fixture();
    let mut checked = 0;
    for vector in &fixture.vectors {
        let (Some(kind), Some(payload)) = (&vector.encode, &vector.payload) else {
            continue;
        };
        let limits = limits_of(vector.limits.unwrap_or(fixture.default_limits));
        let result = encode(kind, &text(payload), limits);
        if let Some(expected) = &vector.encode_error {
            let error = result.expect_err(&vector.name);
            assert_eq!(error.kind(), expected, "{}", vector.name);
        } else {
            let produced = result.unwrap_or_else(|error| panic!("{}: {error}", vector.name));
            let expected: Vec<Vec<u8>> = vector
                .wire_parts
                .as_ref()
                .expect("wire_parts")
                .iter()
                .map(bytes)
                .collect();
            assert_eq!(
                produced.len(),
                expected.len(),
                "{}: buffer count",
                vector.name
            );
            for (index, (got, want)) in produced.iter().zip(&expected).enumerate() {
                assert!(
                    got == want,
                    "{} buffer {index}: got {} want {}",
                    vector.name,
                    preview(got),
                    preview(want)
                );
            }
        }
        checked += 1;
    }
    assert!(checked >= 10, "only {checked} encode vectors");
}

#[test]
fn decoders_produce_every_vector_whole_and_byte_by_byte() {
    let fixture = fixture();
    let mut checked = 0;
    for vector in &fixture.vectors {
        let wire = match (&vector.wire, &vector.wire_parts) {
            (Some(wire), _) => bytes(wire),
            (None, Some(parts)) => parts.iter().flat_map(bytes).collect(),
            (None, None) => continue,
        };
        let limits = limits_of(vector.limits.unwrap_or(fixture.default_limits));
        // Byte at a time for small wires; large ones in 4093-byte chunks, which still split every header.
        let fine = if wire.len() > 65_536 { 4_093 } else { 1 };
        for chunk in [wire.len(), fine] {
            let (frames, error) = decode(&wire, limits, chunk);
            let label = format!("{} (chunk {chunk})", vector.name);
            match (&vector.error, error) {
                (None, None) => {
                    let expected: Vec<String> = vector
                        .frames
                        .as_ref()
                        .expect("frames")
                        .iter()
                        .map(text)
                        .collect();
                    assert!(frames == expected, "{label}: frames differ");
                }
                (Some(spec), Some((error, at))) => {
                    assert_eq!(error.kind(), spec.kind, "{label}");
                    assert_eq!(at, spec.at, "{label}: error raised on {at}");
                    if chunk == 1 {
                        let before: Vec<String> = spec.frames_before.iter().map(text).collect();
                        assert_eq!(frames, before, "{label}: frames before the error");
                    }
                }
                (None, Some((error, at))) => panic!("{label}: unexpected {} on {at}", error.kind()),
                (Some(spec), None) => {
                    panic!("{label}: expected {} but decoded {frames:?}", spec.kind)
                }
            }
        }
        checked += 1;
    }
    assert!(checked >= 30, "only {checked} decode vectors");
}

fn preview(bytes: &[u8]) -> String {
    if bytes.len() <= 64 {
        encode_hex(bytes)
    } else {
        format!("{}... ({} bytes)", encode_hex(&bytes[..32]), bytes.len())
    }
}
