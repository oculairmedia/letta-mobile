# Letta Mobile

Native Android client for [Letta](https://github.com/letta-ai/letta), built with Kotlin and Jetpack Compose.

`main` now represents the Kotlin app. The previous React Native app has been retired.

<a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/oculairmedia/letta-mobile/releases" target="_blank"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="48"></a>

## What this app includes

- Agent management: create, edit, clone, import, delete, and organize agents
- Rich chat: tool outputs, code blocks, reasoning display, chat backgrounds, and shared transitions
- Memory administration: core memory, archival passages, block library, and archive attachment flows
- Admin tooling: models, providers, tools, MCP servers, identities, folders, groups, schedules, jobs, and runs
- Native Android UI polish with Material 3 and a centralized Lucide icon system

## Project layout

The Android project lives in `android-compose/`.

| Path                            | Purpose                                                  |
| ------------------------------- | -------------------------------------------------------- |
| `android-compose/app/`          | Android app module: screens, navigation, ViewModels, DI  |
| `android-compose/core/`         | API client, Room database, repositories, models          |
| `android-compose/designsystem/` | Reusable Compose UI, theming, icons, dialogs, markdown   |
| `android-compose/chat/`         | Streaming chat client primitives and chat-domain support |

## Quick start

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full contributor setup. The shortest path to a local build is:

```bash
cd android-compose
cp local.properties.example local.properties
export JAVA_HOME="/path/to/Android Studio/jbr"
./gradlew :app:assembleDebug
```

## Recommended checks

Run these from `android-compose/` before pushing changes:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
```

## CI

GitHub Actions builds and tests `android-compose/` on pushes and pull requests targeting `main`.

## Development notes

- This repo uses `bd` (beads) for task tracking.
- The included pre-push hook compiles `:app:compileDebugKotlin` from `android-compose/`.
- If Gradle or KSP gets into a bad state locally, rerun with a clean build from `android-compose/`.
