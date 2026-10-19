# Release Gate Ledger

**Status:** Historical April 2026 REST/SSE run ledger. Its orchestrator,
`gates.yaml` and root REST CLI were removed on 2026-10-19 after verifying they
had no workflow callers. Policy and run tables below are frozen evidence, not
an active generator contract or the current required CI gate list. See
[RELEASE.md](RELEASE.md) for current validation and workflow links.

## Gate Policy

<!-- BEGIN AUTO-POLICY -->
| Gate | Level | Classification | Notes |
| --- | --- | --- | --- |
| verify-build | L0 | blocking | Compiles `:app:compileDebugKotlin`. |
| verify-unit-tests | L0 | blocking | Runs `:app:testDebugUnitTest`. |
| verify-device-ready | L1 | blocking | Installs the debug APK, injects auth, and opens the target conversation. |
| verify-sync | L3 | advisory | Requires a healthy hydrated conversation on device. Advisory reason: r6j3 — internal gate reliability pending; bootstrap-window flake tracked in letta-mobile-r6j3.1 |
| verify-stream | L3 | advisory | Starts a background streaming conversation POST, then asserts resume-stream receives SSE frames during the watch window. Advisory reason: r6j3 — internal gate reliability pending; release-path mismatch tracked in letta-mobile-50m6 |
<!-- END AUTO-POLICY -->

## Recent Runs

<!-- BEGIN AUTO-RUNS -->
| Timestamp | Base URL | Blocking pass | Blocking fail | Skipped | Report |
| --- | --- | ---: | ---: | ---: | --- |
| 2026-04-23T16-40-18Z | https://letta.oculair.ca | 3 | 0 | 0 | reports/verify-release-2026-04-23T16-40-18Z.md |
| 2026-04-23T16-03-03Z | https://letta.oculair.ca | 4 | 1 | 0 | reports/verify-release-2026-04-23T16-03-03Z.md |
| 2026-04-23T07-10-02Z | http://192.168.50.90:8289 | 2 | 0 | 3 | reports/verify-release-2026-04-23T07-10-02Z.md |
| 2026-04-23T06-44-37Z | http://192.168.50.90:8289 | 3 | 2 | 0 | reports/verify-release-2026-04-23T06-44-37Z.md |
| 2026-04-23T06-42-35Z | http://192.168.50.90:8289 | 2 | 1 | 2 | reports/verify-release-2026-04-23T06-42-35Z.md |
| 2026-04-23T06-37-48Z | http://192.168.50.90:8289 | 1 | 2 | 2 | reports/verify-release-2026-04-23T06-37-48Z.md |
<!-- END AUTO-RUNS -->

The recorded classifications apply to those historical runs only; current gates are maintained in the CI workflows.
