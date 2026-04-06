# Letta Mobile Project Handoff

## Project Overview

React Native mobile app for Letta AI agents, built with Expo and TypeScript.

**Location:** `E:/PROJECTS/letta-mobile`

**Tech Stack:**
- React Native 0.79.3 / Expo 53
- TypeScript
- Zustand (state management)
- TanStack React Query (server state)
- @letta-ai/letta-client@1.10.2 (Letta SDK)

---

## How to Run

```bash
cd E:/PROJECTS/letta-mobile

# Install dependencies
yarn install

# Create local.properties for Android SDK
echo "sdk.dir=C:/Users/Emmanuel/AppData/Local/Android/Sdk" > android/local.properties

# Start Metro with LAN access
npx expo start --host lan

# Build and run Android (separate terminal)
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" npx expo run:android
```

**ADB Setup:**
- Device: Pixel 2 XL (USB connected)
- ADB TCP enabled: `adb tcpip 5555`
- Device IP: `192.168.50.202`
- ADB reverse for Metro: `adb reverse tcp:8081 tcp:8081`

---

## Key Architecture

| Directory | Purpose |
|---|---|
| `/app/screens/` | Screen components |
| `/app/hooks/` | React Query hooks for API calls |
| `/app/providers/` | Context providers (Letta client, Query) |
| `/app/stores/` | Zustand stores (config persistence) |
| `/app/components/` | Reusable UI components |

**Important Files:**
- `app/hooks/use-agents.tsx` - Fetches agent list
- `app/hooks/use-mcp.ts` - MCP server/tools queries
- `app/screens/MCPScreen.tsx` - MCP server management
- `app/screens/AgentListScreen.tsx` - Agent list
- `app/providers/LettaProvider.tsx` - Letta client setup
- `app/stores/lettaConfigStore.ts` - Server config (cloud/selfhosted)

---

## Changes Made This Session

1. **Fixed agent memory blocks not showing:**
   - Updated `use-agents.tsx` to include `agent.blocks`, `agent.tools`, `agent.tags` in API request
   - Letta API requires explicit `include` parameter now

2. **Enhanced MCP screen:**
   - Added tool count badge per server
   - Added expandable "Show Tools" section with tool names/descriptions
   - Pull-to-refresh now updates both servers and tools

3. **Removed card drop shadows:**
   - Cleaned up `Card.tsx` - removed shadow/elevation styles

---

## Android Studio MCP Plugin

We set up the Android Tools MCP plugin for potential agent integration:
- **Plugin:** `C:\Users\Emmanuel\android-tools-mcp-0.1.0.zip`
- **Bridge script:** `U:\letta-mobile\plugins\android-studio-mcp.py`
- **Endpoint:** `http://192.168.50.247:24602` (via netsh port proxy)
- Server runs on port 24601 when Android Studio has a project open

---

## Current Backend Config

The app points to: `https://letta2.oculair.ca`

---

## Known Issues / Next Steps

- Some image URLs from `app.letta.com` fail to load (warnings in logs)
- Package versions slightly behind recommended Expo versions
- MCP client integration with Letta server still needs work (was exploring SSE endpoint access)


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
