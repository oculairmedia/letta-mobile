# Contributing

The active Android app lives in `android-compose/`.

## Local Android setup

1. Create `android-compose/local.properties` with your Android SDK path.
2. Point `JAVA_HOME` at Android Studio's bundled JBR.

Example on Windows Git Bash:

```bash
cat > android-compose/local.properties <<'EOF'
sdk.dir=C:\Users\<you>\AppData\Local\Android\Sdk
EOF

export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

Example on Linux:

```bash
cat > android-compose/local.properties <<'EOF'
sdk.dir=/usr/lib/android-sdk
EOF

export JAVA_HOME="/path/to/android-studio/jbr"
```

## Recommended build checks

Run these from `android-compose/` before pushing:

```bash
JAVA_HOME="/path/to/Android Studio/jbr" ./gradlew --no-daemon :app:compileDebugKotlin
JAVA_HOME="/path/to/Android Studio/jbr" ./gradlew --no-daemon :app:testDebugUnitTest
```

If you want a device build:

```bash
JAVA_HOME="/path/to/Android Studio/jbr" ./gradlew --no-daemon installDebug
```

## Optional pre-push hook

The repo includes a reusable hook template at `.githooks/pre-push`.

Install it manually if you want local push protection:

```bash
./scripts/install-hooks.sh
```

The hook runs `:app:compileDebugKotlin` from `android-compose/` with `--no-daemon` and fails the push if Kotlin compilation breaks.
