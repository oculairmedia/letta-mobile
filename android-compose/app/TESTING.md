# App snapshot testing

Run commands from `android-compose/`. The former `RunBlockScreenshotTest` was
removed during the shared UI cut; a Gradle filter naming it does not validate the
current run-block renderer.

The renderer now lives in
`sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/chat/surface/timeline/rows/ChatRowRunBlock.kt`.
Its JVM Compose regression suite is
`sharedUI/src/jvmTest/kotlin/com/letta/mobile/ui/chat/surface/timeline/rows/ChatRowRunBlockSummaryUiTest.kt`:

```bash
./gradlew :sharedUI:jvmTest --tests '*ChatRowRunBlockSummaryUiTest'
```

This is a UI behavior test, not a Roborazzi image-baseline recorder. Android
modules retain their configured Roborazzi/tier tasks for surviving Android tests;
check the actual test class before choosing a `--tests` filter. `:app:testScreenshot`
uses Vintage plus `*ScreenshotTest` and permits no matching tests.
