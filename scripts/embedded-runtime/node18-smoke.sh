#!/usr/bin/env bash
# embedded-runtime-node18 (letta-mobile-by4vm.110.3): proves the letta-code tree the APK ships
# boots on the Node the device ships (nodejs-mobile Node 18.20.4), so a bad letta-code bump fails
# in CI instead of on a phone. Runs against the tree PrepareEmbeddedLettaCodeAssetsTask staged
# (`:app:prepareEmbeddedLettaCodeAssets -PembedLettaCodeAssets=true`), with the shipped preloads in
# the device order (node18-compat.cjs FIRST) behind android-platform-emulation.cjs, against
# fake-llm.cjs. Nothing touches the network beyond 127.0.0.1.
#
#   1. budget    gzip'd staged tree <= EMBEDDED_BUDGET_MB (raw size is not budgeted)
#   2. headless  the current Android mode: stream-json over stdin, one Bash tool turn -> result
#   3. server    `letta server` through the shared LocalAppServerHost (appserver-cli
#                app-server-serve: loopback, capability token), one Bash tool turn on the agent
#                from step 2 driven by the shared Kotlin AppServerClient (app-server-smoke), then
#                one turn WITH AN IMAGE: letta-code prepares images in a child node it launches with
#                process.execPath, which on the phone is the app process (emulated, see
#                android-platform-emulation.cjs), so this fails unless the shipped preloads relaunch
#                helpers on the packaged node CLI (LETTA_CHILD_NODE_PATH, as the device sets it)
#   4. compact   a compact agent (tag profile:compact, letta-mobile-by4vm.121.16) driven by the
#                Kotlin client gets the shared small toolset (CompactAgentProfile.CLIENT_TOOLS,
#                no Bash) and its Read call round-trips: model -> letta-code runs Read -> result
#                back to the model -> final answer
#
# The model is resolved the way the device resolves it (letta-mobile-by4vm.121.1): the agent handle
# is `lmstudio/<vendor>/<model>`, providers/auth.json carries no models[], and the fake serves the
# id the on-device bridge serves (the handle minus `lmstudio/`, lettaCodeLookupId in the app) only on
# /v1/models, so a letta-code bump that changes lmstudio model discovery fails here.
#
# On failure the first missing-API style error from the runtime's stderr is printed as a GitHub
# error annotation and, with the stderr tail, into $GITHUB_STEP_SUMMARY.
#
# usage: node18-smoke.sh <staged nodejs-project dir> <appserver-cli installDist dir>
# env:   NODE18 (node binary, default `node`), EXPECT_NODE_VERSION (default: libnodeVersion from
#        the pin file), EMBEDDED_BUDGET_MB (default 28), JAVA (default `java`), WORK (scratch dir)
set -euo pipefail

