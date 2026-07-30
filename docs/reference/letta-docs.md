# Looking up Letta documentation

Letta's own docs are the authority on Letta behavior — agent memory, MemFS,
skills, schedules, channels, the CLI, and the App Server protocol this client
integrates against. **Look them up instead of guessing.** They are not vendored
into this repo (they change upstream and would go stale), so fetch on demand.

## The two things worth knowing

**1. There is a machine-readable index.** `https://docs.letta.com/llms.txt`
(~21KB) lists every page with a one-line description. Read it first when you do
not already know which page you need.

**2. Every page has a canonical markdown version.** Append `/index.md` to any
docs URL — no HTML parsing, no scraping:

```
https://docs.letta.com/quickstart      ->  https://docs.letta.com/quickstart/index.md
https://docs.letta.com/concepts/memfs  ->  https://docs.letta.com/concepts/memfs/index.md
```

The docs root is the one exception: it is `https://docs.letta.com/index.md`
(**not** `/index/index.md`, which 404s).

API reference pages follow the same `/api/.../index.md` pattern, but prefer our
own Kotlin models for wire shapes we actually consume — they are what this
client is built against.

## How to fetch

Any of these work; use whichever your harness supports:

```bash
curl -s https://docs.letta.com/llms.txt | less
curl -s https://docs.letta.com/platform/app-server/protocol-lifecycle/index.md
```

Claude Code and similar harnesses can use `WebFetch` on the same URLs. If you
have no network access at all, say so rather than guessing at Letta semantics.

## Page map (most relevant to this repo first)

Paths below are slugs — prefix with `https://docs.letta.com/` and suffix with
`/index.md`.

**App Server — the protocol this client speaks**
- `platform/app-server` — overview
- `platform/app-server/protocol-lifecycle` — connection, turns, frame lifecycle
- `platform/app-server/external-tools` — external tool-call round trip
- `platform/app-server/integration-patterns`
- `platform/app-server/quickstart`

**Core concepts behind what our UI renders**
- `concepts/memfs` — the memory filesystem behind core-memory blocks
- `concepts/conversations`
- `concepts/stateful-agents`
- `configuration/memory` — memory blocks
- `configuration/skills`, `configuration/schedules`, `configuration/subagents`
- `configuration/channels` (+ `channels/{custom,discord,signal,slack,telegram,whatsapp}`)
- `configuration/models`, `configuration/permissions`, `configuration/secrets`, `configuration/mods`

**CLI / harness**
- `platform/cli`, `platform/cli/reference`, `platform/cli/headless`, `platform/cli/slash-commands`
- `self-hosting`, `platform/computers`, `platform/computers/byom`

**SDK**
- `agent-sdk`, `agent-sdk/quickstart`, `agent-sdk/reference`
- `agent-sdk/remote-client`, `agent-sdk/remote-client/self-hosted`, `agent-sdk/deployment`

**Reference**
- `reference/changelog` — pin behavior to a version; useful when our pinned
  `@letta-ai/letta-code` differs from what the docs describe
- `reference/settings`, `reference/terminology`, `reference/troubleshooting`

**Getting oriented**
- `index`, `quickstart`, `handbook`, `handbook/setup`, `handbook/meet-your-agent`

## Caveat

The docs describe current upstream Letta. This client pins a specific
`@letta-ai/letta-code` version (see `CLAUDE.md`), so behavior can legitimately
differ. When a doc and our observed wire behavior disagree, trust the observed
behavior and check `reference/changelog` for when it changed.
