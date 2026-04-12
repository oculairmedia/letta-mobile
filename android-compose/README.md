# Android Compose App

This directory contains the production Letta Mobile Android app.

## Modules

| Module         | Purpose                                                   |
| -------------- | --------------------------------------------------------- |
| `app`          | Screens, navigation, Hilt wiring, Android entrypoints     |
| `core`         | Data models, Ktor API client, Room, repositories, mappers |
| `designsystem` | Shared Compose components, theme, dialogs, `LettaIcons`   |
| `chat`         | Streaming chat client and chat-domain support             |

## Prerequisites

- Android Studio with Android SDK Platform 35 and Build-Tools 34 installed
- A full JDK/JBR via Android Studio or Java 17
- `local.properties` pointing at your Android SDK

## Local setup

Create `local.properties` from the example file and set `sdk.dir` for your machine:

```bash
cp local.properties.example local.properties
```

Set `JAVA_HOME` before running Gradle:

```bash
export JAVA_HOME="/path/to/Android Studio/jbr"
```

## Common commands

Run Gradle commands from this directory.

```bash
./gradlew --no-daemon :app:compileDebugKotlin
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon installDebug
./gradlew --no-daemon detekt
```

## Recommended verification flow

For normal application changes:

```bash
./gradlew --no-daemon :app:compileDebugKotlin
./gradlew --no-daemon :app:testDebugUnitTest
```

For shared model / repository / serialization changes, it is safer to verify the full stack in order:

```bash
./gradlew --no-daemon clean :core:compileDebugKotlin
./gradlew --no-daemon :designsystem:compileDebugKotlin
./gradlew --no-daemon :app:compileDebugKotlin
./gradlew --no-daemon :app:testDebugUnitTest
```

Run those commands sequentially. KSP state can become unreliable if you try to overlap Gradle work.

## Troubleshooting

- If Gradle reports missing Android SDK components, install Platform 35 and Build-Tools 34 in Android Studio.
- If Gradle reports missing `JAVA_COMPILER`, make sure `JAVA_HOME` points to a full JDK/JBR, not a JRE.
- If Gradle cannot find the SDK, verify `local.properties` contains the correct `sdk.dir` for your machine.
- If Kotlin or KSP behaves inconsistently after dependency or generated-code changes, rerun with `clean` before compiling again.

### Kotlin incremental compilation errors

If you see `.tab` file corruption errors (`source-to-classes.tab already registered`, `lookups.tab`, `class-attributes.tab`, or missing backup file errors), run:

```bash
./gradlew cleanKotlinIC
```

This wipes Kotlin IC caches from all modules without nuking the entire `build/` tree. The next build will recompile from scratch and rebuild clean caches.

If corruption persists, kill stale daemons and do a full clean:

```bash
./gradlew --stop
pkill -f kotlin-daemon 2>/dev/null || true
./gradlew clean
```

See `gradle.properties` for IC hardening settings that reduce these failures.
