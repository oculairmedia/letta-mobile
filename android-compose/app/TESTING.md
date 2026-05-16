# App snapshot testing

App-owned screenshot tests use Roborazzi. Run the app screenshot tier with:

```bash
./gradlew :app:testScreenshot --max-workers=1
```

RunBlock snapshots moved with the composable into `:feature-chat`. Use:

```bash
./gradlew :feature-chat:verifyRoborazziDebug --tests com.letta.mobile.feature.chat.RunBlockScreenshotTest --max-workers=1
```

Regenerate feature-chat baselines after intentional RunBlock visual changes:

```bash
./gradlew :feature-chat:recordRoborazziDebug --tests com.letta.mobile.feature.chat.RunBlockScreenshotTest --max-workers=1
```

This project uses Roborazzi for app/feature screenshots. Paparazzi is currently not functional in this repo environment; see `letta-mobile-zwsk` for the broader tooling follow-up.
