# On-device App Server: model ids and test-agent cleanup

The Local runtime runs letta-code's App Server inside the app (`files/embedded-lettacode/`). Its
agents, conversations and agent memory live in `files/embedded-lettacode/local-backend/`, which an
app reinstall does not reset (only Room is).

## Model ids (letta-mobile-by4vm.121.1)

Agents on the device use `lmstudio/<model>` handles on the runtime's `lc-lmstudio` provider.
letta-code 0.34.14 resolves such a handle by discovery: it strips the leading `lmstudio/` once
(`google/gemma-3n-E2B-it-litert-lm` keeps its slash) and looks that id up in what the endpoint
lists at `GET <base>/api/v0/models`, else `GET <base>/v1/models`. A `models[]` array in
`providers/auth.json` is not read for lmstudio. So every endpoint behind `lc-lmstudio` must list
`lettaCodeLookupId(handle)` (`EmbeddedLettaCodeModelSelection.kt`):

- the on-device bridge (`OnDeviceOpenAiBridge`) serves exactly that id;
- a model server named in Settings must list the "Model server model" id (minus `lmstudio/`);
- `LocalAppServerDeviceSmokeTest`'s `FakeOpenAiServer` and the CI `embedded-runtime-node18` fake
  (`scripts/embedded-runtime/fake-llm.cjs`) advertise ids the same way, with a slash in the model
  id, so a change on either side fails a test instead of a phone (`Unknown model … for provider
  "lmstudio"`).

## Smoke-test agents (letta-mobile-by4vm.121.2)

`LocalAppServerDeviceSmokeTest` names its agents `device-smoke-<millis>`. After the turn (pass or
fail) it deletes every `device-smoke-*` agent through `agent_delete` (which also removes their
conversations) and fails if `agent_list` still returns one.

Agents left by runs before that change stay in the store and are listed in the app. Remove them
once with [`scripts/dev/device-clean-local-backend.sh`](../../scripts/dev/device-clean-local-backend.sh):

```bash
adb shell am force-stop com.letta.mobile.dev        # the script refuses while the app runs
scripts/dev/device-clean-local-backend.sh            # dry run: lists what would be removed
scripts/dev/device-clean-local-backend.sh --yes      # deletes
```

It removes `local-backend/{agents,app-agents,conversations,memfs,model-cache}` (all local agents
and their history; `app-agents/` and `model-cache/` are dead pre-W5 directories, no model weights). It keeps the downloaded models (`files/embedded-lettacode/models/`), the runtime,
`providers/` and `ws-token` (rewritten on the next dial) and all other app data. `--serial` /
`ANDROID_SERIAL` pick the device, `--package` / `PKG` the application id.
