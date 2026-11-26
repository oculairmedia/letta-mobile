# letta-code runtime patches

`letta-code-patch-register.mjs` is passed to the Meridian App Server as
`NODE_OPTIONS=--import=file://…/letta-code-patch-register.mjs`; it registers
`letta-code-patch-loader.mjs`, which rewrites the deployed `letta.js` bundle at load time
(the `lcp-*` patches; the boot log reports `lcp-patches applied=N skipped=M`).

Moved on 2026-11-04 from the deprecated `letta-code-parallel` repo
(`oculairmedia/letta-code-admin-shim`, `admin-shim/scripts/`), byte-identical to its deployed
commit `6354a12`. The deployed host still loads the old path until `/etc/meridian/appserver.env`
is repointed and `meridian-appserver` restarted.

## Host install

Install both `.mjs` files from a main commit into an immutable directory and point `current` at it,
the same shape as wrapper releases; `NODE_OPTIONS` loads `…/runtime-patches/current/`:

```bash
REL=/opt/meridian/runtime-patches/$(git rev-parse --short=9 HEAD)
install -D -m 0644 -t "$REL" scripts/appserver/runtime-patches/letta-code-patch-{register,loader}.mjs
ln -sfn "$REL" /opt/meridian/runtime-patches/current
systemctl restart meridian-appserver   # then confirm `lcp-patches applied=N skipped=0`
```

## Tests

The tests verify the patches against the deployed bundle (`LETTA_CLI_PATH_REAL`, else the newest
`/root/letta-code-*`). They need Node 24 and `tsx`:

```bash
cd scripts/appserver/runtime-patches
LETTA_CLI_PATH_REAL=/root/letta-code-<version>/node_modules/@letta-ai/letta-code/letta.js \
  node --import tsx/esm --test --test-concurrency=1 test/*.test.ts
```

## Host-neutral agent store

Agent state replicates between nodes in both directions, so these patches make the local store
write the same bytes on every machine, with no translation step:

| Patch | Effect |
| --- | --- |
| `lcp-conversation-uuid` | `LocalStore.nextConversationId` mints `local-conv-<uuid>` instead of a backend-wide counter. |
| `lcp-ui-msg-uuid` | `nextLocalMessageId` mints `ui-msg-<uuid>`. Readers match ids by equality or by the `ui-msg-` prefix; the only numeric parser, in the pre-pi transcript migration, reads a uuid suffix as 0. |
| `lcp-memory-lf` | Every memory-repo prepare sets `core.autocrlf=false` and, if the repo has no `.gitattributes`, writes `* text=auto eol=lf` and commits it once (new repos carry it in the init commit). Git failures are logged, never fatal. |
| `lcp-session-cwd` | The `messages.jsonl` session line records `LETTA_WORKSPACE_ID` instead of the App Server's absolute cwd, and leaves `cwd` out when the variable is unset. |
| `lcp-agent-description` | The default agent description is `Letta Code agent`, without the creating host's path. |
| `lcp-memory-dir-token` | The agent-info reminder shows the literal `$MEMORY_DIR` instead of this node's absolute memory root. |

`LETTA_WORKSPACE_ID` (optional) is a host-neutral name for the node's workspace, set in the App
Server environment next to `NODE_OPTIONS`. They add six to the boot count. The tests are in
`test/patch-loader-host-neutral.test.ts`.
