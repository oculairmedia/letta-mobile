use letta_iroh_wasm::negotiation::auth_allows_frame_parts;

#[test]
fn a_successful_auth_response_listing_frame_part_allows_split_sends() {
    let auth = r#"{"type":"auth_response","request_id":"a","success":true,"capabilities":["frame_part","canvas"]}"#;

    assert_eq!(auth_allows_frame_parts(auth), Some(true));
}

#[test]
fn a_successful_auth_response_without_frame_part_keeps_sends_to_one_frame() {
    let bare = r#"{"type":"auth_response","request_id":"a","success":true}"#;
    let other =
        r#"{"type":"auth_response","request_id":"a","success":true,"capabilities":["canvas"]}"#;

    assert_eq!(auth_allows_frame_parts(bare), Some(false));
    assert_eq!(auth_allows_frame_parts(other), Some(false));
}

#[test]
fn a_failed_auth_response_or_any_other_frame_says_nothing() {
    let failed = r#"{"type":"auth_response","request_id":"a","success":false,"capabilities":["frame_part"]}"#;
    let mentions_auth = r#"{"type":"admin_rpc_response","request_id":"b","success":true,"result":{"note":"auth_response","capabilities":["frame_part"]}}"#;

    assert_eq!(auth_allows_frame_parts(failed), None);
    assert_eq!(auth_allows_frame_parts(mentions_auth), None);
    assert_eq!(auth_allows_frame_parts("not json auth_response"), None);
}

#[test]
fn a_large_frame_is_skipped_without_parsing() {
    let padding = "x".repeat(64 * 1024);
    let large = format!(
        r#"{{"type":"auth_response","request_id":"a","success":true,"capabilities":["frame_part"],"pad":"{padding}"}}"#
    );

    assert_eq!(auth_allows_frame_parts(&large), None);
}
