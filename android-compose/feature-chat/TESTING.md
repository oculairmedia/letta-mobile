# Feature chat snapshot testing

Run the RunBlock snapshot suite with Roborazzi:

```bash
./gradlew :feature-chat:verifyRoborazziDebug --tests com.letta.mobile.feature.chat.RunBlockScreenshotTest --max-workers=1
```

Regenerate baselines after intentional visual changes:

```bash
./gradlew :feature-chat:recordRoborazziDebug --tests com.letta.mobile.feature.chat.RunBlockScreenshotTest --max-workers=1
```

RunBlock snapshots live in `:feature-chat` because the composable is internal to that feature module after the chat extraction work.
