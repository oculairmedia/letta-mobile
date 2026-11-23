# voice.transcribe: voice endpoint v1

- Method: `voice.transcribe` over the authenticated Iroh `admin_rpc` channel. Capability: `chat.send`.
- Contract: [voice endpoint v1](../voice-endpoint-v1.md), section "Transcription".
- Params: `audio` (base64 of a mono 16-bit PCM WAVE clip, at most 1 MiB decoded and 30 s), `encoding`
  (`wav` only), an optional `language` that overrides the stored hint, and an optional `purpose`:
  `live_partial` makes the host send the speech server `X-Voice-Postprocess: basic` (light cleanup);
  absent, `turn` or any other value sends no such header, so the server's default cleanup applies.
- The host asks for `verbose_json` and falls back to `json` when the server refuses that format, and
  remembers the fallback for the stored setup.
- Result: `text`, `format` and `segments` (the recogniser's `no_speech_prob` and `avg_logprob` per
  segment, never segment text; empty for `json`).
- Errors: `voice.invalid_request`, `voice.payload_too_large`, `voice.not_configured`,
  `voice.consent_required` (no audio grant), `voice.busy` (one transcription per connection) and the
  upstream codes.

Fixture: `voice.transcribe/voice-endpoint-v1.json`, run through the real stream server and voice routes
against a fake speech server.
