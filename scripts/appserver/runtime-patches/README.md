# letta-code runtime patches

`letta-code-patch-register.mjs` is passed to the Meridian App Server as
`NODE_OPTIONS=--import=file://…/letta-code-patch-register.mjs`; it registers
`letta-code-patch-loader.mjs`, which rewrites the deployed `letta.js` bundle at load time
(the `lcp-*` patches; the boot log reports `lcp-patches applied=N skipped=M`).

Moved on 2026-11-04 from the deprecated `letta-code-parallel` repo
(`oculairmedia/letta-code-admin-shim`, `admin-shim/scripts/`), byte-identical to its deployed
commit `6354a12`. The deployed host still loads the old path until `/etc/meridian/appserver.env`
is repointed and `meridian-appserver` restarted.

## Tests

The tests verify the patches against the deployed bundle (`LETTA_CLI_PATH_REAL`, else the newest
`/root/letta-code-*`). They need Node 24 and `tsx`:

```bash
cd scripts/appserver/runtime-patches
LETTA_CLI_PATH_REAL=/root/letta-code-0.34.14/node_modules/@letta-ai/letta-code/letta.js \
  node --import tsx/esm --test --test-concurrency=1 test/*.test.ts
```
