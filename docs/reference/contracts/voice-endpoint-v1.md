# Voice endpoint v1: host-stored speech setup and speech through the host

Status: **accepted; the host side is built, the clients come next.** The owner accepted the seven
recommendations below on 2026-11-15.

Fixtures: one per method, `android-compose/sharedLogic/src/commonTest/resources/contracts/<method>/voice-endpoint-v1.json`,
each paired with a short spec in `docs/reference/contracts/<method>/voice-endpoint-v1.md`.
Client SPI and wire types: `android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/voice/host/`.
Host routes, store and speech server client: `android-compose/sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh/voice/`.
Contract tests: `HostVoiceContractTest` (commonTest) decodes and re-encodes every case through the
client types; `WireContractsTest` (jvmTest) runs every case through the real stream server, capability
gate and voice routes against a fake speech server.

## Decision this implements

The owner decided (2026-11-14) that the speech-server setup lives **on the host**, the way model
provider credentials do, and every client shares it. The host stores the speech server URL, its
API key (write-only: never returned to any client) and the shared grant record. Clients read only
"configured / URL / models"; speech-to-text and text-to-speech requests go **through the host**,
which calls the speech server with the stored key. The per-device `VoiceSetupStore` stops being the
source of truth and becomes a migration path.

A later decision (2026-11-15) makes Android listen **on device** with the system recogniser. The
host-stored server is then the Android listening fallback, and stays primary for desktop listening
and for speech output everywhere. So transcription through the host stays in v1, the status reports
listening and speaking separately, and nothing here assumes a device sends microphone audio.

## How provider credentials work today (the model this mirrors)

| Step | Where |
| --- | --- |
| Client builds `provider.connect` params (`provider_id`, `auth_method_id`, `fields`, `provider_name`, `oauth_config`); `ProviderConnectRequest.toString` names keys only | `sharedLogic/src/commonMain/.../data/repository/modelcontrol/ModelControlWire.kt` |
| Request goes as `admin_rpc` over Iroh: one bi-stream per request, one `admin_rpc` frame, one `admin_rpc_response` frame | client `data/transport/iroh/IrohAppServerTransport.kt` (`adminRpcOverStream`); host `data/controller/node/iroh/AdminRpcStreamServer.kt` |
| Authorisation: capability gate before dispatch. `provider.list` is `chat.read` (`ADMIN_READ_METHODS`); `provider.connect` / `provider.disconnect` fall to `admin.full` because they change what every client sees | `data/controller/node/iroh/IrohPeerCapabilities.kt` (`forAdminMethod`) |
| Handler decodes params to the App Server's `connect_provider` command and writes the App Server's **local** provider store; the App Server is the only writer | `data/controller/node/iroh/ProviderAdminHandlers.kt`, `ProviderCatalogProjection.kt` (`ProviderConnectParams`) |
| Response: refreshed provider rows (connected flag, base URL, never the key) plus `models_may_have_changed` | `ProviderCatalogProjection.toAdminRow` |
| Write-only key types: `SecretWriteValue` (redacted `toString`, identity equality, `internal revealForEncoding`) inside command classes whose serializer encodes but refuses to decode | `data/model/provider/wire/ProviderMutationWireModels.kt` |
| Timeouts and breaker policy per native op (`provider.connect` is `MutationAmbiguous`) | `data/controller/node/iroh/NativeAdminOperationPolicy.kt` |

(Paths are under `android-compose/sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/` unless shown otherwise.)

Voice differs in one way: the speech server is not an App Server provider, so the host keeps the
setup in its **own** store, not through `connect_provider`: the setup in `voice-setup.json` and the
key in `voice-speech-key`, a file only the host's user may read, both beside the host's model exposure
state. The key file also names the origin the key was entered for, and the host never uses it for
another origin. Everything else follows the
same pattern: admin-only writes, broadly readable status without the key, and the key leaves the
client exactly once, inside a write.