PROJECT=$(cd "${1:?staged nodejs-project dir}" && pwd)
CLI_HOME=$(cd "${2:?appserver-cli installDist dir}" && pwd)
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
PINS="$REPO/android-compose/letta-code-runtime/letta-code-runtime.properties"
NODE18=${NODE18:-node}
JAVA=${JAVA:-java}
BUDGET_MB=${EMBEDDED_BUDGET_MB:-28}
EXPECT_NODE_VERSION=${EXPECT_NODE_VERSION:-$(sed -n 's/^libnodeVersion=//p' "$PINS")}
WORK=${WORK:-$(mktemp -d)}
mkdir -p "$WORK"
LETTA_JS="$PROJECT/node_modules/@letta-ai/letta-code/letta.js"
LLM_PORT=${FAKE_LLM_PORT:-4711}
MODEL=lmstudio/ci/node18-model
SERVED_MODEL=${MODEL#lmstudio/} # what OnDeviceOpenAiBridge advertises for this handle
SUMMARY=${GITHUB_STEP_SUMMARY:-/dev/null}
PIDS=()

cleanup() { for pid in "${PIDS[@]}"; do kill "$pid" 2>/dev/null || true; done; }
trap cleanup EXIT

summary() { printf '%s\n' "$@" >> "$SUMMARY"; }

# First line that names the API the runtime was missing, else the first error-looking line.
first_error() {
  local line
  line=$(cat "$@" 2>/dev/null | grep -m1 -E "is not a function|is not defined|is not a constructor|Cannot find module|ERR_[A-Z_]+|SyntaxError|Unexpected token|Invalid regular expression" ||
    cat "$@" 2>/dev/null | grep -m1 -i "error" || true)
  echo "${line:-(no error line in the logs)}"
}

fail() { # fail <phase> <message> <log files...>
  local phase=$1 message=$2; shift 2
  local first; first=$(first_error "$@")
  echo "::error title=embedded-runtime-node18 ${phase}::${message}: ${first}"
  summary "### embedded-runtime-node18: ${phase} FAILED" "" "${message}" "" "First error: \`${first}\`" "" '```'
  for log in "$@"; do [[ -f "$log" ]] && { echo "--- $log (tail)"; tail -n 40 "$log"; } | tee -a "$SUMMARY"; done
  summary '```'
  exit 1
}

phase() { echo "::group::$1"; }
endphase() { echo "::endgroup::"; }

# --- 0. preconditions --------------------------------------------------------------------------
actual_node=$("$NODE18" --version)
[[ "$actual_node" = "$EXPECT_NODE_VERSION" ]] ||
  { echo "::error::node is $actual_node, the device ships $EXPECT_NODE_VERSION"; exit 1; }
[[ -f "$LETTA_JS" ]] || { echo "::error::letta.js missing at $LETTA_JS (stage the tree first)"; exit 1; }
PRELOADS=("$HERE/android-platform-emulation.cjs")
for preload in node18-compat.cjs regexp-polyfill.cjs android-network-polyfill.cjs child-node.cjs; do
  [[ -f "$PROJECT/$preload" ]] || { echo "::error::shipped preload $preload missing from the staged tree"; exit 1; }
  "$NODE18" --check "$PROJECT/$preload"
  PRELOADS+=("$PROJECT/$preload")
done
REQUIRES=()
for preload in "${PRELOADS[@]}"; do REQUIRES+=("--require" "$preload"); done
echo "node $actual_node, letta-code $(sed -n 's/^lettaCodeVersion=//p' "$PINS"), preloads: ${PRELOADS[*]##*/}"

# --- 1. size budget ----------------------------------------------------------------------------
phase "size budget"
tar -C "$(dirname "$PROJECT")" -czf "$WORK/staged.tar.gz" "$(basename "$PROJECT")"
gz_bytes=$(stat -c %s "$WORK/staged.tar.gz")
raw_kb=$(du -sk --apparent-size "$PROJECT" | cut -f1)
gz_mb=$(awk -v b="$gz_bytes" 'BEGIN { printf "%.1f", b / 1048576 }')
echo "staged tree: ${gz_mb} MiB gzip (budget ${BUDGET_MB} MiB), $((raw_kb / 1024)) MiB raw (not budgeted)"
summary "### embedded-runtime-node18" "" "| | |" "|---|---|" "| node | $actual_node |" \
  "| staged tree (gzip) | ${gz_mb} MiB of ${BUDGET_MB} MiB |" "| staged tree (raw) | $((raw_kb / 1024)) MiB |"
rm -f "$WORK/staged.tar.gz"
endphase
if [[ "$gz_bytes" -gt $((BUDGET_MB * 1048576)) ]]; then
  echo "::error title=embedded-runtime-node18 budget::staged tree is ${gz_mb} MiB gzip, over the ${BUDGET_MB} MiB budget"
  exit 1
fi

# --- shared sandbox: throwaway HOME + backend, lmstudio provider on the fake LLM ---------------
mkdir -p "$WORK/home" "$WORK/backend/providers" "$WORK/tmp" "$WORK/work"
cat > "$WORK/backend/providers/auth.json" <<EOF
{ "version": 1, "providers": { "lc-lmstudio": {
  "id": "local-provider-lc-lmstudio", "name": "lc-lmstudio", "provider_type": "lmstudio",
  "provider_category": "byok", "auth": { "type": "api", "key": "lm-studio" },
  "base_url": "http://127.0.0.1:$LLM_PORT/v1" } } }
EOF
# The device environment (AndroidEmbeddedLettaCodeLaunch) minus the Android-only bridge/tool paths.
# No LETTA_CODE_VISION_MODEL_IDS match for the served model: vision must come from the fake's
# /api/v0/models, as it does from the on-device bridge (letta-mobile-by4vm.121).
export HOME="$WORK/home" TMPDIR="$WORK/tmp" TMP="$WORK/tmp" TEMP="$WORK/tmp" SHELL=/bin/sh NO_COLOR=1 \
  LETTA_LOCAL_BACKEND_EXPERIMENTAL=1 LETTA_LOCAL_BACKEND_DIR="$WORK/backend" LETTA_LOCAL_BACKEND_EXECUTOR=pi \
  UV_USE_IO_URING=0 UV_THREADPOOL_SIZE=2 LETTA_COMPAT_TRACE=1 \
  LMSTUDIO_BASE_URL="http://127.0.0.1:$LLM_PORT/v1" LMSTUDIO_API_KEY=lm-studio \
  NODE_OPTIONS="--max-old-space-size=384 --max-semi-space-size=16"
NODE18_BIN=$(dirname "$(command -v "$NODE18")")
export PATH="$NODE18_BIN:$PATH"
# What AndroidEmbeddedLettaCodeLaunch sets: the node letta-code starts its helpers on (child-node.cjs).
LETTA_CHILD_NODE_PATH=$(command -v "$NODE18")
export LETTA_CHILD_NODE_PATH

FAKE_LLM_LOG="$WORK/llm.log" FAKE_LLM_PORT=$LLM_PORT FAKE_LLM_MODEL=$SERVED_MODEL FAKE_LLM_READ_FILE="$WORK/compact-note.txt" "$NODE18" "$HERE/fake-llm.cjs" 2> "$WORK/llm.err" &
PIDS+=($!)
for _ in $(seq 50); do grep -q "fake llm on" "$WORK/llm.err" 2>/dev/null && break; sleep 0.1; done

tool_runs() { grep -c 'NODE18-TOOL-42' "$WORK/llm.log" 2>/dev/null || true; }
image_requests() { grep -c '"images":[1-9]' "$WORK/llm.log" 2>/dev/null || true; }
# 4x4 PNG: the app sends photos as a base64 image part next to the text.
base64 -d > "$WORK/smoke.png" <<< 'iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAEElEQVR4nGP4z8AARwzEcQCukw/x0F8jngAAAABJRU5ErkJggg=='

# --- 2. headless stream-json (the current Android mode) ----------------------------------------
phase "headless stream-json on node $actual_node"
turn='{"type":"user","message":{"role":"user","content":"run the smoke tool","otid":"otid-node18-1"}}'
set +e
# shellcheck disable=SC2094 # the reader only polls the file the turn writes, by design
( printf '%s\n' "$turn"
  # Keep stdin open like the Android bridge until the turn's result arrives (or 120 s).
  for _ in $(seq 240); do grep -q '"type":"result"' "$WORK/headless.jsonl" 2>/dev/null && break; sleep 0.5; done ) |
  (cd "$WORK/work" && "$NODE18" --max-old-space-size=384 --max-semi-space-size=16 "${REQUIRES[@]}" "$LETTA_JS" \
    --backend local --model "$MODEL" --new-agent \
    --input-format stream-json --output-format stream-json --permission-mode unrestricted) \
  > "$WORK/headless.jsonl" 2> "$WORK/headless.err"
headless_exit=$?
set -e
grep '\[node18-compat\]' "$WORK/headless.err" || true
endphase
result=$("$NODE18" -e '
  const lines = require("fs").readFileSync(process.argv[1], "utf8").split("\n").filter(Boolean);
  let agent = "", conversation = "", result = "";
  for (const line of lines) {
    let o; try { o = JSON.parse(line); } catch { continue; }
    agent = agent || o.agent_id || "";
    conversation = conversation || o.conversation_id || "";
    if (o.type === "result") result = o.subtype || "result";
  }
  console.log([agent, conversation, result].join(" "));' "$WORK/headless.jsonl")
read -r AGENT_ID CONVERSATION_ID headless_result <<< "$result"
echo "headless: exit=$headless_exit result=${headless_result:-none} agent=${AGENT_ID:-?} conversation=${CONVERSATION_ID:-?} tool runs=$(tool_runs)"
[[ "$headless_result" = "success" ]] || fail headless "headless turn did not end in result/success (exit $headless_exit)" "$WORK/headless.err" "$WORK/headless.jsonl"
[[ "$(tool_runs)" -ge 1 ]] || fail headless "the Bash tool call never returned its output to the model" "$WORK/headless.err" "$WORK/llm.log"
[[ -n "$AGENT_ID" && -n "$CONVERSATION_ID" ]] || fail headless "no agent_id/conversation_id in the stream-json output" "$WORK/headless.jsonl"
summary "| headless stream-json | result/success, Bash tool ran |"

# --- 4a. a compact agent, created the way the app's compact profile creates one -----------------
# Headless has no client policy, so --tools stands in for it here; step 4b proves the Kotlin client
# applies the same set on its own.
echo "NODE18-NOTE-7" > "$WORK/compact-note.txt"
phase "compact agent (headless create) on node $actual_node"
set +e
( printf '%s\n' '{"type":"user","message":{"role":"user","content":"read my note","otid":"otid-node18-compact"}}'
  for _ in $(seq 240); do grep -q '"type":"result"' "$WORK/compact.jsonl" 2>/dev/null && break; sleep 0.5; done ) |
  (cd "$WORK/work" && "$NODE18" --max-old-space-size=384 --max-semi-space-size=16 "${REQUIRES[@]}" "$LETTA_JS" \
    --backend local --model "$MODEL" --new-agent --tags profile:compact --tools Memory,Read,device_action \
    --input-format stream-json --output-format stream-json --permission-mode unrestricted) \
  > "$WORK/compact.jsonl" 2> "$WORK/compact.err"
set -e
endphase
read -r COMPACT_AGENT_ID COMPACT_CONVERSATION_ID < <("$NODE18" -e '
  let a = "", c = "";
  for (const line of require("fs").readFileSync(process.argv[1], "utf8").split("\n")) {
    try { const o = JSON.parse(line); a = a || o.agent_id || ""; c = c || o.conversation_id || ""; } catch {}
  }
  console.log(a, c);' "$WORK/compact.jsonl")
[[ -n "$COMPACT_AGENT_ID" && -n "$COMPACT_CONVERSATION_ID" ]] || fail compact "no compact agent was created" "$WORK/compact.err" "$WORK/compact.jsonl"
note_reads() { grep -c 'NODE18-NOTE-7' "$WORK/llm.log" 2>/dev/null || true; }

# --- 3. App Server through LocalAppServerHost, driven by the Kotlin client --------------------
phase "App Server (LocalAppServerHost + app-server-smoke) on node $actual_node"
cli() { "$JAVA" -cp "$CLI_HOME/lib/*" com.letta.mobile.appservercli.Main "$@"; }
LETTA_ARGS=(--letta-arg=--max-old-space-size=384 --letta-arg=--max-semi-space-size=16)
for arg in "${REQUIRES[@]}" "$LETTA_JS"; do LETTA_ARGS+=("--letta-arg=$arg"); done
(cd "$WORK/work" && exec "$JAVA" -cp "$CLI_HOME/lib/*" com.letta.mobile.appservercli.Main app-server-serve \
  --backend-dir "$WORK/backend" --letta-command "$(command -v "$NODE18")" "${LETTA_ARGS[@]}") \
  > "$WORK/server.out" 2> "$WORK/server.err" &
SERVER_PID=$!
PIDS+=("$SERVER_PID")
url=""
for _ in $(seq 120); do
  url=$(sed -n 's/^\[app-server\] ready at \(ws:\/\/[^ ]*\).*/\1/p' "$WORK/server.out" | head -n1)
  [[ -n "$url" ]] || ! kill -0 "$SERVER_PID" 2>/dev/null || { sleep 0.5; continue; }
  break
done
[[ -n "$url" ]] || { endphase; fail server "letta server never became ready" "$WORK/server.err" "$WORK/server.out"; }
before=$(tool_runs)
set +e
APP_SERVER_TEST_TOKEN=$(cat "$WORK/backend/ws-token") cli app-server-smoke --url "$url" \
  --agent "$AGENT_ID" --conversation "$CONVERSATION_ID" --message "run the smoke tool again" \
  --timeout-ms 120000 > "$WORK/smoke.out" 2> "$WORK/smoke.err"
smoke_exit=$?
set -e
cat "$WORK/smoke.out"
images_before=$(image_requests)
set +e
APP_SERVER_TEST_TOKEN=$(cat "$WORK/backend/ws-token") cli app-server-smoke --url "$url" \
  --agent "$AGENT_ID" --conversation "$CONVERSATION_ID" --message "what is in this picture?" \
  --image "$WORK/smoke.png" --timeout-ms 120000 > "$WORK/image.out" 2> "$WORK/image.err"
image_exit=$?
set -e
cat "$WORK/image.out"
# 4b. The compact agent through the Kotlin client: it learns the agent is compact from runtime_start
# and offers only CompactAgentProfile.CLIENT_TOOLS.
reads_before=$(note_reads)
compact_from=$(wc -l < "$WORK/llm.log")
set +e
APP_SERVER_TEST_TOKEN=$(cat "$WORK/backend/ws-token") cli app-server-smoke --url "$url" \
  --agent "$COMPACT_AGENT_ID" --conversation "$COMPACT_CONVERSATION_ID" --message "read my note again" \
  --timeout-ms 120000 > "$WORK/compact.out" 2> "$WORK/compact-smoke.err"
compact_exit=$?
set -e
cat "$WORK/compact.out"
grep '\[node18-compat\]' "$WORK/server.err" || true
kill "$SERVER_PID" 2>/dev/null || true
endphase
[[ "$smoke_exit" -eq 0 ]] || fail server "app-server-smoke exited $smoke_exit" "$WORK/server.err" "$WORK/smoke.err" "$WORK/smoke.out"
grep -q '^\[tool-return\]' "$WORK/smoke.out" || fail server "no tool return reached the Kotlin client" "$WORK/server.err" "$WORK/smoke.out"
{ grep -q '^\[lifecycle\] Completed' "$WORK/smoke.out" && grep -q '"stop_reason":"end_turn"' "$WORK/smoke.out"; } ||
  fail server "the turn never completed with end_turn" "$WORK/server.err" "$WORK/smoke.out"
[[ "$(tool_runs)" -gt "$before" ]] || fail server "the Bash tool output never reached the model" "$WORK/server.err" "$WORK/llm.log"
[[ "$image_exit" -eq 0 ]] || fail image "the image turn (app-server-smoke --image) exited $image_exit" "$WORK/server.err" "$WORK/image.err" "$WORK/image.out"
{ grep -q '^\[lifecycle\] Completed' "$WORK/image.out" && ! grep -q 'Failed to prepare image' "$WORK/image.out"; } ||
  fail image "the image turn did not complete: $(grep -m1 '^\[lifecycle\] Failed' "$WORK/image.out" || echo 'no Completed terminal')" "$WORK/image.out" "$WORK/server.err"
[[ "$(image_requests)" -gt "$images_before" ]] || fail image "the image never reached the model" "$WORK/llm.log" "$WORK/server.err"
[[ "$compact_exit" -eq 0 ]] || fail compact "the compact turn (app-server-smoke) exited $compact_exit" "$WORK/server.err" "$WORK/compact-smoke.err" "$WORK/compact.out"
compact_tools=$(tail -n +"$((compact_from + 1))" "$WORK/llm.log" | "$NODE18" -e '
  const allowed = new Set(["Memory", "Read", "device_action"]);
  const lines = require("fs").readFileSync(0, "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l));
  const names = [...new Set(lines.flatMap((l) => l.toolNames))];
  const bad = names.filter((n) => !allowed.has(n));
  console.log(names.join(",") + (bad.length || !names.includes("Read") ? " BAD" : ""));')
echo "compact agent tools offered: $compact_tools"
[[ "$compact_tools" != *BAD* ]] || fail compact "the compact agent was offered [$compact_tools], not the shared small toolset" "$WORK/llm.log" "$WORK/compact.out"
[[ "$(note_reads)" -gt "$reads_before" ]] || fail compact "the Read result never reached the model" "$WORK/llm.log" "$WORK/compact.out"
grep -q '^\[tool-return\]' "$WORK/compact.out" || fail compact "no tool return reached the Kotlin client" "$WORK/compact.out" "$WORK/server.err"
summary "| App Server (Kotlin client) | runtime_start, Bash tool, end_turn; image turn prepared on the child node |" \
  "| compact agent (Kotlin client) | tools offered: $compact_tools; Read round trip to a final answer |" \
  "| node18-compat shims hit | $(grep -ho '\[node18-compat\] hit [^ ]*' "$WORK/headless.err" "$WORK/server.err" | awk '{print $3}' | sort -u | paste -sd' ' -) |"
echo "embedded-runtime-node18: OK"
