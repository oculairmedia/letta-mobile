# AGENTS

This is a Rive CLI project.

Work autonomously from the user's requested outcome. Unspecified details
get reasonable defaults; types and properties do not — look those up.

- `rive docs` — the index; read the topic that matches the task
- `rive schema <Type>` / `rive schema --search <text>` — types and properties

After every edit:

- `rive . --verify` — confirm the project compiles
- `rive inspect . --json` — inspect what actually got built

A clean verify/inspect is not enough. Read the JSON for what the request
called for. If it is missing or wrong, fix the project and verify again.