## Transport, discovery and versioning

- All methods are `admin_rpc` methods with the existing envelope: request
  `{"type":"admin_rpc","request_id","method","params"}`, response
  `{"type":"admin_rpc_response","request_id","success","result"|"error"}`.
- A host that serves every v1 method advertises the auth capability **`voice_endpoint_v1`**
  (through `AdminRpcRouter.featureCapabilities`). Without it a client treats the host as having no
  shared setup and keeps its local one (see Migration).
- `voice.speech` is the first streamed `admin_rpc` method (see Synthesis). It is valid only on a
  request bi-stream of its own; sent on the control channel the host answers `voice.invalid_request`.
- Fields are snake_case. Readers ignore unknown fields; writers omit null optional fields.
- **v2 coexistence:** additive changes (new optional fields, new error codes a v1 client maps to
  `internal`) stay v1. A breaking change ships as `voice_endpoint_v2` with new method names
  (`voice.v2.*`); a host serves both families for at least one release, and a client picks the
  highest family both sides advertise. `contract_version` in status and setup writes names the
  family the message belongs to.

## Methods and authorisation

| Method | Capability | Native policy | Purpose |
| --- | --- | --- | --- |
| `voice.setup.get` | `chat.read` | Read | Shared setup status, never the key |
| `voice.models.list` | `chat.read` | Read | Models and voices of the stored server; refreshes reachability |
| `voice.setup.set` | `admin.full` | MutationAmbiguous | Write URL, names, key change and grants |
| `voice.setup.clear` | `admin.full` | MutationAmbiguous | Delete setup, key and grants |
| `voice.setup.test` | `admin.full` | Read | Check the stored setup or a candidate; sends no audio or text |
| `voice.transcribe` | `chat.send` | Read | One clip to text through the host |
| `voice.speech` | `chat.send` | Read | One text to streamed PCM through the host |

Reads mirror `provider.list` (`chat.read`, held by every default role). Writes mirror
`provider.connect` (`admin.full`), because they change what every device uses and what leaves the
host. `voice.setup.test` is admin-only because a candidate carries a key and makes the host call an
address a client chose; a non-admin device checks the stored setup through `voice.models.list`.
Speech requests use `chat.send`, the capability that already lets a device make the agent act for
it. A capability refusal comes from the transport before dispatch:
`{"success":false,"error":"forbidden","capability":"<needed>"}`.

The host lists `voice.setup.get` and `voice.models.list` in `ADMIN_READ_METHODS` and
`voice.transcribe` / `voice.speech` in `CHAT_SEND_METHODS`; `set`, `clear` and `test` stay in the
`admin.full` default branch. The native policy column is advisory: voice calls never go through the
App Server, so they have no `NativeAdminOp` entry.

## Setup status (`voice.setup.get`, and the result of `set` and `clear`)

```json
{"contract_version": 1, "configured": true, "revision": "rev-2",
 "base_url": "https://speech.example.invalid", "has_api_key": true,
 "transcription": {"available": true, "model": "stt-model-a", "language": "en"},
 "speech": {"available": true, "model": "tts-model-a", "voice": "voice-a"},
 "consent": {"origin": "https://speech.example.invalid:443",
             "grants": {"audio_for_transcription": true, "text_for_synthesis": true},
             "granted_at_ms": 1767225600000},
 "reachability": {"state": "reachable", "checked_at_ms": 1767225600000}}
```

- The key is never present: not the value, not a mask, not its length. `has_api_key` says only
  whether one is stored, as the provider rows say only whether a provider is connected.
- `transcription.available` is true exactly when the host would accept `voice.transcribe` now:
  configured, a transcription model set and the audio grant present. `speech.available` likewise
  needs a speech model, a voice and the text grant. A device that listens on its own recogniser
  reads only `speech.available`.
- `revision` is opaque and changes on every write; `set` and `clear` take it as `expected_revision`
  for optimistic concurrency.
