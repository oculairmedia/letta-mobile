//! Runs `node-protocol/node-protocol-v1.json`: the ALPN table, closures, auth vocabulary, limits,
//! the auth-failure limiter scenarios, and every control-lane frame example, which must parse to its
//! type and serialize back to exactly the bytes the Kotlin node writes.

use core_proto::alpn::{Admission, Alpn};
use core_proto::auth::{self, AuthErrorCode, AuthFailureLimiter, FailureOutcome};
use core_proto::capability;
use core_proto::closure::Closure;
use core_proto::codec::{self, Limits};
use core_proto::frames::{self, ControlFrame, ErrorFrame, Lane};
use core_proto::ids::RequestId;
use core_testkit::load_json;
use serde::Deserialize;
use serde_json::Value;

const FIXTURE: &str = "node-protocol/node-protocol-v1.json";

#[derive(Deserialize)]
struct Fixture {
    contract: String,
    version: u32,
    alpns: Vec<AlpnRow>,
    unknown_alpns: Vec<String>,
    closures: Vec<ClosureRow>,
    auth_errors: Vec<AuthErrorRow>,
    limits: LimitsRow,
    capabilities: CapabilitiesRow,
    limiter_scenarios: Vec<Scenario>,
    control_frames: Vec<FrameRow>,
}

#[derive(Deserialize)]
struct AlpnRow {
    alpn: String,
    route: Alpn,
    admission: Admission,
    refusal: ClosureRow,
}

#[derive(Deserialize, PartialEq, Eq, Debug)]
struct ClosureRow {
    code: u64,
    reason: String,
}

#[derive(Deserialize)]
struct AuthErrorRow {
    error: String,
    counts_against_session: bool,
}

#[derive(Deserialize)]
struct LimitsRow {
    max_frame_bytes: usize,
    max_reassembled_bytes: usize,
    pre_auth_max_reassembled_bytes: usize,
    max_failures_per_window: usize,
    lockout_window_ms: u64,
    max_tracked_peers: usize,
    max_failures_before_close: u32,
    min_token_length: usize,
    handshake_timeout_ms: u64,
    admin_rpc_first_frame_timeout_ms: u64,
    max_concurrent_admin_rpc_calls: usize,
}

#[derive(Deserialize)]
struct CapabilitiesRow {
    frame_part: String,
    always_advertised: Vec<String>,
}

#[derive(Deserialize)]
struct Scenario {
    name: String,
    limits: Option<ScenarioLimits>,
    steps: Vec<Step>,
}

#[derive(Deserialize)]
struct ScenarioLimits {
    max_failures_per_window: usize,
    lockout_window_ms: u64,
    max_tracked_peers: usize,
}

#[derive(Deserialize)]
#[serde(tag = "op", rename_all = "snake_case")]
enum Step {
    Deny {
        peer: String,
        at_ms: u64,
        reason: String,
        expect: String,
    },
    Check {
        peer: String,
        at_ms: u64,
        expect_rate_limited: bool,
    },
    Tracked {
        expect: usize,
    },
}

#[derive(Deserialize)]
struct FrameRow {
    name: String,
    lane: Lane,
    from: String,
    #[serde(rename = "type")]
    type_name: String,
    wire: String,
}

fn fixture() -> Fixture {
    let fixture: Fixture = load_json(FIXTURE).expect("node protocol fixture loads");
    assert_eq!(fixture.contract, "node-protocol");
    assert_eq!(fixture.version, 1);
    fixture
}

#[test]
fn alpns_route_as_the_table_says() {
    let fixture = fixture();
    assert_eq!(fixture.alpns.len(), Alpn::ALL.len());
    for row in &fixture.alpns {
        let alpn = Alpn::from_bytes(row.alpn.as_bytes())
            .unwrap_or_else(|| panic!("{} is known", row.alpn));
        assert_eq!(alpn, row.route, "{}", row.alpn);
        assert_eq!(alpn.admission(), row.admission, "{}", row.alpn);
        let refusal = alpn.refusal();
        assert_eq!(
            ClosureRow {
                code: refusal.code,
                reason: refusal.reason.to_owned()
            },
            row.refusal,
            "{}",
            row.alpn
        );
    }
    for unknown in &fixture.unknown_alpns {
        assert_eq!(Alpn::from_bytes(unknown.as_bytes()), None, "{unknown:?}");
    }
    assert_eq!(Closure::UNKNOWN_ALPN.code, 4404);
}

