# voice.speech: voice endpoint v1 (streamed)

- Method: `voice.speech`, the first streamed `admin_rpc` method. Capability: `chat.send`. Valid only on a
  request bi-stream of its own; on the control channel the host answers `voice.invalid_request`.
- Contract: [voice endpoint v1](../voice-endpoint-v1.md), section "Synthesis".
- Params: `text`, at most 4096 characters. The client keeps its send half open as the cancel channel.
- Stream: `admin_rpc_stream` frames with `seq` from 0: one `format` frame (`sample_rate_hz`, `channels`,
  `sample_format: "s16le"`), then `audio` frames of whole sample frames, at most 64 KiB of PCM each,
  written as they decode. Then the final `admin_rpc_response` with `audio_frames` and `sample_frames`.
- Cancel: an `admin_rpc_cancel` frame, the send half finishing or resetting, a failed write or a dropped
  connection cancels the upstream request and ends with `voice.cancelled`.
- Limits: one synthesis per connection and four upstream syntheses per host (`voice.busy`).
- Errors: `voice.invalid_request`, `voice.payload_too_large`, `voice.not_configured`,
  `voice.consent_required` (no text grant), `voice.busy`, `voice.cancelled` and the upstream codes.

Fixture: `voice.speech/voice-endpoint-v1.json`, run through the real stream server and voice routes
against a fake speech server whose body the test writes piece by piece.