- `reachability.state` is `unknown` (not checked since the setup changed), `reachable` or `failed`
  with `error` holding a code from the closed set. `voice.setup.test` (stored) and
  `voice.models.list` update it; every setup write resets it to `unknown`.
- Times are epoch milliseconds by the host's clock. Clients display them and never compare them
  with their own clock for decisions.
- Nothing stored: `configured: false`, `has_api_key: false`, both kinds unavailable, empty grants,
  reachability `unknown`; no `revision`, `base_url` or `origin`.

## Setup write (`voice.setup.set`)

```json
{"contract_version": 1, "base_url": "https://speech.example.invalid",
 "transcription_model": "stt-model-a", "speech_model": "tts-model-a", "voice": "voice-a",
 "language": "en", "api_key": "<write-only>",
 "grants": {"audio_for_transcription": true, "text_for_synthesis": true},
 "expected_revision": "rev-1"}
```

- `base_url` is required: plain `http`/`https`, no user info, query, fragment or whitespace (the
  existing `VoiceEndpointRules` / `SpeechEndpointAddress` rules). Otherwise `voice.invalid_request`.
- Key change, at most one of: `api_key` (store this key), `remove_api_key: true` (delete it), or
  neither (keep it). **Keep applies only while the origin is unchanged; a write to a new origin
  drops the stored key**, so a key is never sent to a server it was not entered for. Both present
  is `voice.invalid_request`.
- A key over plain `http` to a host other than loopback (as seen from the host) is
  `voice.insecure_transport`; nothing is stored.
- Model and voice names are optional; a missing one leaves that kind unavailable.
- `grants` is the whole shared grant record for the new origin (see Consent). A write to a new
  origin without grants leaves none: a changed address never inherits consent.
- `expected_revision`, when present and stale, is `voice.revision_conflict`.
- The result is the new status. The write does not contact the speech server.
- Client type: `HostVoiceSetupCommand` (not a data class; its serializer refuses to decode; its
  `toString` shows no key), with the key as the provider path's `SecretWriteValue`.

## Clear (`voice.setup.clear`)

Params `{"expected_revision": "rev-2"}` (optional). Deletes URL, names, key and grants; the result
is the empty status. Stale revision: `voice.revision_conflict`.

## Connection test (`voice.setup.test`)

- `{}` checks the stored setup with the stored key and updates `reachability`.
- `{"base_url": "...", "api_key": "..."}` checks a candidate and forgets it. Without `api_key`, the
  host sends the stored key only when the candidate has the stored origin; on another origin it
  checks without a key.
- The host calls `GET {base}/v1/models` only; no audio or text is sent, so no grant is needed.
- Result `{"model_count": 2, "checked_at_ms": 1767225600000}`; failures use the closed codes.

## Models (`voice.models.list`)

Result `{"models": [{"id": "..."}], "voices": [{"id": "..."}], "voices_source": "upstream"|"configured", "checked_at_ms": ...}`.
Models come from `GET {base}/v1/models`. Voices come from `GET {base}/v1/audio/voices` when the
server answers it with a list; otherwise the list is just the stored voice and the source is
`configured`. Nothing stored: `voice.not_configured`.

## Transcription (`voice.transcribe`)

- Params `{"audio": "<base64>", "encoding": "wav", "language": "en"}`. v1 accepts only `wav`:
  mono 16-bit PCM WAVE, which the voice ear already produces. Anything else is
  `voice.invalid_request`.
- Limits: at most **1 MiB** of decoded audio and **30 s** (from the WAVE header), else
  `voice.payload_too_large`; the ear's turns end at 20 s. The base64 frame may exceed one 1 MiB
  Iroh frame, so the client sends it with `frame_part` continuation (both sides already advertise it).
