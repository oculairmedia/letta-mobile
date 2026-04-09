# Android Compose Development

This directory contains the active Android admin app.

## Prerequisites

- Android Studio with SDK Platform 35 and Build-Tools 34 installed
- Android Studio bundled JBR
- Android SDK path available for `local.properties`

## Local setup

Create `local.properties` from the example file:

```bash
cp local.properties.example local.properties
```

Set `JAVA_HOME` to Android Studio's bundled JBR before running Gradle:

```bash
export JAVA_HOME="/path/to/Android Studio/jbr"
```

## Common commands

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew installDebug
./gradlew detekt
```

## Troubleshooting

- If Gradle reports missing Android SDK components, install Platform 35 and Build-Tools 34 in Android Studio.
- If Gradle reports missing `JAVA_COMPILER`, make sure `JAVA_HOME` points to a full JDK/JBR, not a JRE.
- If Gradle cannot find the SDK, verify `local.properties` contains the correct `sdk.dir` for your machine.
