# Letta Mobile — Kotlin Compose

## Project Overview

Native Android app for Letta AI agents, built with Kotlin and Jetpack Compose.

**Location:** `/opt/stacks/letta-mobile`
**App directory:** `android-compose/`

**Tech Stack:**
- Kotlin / Jetpack Compose
- Hilt (dependency injection)
- Room (local database)
- Ktor (HTTP client)
- Material 3 + Lucide icons

---

## How to Build

```bash
cd android-compose
cp local.properties.example local.properties
export JAVA_HOME="/path/to/jdk-26"
./gradlew :app:assembleDebug
```

---

## Key Architecture

| Module | Purpose |
|--------|---------|
| `android-compose/core/` | Data layer — API client, Room DB, repositories, models |
| `android-compose/designsystem/` | Reusable Compose components, theming, LettaIcons |
| `android-compose/app/` | UI screens, ViewModels, navigation, Hilt DI |

**Important Directories:**
- `designsystem/src/main/java/com/letta/mobile/ui/a2ui/README.md` - A2UI renderer and catalog authoring guide
- `app/src/main/java/com/letta/mobile/ui/screens/` — All screen composables
- `app/src/main/java/com/letta/mobile/ui/navigation/` — Nav graph, shared transitions
- `core/src/main/java/com/letta/mobile/data/api/` — Letta API client and endpoint interfaces
- `core/src/main/java/com/letta/mobile/data/repository/` — Repository implementations
- `designsystem/src/main/java/com/letta/mobile/ui/` — Components, icons, theme

---

## Backend Config

The app points to: `https://letta2.oculair.ca`

---

## Recommended Build Checks

```bash
export JAVA_HOME=/usr/lib/jvm/jdk-26
cd android-compose
./gradlew --no-daemon :app:compileDebugKotlin
./gradlew --no-daemon :app:testDebugUnitTest
```

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:ca08a54f -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

## Session Completion

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   bd dolt push
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds
<!-- END BEADS INTEGRATION -->