- `language` overrides the stored hint for this request; absent uses the stored hint.
- The host asks the server for `verbose_json` and falls back to `json` on a 400, 415 or 422 (the
  device-side `SpeechEndpointClient.transcribe` falls back on 400 and 422), and caches the fallback per
  stored setup revision.
- Result `{"text": "...", "format": "verbose_json"|"json", "segments": [{"no_speech_prob": 0.02, "avg_logprob": -0.25}]}`.
  Segments carry the recogniser's numbers that the ear's phantom-turn filter relies on, never
  segment text; `json` answers have `segments: []`. The client maps this to `SpeechTranscript`.
- Requires the audio grant (`voice.consent_required`) and a transcription model
  (`voice.not_configured`).

## Synthesis (`voice.speech`), streamed

The existing `admin_rpc` stream carries one response. Speech output must start playing with its
first audio, so `voice.speech` adds **stream frames** before the final response on the same
request bi-stream:

1. Client opens a bi-stream and writes one `admin_rpc` frame: `{"method": "voice.speech", "params": {"text": "..."}}`.
   It does **not** finish its send half: that half stays open as the cancel channel.
2. Host validates (grant, model, voice, text at most 4096 characters, one synthesis in flight),
   then calls `POST {base}/v1/audio/speech` with `response_format: "wav"` and reads the body as it
   streams. A failure before any audio is a single final response with the code.
3. When the WAVE header arrives, the host writes
   `{"type":"admin_rpc_stream","request_id","seq":0,"event":"format","sample_rate_hz":24000,"channels":1,"sample_format":"s16le"}`.
   The host decodes the WAVE stream itself (it has `WavStreamDecoder`), so clients receive only
   PCM with its format stated once; the host does not resample.
4. Each run of decoded samples is written at once as
   `{"type":"admin_rpc_stream","request_id","seq":n,"event":"audio","pcm":"<base64 s16le>"}`:
   whole sample frames only, at most 64 KiB of PCM per frame, and never held back to fill a
   frame. The host must not buffer the upstream body or wait for it to complete. `seq` counts from
   0 without gaps.
5. After the last audio, the final response:
   `{"type":"admin_rpc_response","request_id","success":true,"result":{"audio_frames":2,"sample_frames":6}}`,
   then the host finishes its send half. A failure after audio started (for example a broken
   stream) is a final failed response with the code; the client drops the partial segment.

**Cancellation** (barge-in, stop, conversation change). Any of these cancels the request: the client
writes `{"type":"admin_rpc_cancel","request_id"}`, the client finishes or resets its send half, a
host write fails, or the connection drops. Within **250 ms** the host must stop writing audio,
**cancel the upstream HTTP request** (close the connection, not just stop reading, so the speech
server stops generating), release the in-flight slot, try once to write the final
`voice.cancelled` response, and finish. The client stops playback at once, stops reading after the
final response or 1 s, and never plays frames that arrive after it cancelled.

**Concurrency.** At most one `voice.speech` and one `voice.transcribe` in flight per connection
(the voice mouth requests one segment at a time and starts the next only after the current
response completes); a second is `voice.busy`. The host releases the slot before writing the
final response, so the mouth's next request never races it. Host-wide, at most 4 concurrent
upstream speech calls; beyond that `voice.busy`.

**Timeouts.** Host to speech server: connect 10 s; first byte 15 s; whole transcription 30 s;
whole synthesis 120 s; reachability 10 s. Expiry is `voice.upstream_timeout`. Client: for
`voice.speech` an idle limit of 20 s between frames instead of the 30 s whole-request admin_rpc
budget; for the other methods the existing budget.

## Errors (closed set)

The failure response's `error` is exactly one of these strings; nothing else is appended.