#[test]
fn closures_are_the_table() {
    let fixture = fixture();
    let ours: Vec<ClosureRow> = Closure::ALL
        .iter()
        .map(|closure| ClosureRow {
            code: closure.code,
            reason: closure.reason.to_owned(),
        })
        .collect();
    assert_eq!(ours, fixture.closures);
}

#[test]
fn auth_errors_keep_their_labels_and_session_accounting() {
    for row in fixture().auth_errors {
        let code = AuthErrorCode::from(row.error.clone());
        assert!(
            !matches!(code, AuthErrorCode::Other(_)),
            "{} is a known label",
            row.error
        );
        assert_eq!(code.as_str(), row.error);
        assert_eq!(
            code.counts_against_session(),
            row.counts_against_session,
            "{}",
            row.error
        );
    }
}

#[test]
fn limits_match_the_constants() {
    let limits = fixture().limits;
    assert_eq!(limits.max_frame_bytes, codec::MAX_FRAME_BYTES);
    assert_eq!(limits.max_reassembled_bytes, codec::MAX_REASSEMBLED_BYTES);
    assert_eq!(
        limits.pre_auth_max_reassembled_bytes,
        Limits::PRE_AUTH.max_reassembled_bytes()
    );
    assert_eq!(
        limits.max_failures_per_window,
        auth::MAX_FAILURES_PER_WINDOW
    );
    assert_eq!(limits.lockout_window_ms, auth::LOCKOUT_WINDOW_MS);
    assert_eq!(limits.max_tracked_peers, auth::MAX_TRACKED_PEERS);
    assert_eq!(
        limits.max_failures_before_close,
        auth::MAX_FAILURES_BEFORE_CLOSE
    );
    assert_eq!(limits.min_token_length, auth::MIN_TOKEN_LENGTH);
    assert_eq!(limits.handshake_timeout_ms, auth::HANDSHAKE_TIMEOUT_MS);
    assert_eq!(
        limits.admin_rpc_first_frame_timeout_ms,
        frames::ADMIN_RPC_FIRST_FRAME_TIMEOUT_MS
    );
    assert_eq!(
        limits.max_concurrent_admin_rpc_calls,
        frames::MAX_CONCURRENT_ADMIN_RPC_CALLS
    );
}

#[test]
fn capabilities_are_spelled_as_the_wire_spells_them() {
    let capabilities = fixture().capabilities;
    assert_eq!(capabilities.frame_part, capability::FRAME_PART);
    assert_eq!(
        capabilities.always_advertised,
        capability::ALWAYS_ADVERTISED
    );
    assert_eq!(capability::EVENT_SEQ_ORDERED_V1, "event_seq_ordered_v1");
    assert!(capability::advertises(
        &capabilities.always_advertised,
        capability::EVENT_SEQ_ORDERED_V1
    ));
}

#[test]
fn limiter_scenarios_decide_as_written() {
    for scenario in fixture().limiter_scenarios {
        let mut limiter =
            scenario
                .limits
                .map_or_else(AuthFailureLimiter::<String>::new, |limits| {
                    AuthFailureLimiter::with_limits(
                        limits.max_failures_per_window,
                        limits.lockout_window_ms,
                        limits.max_tracked_peers,
                    )
                });
        for (index, step) in scenario.steps.into_iter().enumerate() {
            let label = format!("{} step {index}", scenario.name);
            match step {
                Step::Deny {
                    peer,
                    at_ms,
                    reason,
                    expect,
                } => {
                    let outcome = limiter.deny(&peer, AuthErrorCode::from(reason), at_ms);
                    let reported = match outcome {
                        FailureOutcome::Denied(code) => String::from(code),
                        FailureOutcome::RateLimited => {
                            AuthErrorCode::RateLimited.as_str().to_owned()
                        }
                    };
                    assert_eq!(reported, expect, "{label}");
                }
                Step::Check {
                    peer,
                    at_ms,
                    expect_rate_limited,
                } => assert_eq!(
                    limiter.is_rate_limited(&peer, at_ms),
                    expect_rate_limited,
                    "{label}"
                ),
                Step::Tracked { expect } => assert_eq!(limiter.tracked(), expect, "{label}"),
            }
        }
    }
}

