# System 1 — Laya decision layer

A local FastAPI service wrapping [Laya](https://github.com/NandhaKishorM/laya)
(`convaiinnovations/laya`), a non-autoregressive ModernBERT decision model with
RLCD-calibrated probabilities, as the **System 1** half of Letta Mobile's
dual-process architecture.

| | System 1 (this service) | System 2 (Letta agent) |
|---|---|---|
| Model | ModernBERT encoder, non-autoregressive | autoregressive LLM |
| Latency | ~55ms (guard) / ~88ms (combined), CUDA | seconds |
| Cost | one forward pass | full agent turn |
| Answers | calibrated yes/no, choice, score | open-ended reasoning, tools, memory |

The point is affordability. Interactive UI affordances — should we respond yet,
is this input hostile, does this deserve the expensive model — are per-keystroke
or per-message questions. At LLM prices and latencies they are unaffordable;
here they cost a single forward pass, and System 2 is invoked only when it is
actually needed.

## Status: development harness, off by default

**This service is how we evaluate the concept, not how we intend to ship it.**
It is a Python process with a 1.6GB checkpoint bound to loopback: desktop can
host it, a phone cannot reach it. So `System1Client` is **disabled by default**
and a host that does not opt in pays nothing — no process, no socket, no call.
Desktop development opts in with `System1Client.Config(enabled = true)`.

The deterministic half, `System1ReflexGate`, has no such constraint: it is pure
Kotlin in `commonMain` and runs on every target today.

The intended end state is a unified Kotlin-first inference host covering all
targets — the same paradigm that already runs Gemma on-device for fast local
control on Android. Once that exists, this HTTP service is replaced by an
in-process call and the endpoint disappears. Nothing above the
`System1DecisionEngine` interface should need to change when it does.

## Running it

```bash
pip install -r tools/system1-laya/requirements.txt   # install torch first, see the file
python tools/system1-laya/start_server.py
```

Or in the background: `scripts/start-system1.ps1` (Windows) / `scripts/start-system1.sh` (Unix).

Defaults to `127.0.0.1:8771` — loopback, avoiding Letta's `8283` and the
conventional `8000`. Device is auto-selected (CUDA, then MPS, then CPU) and a
warm-up forward pass runs at startup so the first real request does not pay
kernel autotuning. First run downloads ~1.6GB into the Hugging Face cache;
after that the model loads in ~19s.

```bash
curl -s http://127.0.0.1:8771/health
```

## Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /health` | model, device, dtype, GPU memory, uptime, requests served |
| `POST /predict` | raw Laya passthrough — your own state and questions |
| `POST /v1/system1/guard` | jailbreak, injection, sensitive data, harm severity, topic |
| `POST /v1/system1/route` | difficulty, domain, tool need, sensitivity |
| `POST /v1/system1/triage` | intent, urgency, frustration, churn risk |
| `POST /v1/system1/interaction` | turn-taking: does this expect a response? |
| `POST /v1/system1/evaluate` | all of the above in ONE batched forward pass, plus a disposition |
| `GET /v1/system1/questions` | the question sets actually being evaluated |

Every `/v1/system1/*` endpoint takes the same body:

```json
{ "text": "...", "previous_draft": "...", "context": {}, "thresholds": {} }
```

`/v1/system1/evaluate` returns a `disposition`, which is what the app acts on:

| Disposition | Meaning |
|---|---|
| `BLOCK` | safety signal above the block threshold — refuse without an agent turn |
| `WARN` | elevated safety signal — dispatch, but caution or tighten tool policy |
| `FAST_TRACK` | trivial, tool-free, non-sensitive — cheap model or local answer |
| `ALLOW` | no System 1 objection |

Safety dominates routing: a trivial-looking prompt can never fast-track past a
jailbreak signal. Thresholds come from `SYSTEM1_*` environment variables and can
be overridden per request.

## Calibration findings

**Read this before trusting a field.** Laya is well calibrated on what it was
trained for and unreliable outside it, and the difference is not visible from
the API. `calibration_probe.py` measures separation between positive and
negative fixtures on the same harness:

```bash
python tools/system1-laya/calibration_probe.py
```

Measured on `convaiinnovations/laya` (RTX 3090, bf16):

| Signal | Mean gap | Min/max separation | Verdict |
|---|---|---|---|
| `guard.jailbreak` | +0.97 | +0.87 | **reliable** — use as a gate |
| `triage.is_urgent` | +0.56 | +0.16 | **reliable** |
| `route.domain` | — | correct on coding fixtures | usable, low confidence values |
| `interaction.expects_response` | +0.38 | +0.08 | **usable** with a 0.3 cut-off |
| `route.needs_tools` | −0.01 | −0.01 | **do not gate on this** |
| completeness ("is the user done typing?") | +0.32 best | **+0.007** | **unusable** |

Two consequences, both deliberate:

1. **`needs_tools` is carried but never gated on.** On "Fetch the latest exchange
   rates from the API and save to disk" it reports 0.18 — lower than its own
   reading of a pure-knowledge question. Four rewordings of the question did not
   help. It is exposed as a hint only.

2. **Completeness and change-detection are not asked of the model at all.**
   Across four framings (noul, inverted noul, choice, score), finished messages
   and truncated drafts produce *overlapping* distributions — best min/max
   separation +0.007, against +0.87 for the shipped jailbreak preset. These
   questions moved to `System1ReflexGate` in `sharedLogic`, which answers them
   deterministically from sentence structure: exact, on-device, and free.

That split is the interesting result. "Is the user done?" reads like a semantic
question but is largely structural, and the structural answer is better than the
model's. The model earns its latency on the genuinely semantic question that
remains — *does this expect a response?* — where a bare "thanks, that worked"
scores 0.09 against 0.80 for a real request.

If turn-completeness is later wanted from the model, it needs fine-tuning on the
RLCD objective with app telemetry, not another prompt rewrite. Four were tried.

## Tests

```bash
python tools/system1-laya/test_runtime.py    # 20 policy tests, no model needed
python tools/system1-laya/test_service.py    # 14 integration checks, service must be up
```

`test_runtime.py` covers thresholds, disposition precedence and report shaping
against synthetic answer blobs. `test_service.py` hits a live service and
asserts behaviour that must hold for the layer to be worth its latency, plus a
round-trip latency budget.

The Kotlin client lives in
`android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/system1/`
and its fixtures are verbatim payloads from this service, so a rename on either
side of the contract fails a test.

## Layout

| File | Purpose |
|---|---|
| `server.py` | FastAPI app and endpoints |
| `start_server.py` | launcher: CLI args, warm-up, uvicorn |
| `runtime.py` | model runtime, thresholds, disposition policy, report shaping |
| `questions.py` | question sets and the unified state blob |
| `calibration_probe.py` | separation measurement behind the findings above |
| `test_runtime.py` / `test_service.py` | policy tests / integration tests |