| `error` | Retryable | Meaning | Voice loop code |
| --- | --- | --- | --- |
| `voice.not_configured` | no | No setup, or none for this kind | UNAVAILABLE |
| `voice.consent_required` | no | Shared grant for this payload missing; nothing sent | PERMISSION_REQUIRED |
| `voice.invalid_request` | no | Malformed params, unsupported encoding, invalid address, wrong channel | REJECTED |
| `voice.payload_too_large` | no | Over the audio or text limits | REJECTED |
| `voice.insecure_transport` | no | Key would travel over plain http off loopback | INSECURE_TRANSPORT |
| `voice.upstream_unreachable` | yes | Server not reachable | UNREACHABLE |
| `voice.upstream_unresolved` | yes | Server's host name did not resolve where the request ran | UNRESOLVED |
| `voice.upstream_unauthorized` | no | Server refused the key (401/403) | UNAUTHORIZED |
| `voice.upstream_rejected` | no | Server refused the request (400/404/422 after fallbacks) | REJECTED |
| `voice.upstream_bad_response` | yes | Unexpected shape, broken or non-PCM stream | BAD_RESPONSE |
| `voice.upstream_timeout` | yes | Server too slow | TIMED_OUT |
| `voice.busy` | yes | Request of this kind already in flight, or host at its limit | BUSY |
| `voice.revision_conflict` | no (re-read first) | Setup changed since `expected_revision` | REJECTED |
| `voice.cancelled` | no | Client cancelled | CANCELLED |
| `forbidden` (transport) | no | Pairing lacks the capability | PERMISSION_REQUIRED |
| `Unknown method: …` (router) | no | Host predates v1; client maps to `unsupported` | UNAVAILABLE |
| anything else | yes | Host internal error; client maps to `internal` | ENGINE_FAILED |

HTTP statuses map as: 401/403 `upstream_unauthorized`; 408/504 `upstream_timeout`; 502/503
`upstream_unreachable`; any other 4xx `upstream_rejected`; any other non-2xx `upstream_bad_response`.
Upstream response bodies, upstream messages, the server address and the key never appear in an
error. `HostVoiceErrorCode.fromWire` never echoes the raw string.

## Consent: the shared grant record and the device acknowledgement

**What per-device consent was.** `VoiceEndpointGrants` holds one grant per (origin, payload):
`AUDIO_FOR_TRANSCRIPTION` and `TEXT_FOR_SYNTHESIS`. It is stored apart from the settings so an
export never carries consent to another device; endpoint engines check it before every request and
never fall back to another destination. Its purpose: the person agreed that **this** data goes to
**that** server.

**What moving it to the host means.** The host keeps one grant record bound to the stored origin
(`consent` in the status). It is written only by `voice.setup.set` (admin), revoked by a write with
fewer grants or by `voice.setup.clear`, and it never survives an origin change. The host checks it
before every upstream call and answers `voice.consent_required` instead of sending. A second device
sees the record, the origin and when it was granted in `voice.setup.get`.