#[test]
fn control_frames_round_trip_byte_for_byte() {
    let rows = fixture().control_frames;
    assert!(rows.len() >= 30);
    for row in rows {
        let frame =
            ControlFrame::parse(&row.wire).unwrap_or_else(|error| panic!("{}: {error}", row.name));
        assert_eq!(frame.type_name(), row.type_name, "{}", row.name);
        assert_eq!(frame.to_json(), row.wire, "{}: serialized bytes", row.name);
        let typed = !matches!(frame, ControlFrame::Passthrough(_));
        let expected_typed = matches!(
            row.type_name.as_str(),
            "auth"
                | "auth_response"
                | "error"
                | "runtime_start"
                | "runtime_start_response"
                | "admin_rpc"
                | "admin_rpc_response"
        );
        assert_eq!(typed, expected_typed, "{}: typed", row.name);
        if row.from == "peer" && row.lane == Lane::Control {
            assert!(
                frames::NODE_ACCEPTED_CONTROL_TYPES.contains(&row.type_name.as_str()),
                "{}: a node accepts {}",
                row.name,
                row.type_name
            );
        }
    }
}

/// The constructors a node uses produce exactly the fixture's denial frames.
#[test]
fn denial_constructors_match_the_fixture() {
    let wire = |name: &str| -> String {
        fixture()
            .control_frames
            .into_iter()
            .find(|row| row.name == name)
            .unwrap_or_else(|| panic!("fixture has {name}"))
            .wire
    };
    let id = RequestId::new("node-2");
    let error = |frame: ErrorFrame| ControlFrame::Error(frame).to_json();
    assert_eq!(
        error(ErrorFrame::unauthorized(Some(&id))),
        wire("error_unauthorized")
    );
    assert_eq!(
        error(ErrorFrame::unauthorized(None)),
        wire("error_unauthorized_without_request_id")
    );
    assert_eq!(
        error(ErrorFrame::forbidden(Some(&id), "chat.send")),
        wire("error_capability_denied")
    );
    assert_eq!(
        error(ErrorFrame::agent_scope(Some(&id))),
        wire("error_agent_scope_denied")
    );
    assert_eq!(
        error(ErrorFrame::unknown_command_type(Some("frobnicate"))),
        wire("error_unknown_command_type")
    );
    assert_eq!(
        error(ErrorFrame::unknown_command_type(None)),
        wire("error_command_without_type")
    );
    assert_eq!(
        error(ErrorFrame::parse_failure("SerializationException")),
        wire("error_parse_failure")
    );
    let auth = |frame| ControlFrame::AuthResponse(frame).to_json();
    let caps: Vec<String> = capability::ALWAYS_ADVERTISED
        .iter()
        .map(|&c| c.to_owned())
        .collect();
    let request = RequestId::new("node-1");
    assert_eq!(
        auth(frames::AuthResponse::accepted(
            request.clone(),
            caps.clone(),
            frames::Pairing::NotReported
        )),
        wire("auth_accepted")
    );
    assert_eq!(
        auth(frames::AuthResponse::accepted(
            request.clone(),
            caps.clone(),
            frames::Pairing::NewlyPaired
        )),
        wire("auth_accepted_invite_redeemed")
    );
    assert_eq!(
        auth(frames::AuthResponse::accepted(
            request.clone(),
            caps,
            frames::Pairing::AlreadyPaired
        )),
        wire("auth_accepted_already_paired")
    );
    assert_eq!(
        auth(frames::AuthResponse::refused(
            Some(request),
            AuthErrorCode::InvalidToken
        )),
        wire("auth_refused_invalid_token")
    );
    assert_eq!(
        auth(frames::AuthResponse::refused(
            None,
            AuthErrorCode::RequestIdRequired
        )),
        wire("auth_refused_without_request_id")
    );
    let admin = |frame| ControlFrame::AdminRpcResponse(frame).to_json();
    assert_eq!(
        admin(frames::AdminRpcResponse::forbidden(
            RequestId::new("admin-9"),
            "admin.full"
        )),
        wire("admin_rpc_capability_denied")
    );
    assert_eq!(
        admin(frames::AdminRpcResponse::failed(
            RequestId::new(""),
            "Expected admin_rpc frame"
        )),
        wire("admin_rpc_call_lane_wrong_first_frame")
    );
}

#[test]
fn passthrough_frames_keep_every_field_in_order() {
    let wire = r#"{"type":"input","z":1,"a":{"nested":[1,2]},"request_id":"r"}"#;
    let frame = ControlFrame::parse(wire).expect("parses");
    assert_eq!(frame.request_id(), Some("r"));
    assert_eq!(frame.to_json(), wire);
    let ControlFrame::Passthrough(passthrough) = frame else {
        panic!("input is a passthrough frame");
    };
    assert_eq!(passthrough.fields().get("z"), Some(&Value::from(1)));
}