**What a shared grant would weaken, and the recommendation.** Reply text is already on the host
(the agent's reply comes from there), so a host grant for `text_for_synthesis` loses nothing. Audio
is different: a device's microphone audio leaving **that device** for the first time is the event
the per-device grant protected, and an admin approving it on the desktop is not the person holding
the phone agreeing to it. **Recommendation (built into the contract):** keep the shared host grant
as the destination record, and add a **one-time local acknowledgement per device and origin, only
for audio and only when the device actually uses server transcription**. A device that listens on
its own recogniser (Android's default) never asks it; an Android device asks it the first time it
falls back to server listening; a desktop asks it the first time voice mode listens through the
host. The acknowledgement names the origin from the status, is stored in the device's own store
(like today's grants, never exported), is invalidated when the origin changes, and blocks
`voice.transcribe` calls from that device until given. The host cannot verify it; it is the
client's duty, and the contract makes it observable to the client by publishing the origin. No
acknowledgement is needed for speaking.

## Host privacy and logging rules

- Audio (uploaded or synthesised), transcript text and reply text are never logged, persisted,
  cached or put in telemetry or crash reports; they live in memory for the request only.
- The key lives only in the host's secure store; it is never logged, returned, echoed in an error,
  sent to an origin other than the one it was stored for, or sent over plain http off loopback.
- The server address and origin never go to telemetry (they may appear in the status, which only
  paired devices read).
- May be logged: method, error code, request id, durations (including time to first audio), byte
  and sample counts, upstream HTTP status number, cancel reason, and whether a key was used.
- Upstream response bodies are never logged or forwarded.

## Migration from per-device setup

| Device local setup | Host (`voice_endpoint_v1`) | Behaviour |
| --- | --- | --- |
| any | not advertised | Unchanged: local `VoiceSetupStore`, direct to the server |
| none | configured | Use the host; nothing to migrate |
| present | not configured, device is admin | Offer **"Move this speech server to the host"**: writes URL, models, voice and the local grants via `voice.setup.set` |
| present | not configured, device not admin | Keep direct mode; say an admin device can share it |
| present | configured | Host wins at once. If the origins differ the device says so. Offer to remove the local setup |

**Key handling, recommendation:** the move offers to upload the local key with an explicit
confirmation naming the host ("Send the API key to <host>. It will be removed from this device.").
On confirmation the key goes once inside `voice.setup.set`; after the host's status shows
`has_api_key: true` at the new revision, the local key is deleted. If the person declines, the URL
and names move without the key and the host's reachability will say whether the server needs one;
the local key stays until the person removes the local setup. A local key is never deleted silently.
Reasons for upload-then-delete: one source of truth, no two copies to rotate, and the key crosses
the authenticated Iroh link once, which is no weaker than typing it on the desktop.

**No silent fallback.** Once a host setup is in force, a device never falls back to its local
setup on a host error: that would send audio or text to a destination other than the shared,
granted one. A host without v1, or a cleared host setup, returns the device to its local setup only
if it still has one.

## Client SPI (sharedLogic `data.voice.host`)

`HostVoiceEndpoint`: `status()`, `writeSetup(command)`, `clearSetup(expectedRevision)`,
`testConnection(candidate)`, `models()`, `transcribe(audio, language)` returning
`SpeechTranscript`, and `synthesize(text)` returning `Flow<PcmChunk>`; failures are
`HostVoiceException(HostVoiceErrorCode)`. `transcribe` matches `SpeechTranscriber` (a stage 3
adapter is `SpeechTranscriber { host.transcribe(it) }`) and `synthesize` matches
`EndpointSpeechSynthesizer.stream`, so the loop does not know whether it talks to the server
directly or through the host. The existing `VoiceEar` / `SpeechTranscriber` / `VoiceMouth`
interfaces are unchanged.

## Out of scope for v1

Per-device or per-agent overrides of the shared server, model or voice; more than one speech
server; streaming or partial transcription; realtime speech models; voice upload or cloning;
compressed upload formats and host-side resampling; server-side voice activity detection; quotas
per device; the web client; and moving the wake word or on-device recognition anywhere.

## Open questions (recommendation built)

1. Speech capability: reuse `chat.send` (built) or add `voice.use`? Recommend `chat.send`; a new
   capability would need a pairing migration for every existing device.
2. Key on migration: upload-then-delete with confirmation (built) or never upload? Recommend upload.
3. Device acknowledgement for audio on top of the shared grant (built), audio only, only when
   server transcription is used. Recommend yes.
4. Who may revoke: admin only (built). A non-admin device can always stop its own audio by
   withdrawing its local acknowledgement.
5. Host decodes WAVE and streams PCM (built) rather than passing WAVE bytes through. Recommend
   PCM: one format statement, no header parsing on clients.
6. Second concurrent synthesis: reject `voice.busy` (built) rather than supersede. Recommend
   reject: superseding hides client bugs; barge-in cancels explicitly.
7. Status shows `has_api_key` (built). Recommend yes; it reveals presence only, as provider rows do.
