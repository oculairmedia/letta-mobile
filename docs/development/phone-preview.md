# Phone preview on the desktop

Iterate on the **mobile** UI of the shared KMP chat page (`sharedUI/.../ui/chat/surface/**`,
epic letta-mobile-bglj6.1) from a Windows (or any JBR-capable) desktop, without an Android device or
emulator. The phone idiom is what `ChatSurfaceAppearance.platformStyle = ChatPlatformStyle.Touch`
draws: `TouchComposerBar`, `TouchCanvasDock` (chat head, reply popup), the Touch canvas chrome (undo,
redo and the more menu in the tool rail), the keyboard camera (`CanvasKeyboardCamera`) and the
edge-to-edge chat page.

There are two runners. Both live in the `desktop` module and change nothing in production: the live
mode is behind an environment flag, and the playground is its own source set.

| Mode | Command (from `android-compose/`) | Needs a server |
|---|---|---|
| Live phone app | `./gradlew :desktop:runPhone` | yes, the app's configured backend |
| Fixture playground | `./gradlew :desktop:runPhonePlayground` | no |
| Either, with hot reload | `./gradlew :desktop:runPhoneHot` / `./gradlew :desktop:runPhonePlaygroundHot` | as above |

On Windows, use `gradlew.bat` and set `JAVA_HOME` to a JDK 21+ for Gradle itself; the runners launch
on the JetBrains Runtime that `:desktop:extractDesktopJbr` downloads (Jewel needs a 25+ runtime, and
hot reload needs JBR). The first launch builds the native Mermaid and tablet libraries with `cargo`,
as `:desktop:run` does.

## 1. Live phone mode: `:desktop:runPhone`

The real desktop app (its agents, conversations and boards over the existing transports) in a
phone-sized window. `runPhone` sets `LETTA_DESKTOP_PHONE=1`; the same flag (or
`-Dletta.desktop.phone=true`) works on any other launch of `com.letta.mobile.desktop.MainKt`.
With the flag off, `Main.kt` takes the normal path and nothing below applies.

- **Window.** A Pixel 9 Pro screen (412 x 915 dp) inside a thin device frame, resizable. One phone
  dp is one window dp, scaled down in 0.05 steps only when the monitor is too short. Resizing the
  window resizes the phone in dp, like a resizable emulator; the title shows the current size.
- **Shell.** The shared chat page always draws (the `LETTA_DESKTOP_SHARED_CHAT` preview flag is
  implied) in the Touch idiom with Android's appearance: `ChatToolDetails.Sheet`, no shortcut strip.
  It opens canvas-first, edge to edge. The full-screen chat has a floating header (menu, agent name,
  back to the canvas) and passes its height as `topChromeInset`, as `SharedChatPage` does; the
  canvas mode hides the header and keeps the top of the board clear. The desktop's agent rail and
  sidebar move into a navigation drawer, opened from the header's menu, the chat head's agent pane
  and the board's agent switcher. Other destinations (Home, Settings...) get a phone app bar.
- **Insets.** A 40 dp status bar (with the camera cutout), a 24 dp gesture bar, and an on-screen
  keyboard of 300 dp (200 dp in landscape) that slides in and out over ~285 ms. See *How the insets
  are simulated* below.
- The window has no tray icon, quick-query window, jump list or single-instance lock, so it can run
  beside a normal desktop instance (both then share the same local settings and caches).

## 2. Fixture playground: `:desktop:runPhonePlayground`

The phone screens of sharedUI's snapshot tests, live and interactive, with no server: a list on the
left and up to three phone panes. Click a screen to show it; Ctrl+click (or Shift+click) to add it
beside the others. The panes are real `ChatSurface`s over a `FixtureChatSessionPort`: typing edits
the draft, Send appends the prompt and starts a fake run that Stop ends, the head drags and snaps,
the bar swipes up to the chat.

Screens: chat idle (dark and light), typing, streaming with Stop, an image in a prompt, keyboard up;
canvas idle, thinking, reply popup (dark and light), head left, head right at the top, keyboard up;
the canvas.compose receipt cards (dark and light).

For scripted runs: `LETTA_PLAYGROUND_SCENES=phone-canvas-idle,phone-full-screen-typing` picks the
first panes, `LETTA_PHONE_AUTOSHOT=<seconds>` saves a screenshot after that long and
`LETTA_PHONE_EXIT_AFTER_SHOT=1` then quits (both also work for `runPhone`). `LETTA_PLAYGROUND_RIVE=1`
draws the real Rive mascot instead of the stand-in. Use it with a single pane, because the native
renderer draws one surface per agent.

## Shortcuts

Both runners, any time (F1 shows the list over the phone; it also shows for 8 s at launch):

| Keys | Does |
|---|---|
| Ctrl+K | Show / hide the on-screen keyboard |
| Ctrl+Shift+K | Keyboard follows text focus (on by default) / only on Ctrl+K |
| Ctrl+R | Rotate: portrait / landscape |
| Ctrl+F | Font size: 1.0, 1.15, 1.3, 1.5, 2.0, 0.85 (Android's font scale) |
| Ctrl+Shift+D | Display size: default, large (x1.1), larger (x1.2) density |
| Ctrl+L | Light / dark |
| Ctrl+M | Reduced motion (`LocalReducedMotion`; the keyboard then snaps) |
| Ctrl+T | Mouse acts as touch (on by default) |
| Ctrl+Shift+F | Device frame on / off |
| Ctrl+Shift+S | Save a screenshot to `android-compose/desktop/build/phone-preview/` (the window stays on top for a moment while it is taken) |
| F1 | Shortcut list |

The keyboard also rises by itself whenever a text field starts an input session, and goes down a
beat after the last one ends, as Android's does. Its chevron key hides it, like Back.

## 3. Hot reload

`runPhoneHot` and `runPhonePlaygroundHot` run the same entry points under
[Compose Hot Reload](https://github.com/JetBrains/compose-hot-reload) 1.2.0
(`org.jetbrains.compose.hot-reload`), on the JBR from `:desktop:extractDesktopJbr` (JBR 25.0.4 accepts
`-XX:+AllowEnhancedClassRedefinition`). Hot reload's requirements are met: Kotlin 2.4.10 (2.1.20+),
Compose Multiplatform 1.10/1.11 (1.10.0+ for CHR 1.2), JVM bytecode 21 (21 or earlier).

- Explicit mode: edit, then run `./gradlew reload`, or the specific
  `./gradlew :desktop:hotReloadMain` (live app) / `./gradlew :desktop:hotReloadPhonePlayground`
  (playground), in a second terminal. The window keeps its state and redraws with the new code
  (verified on this setup: a text change in the playground showed up in the open window).
- Auto mode: add `--auto` (`./gradlew :desktop:runPhonePlaygroundHot --auto`) and every save
  recompiles and reloads.
- The reload task recompiles the runtime classpath, so edits in `sharedUI` are picked up as well.
  Changes the JVM cannot redefine in place (a new class hierarchy, a changed `main`) need a restart.
- Keep the Gradle daemon (no `--no-daemon`) for these: the reload tasks then take seconds.

The plugin is applied to `:desktop` **only when a task name starts with `hot` or ends with `Hot`**
on the command line, so ordinary and CI builds never see its compiler flags or runtime artifacts.
If hot reload misbehaves (a structural change it cannot redefine, a JBR it rejects), the plain
runners restart in seconds once Gradle is warm: drop `--no-daemon` locally, close the window and
re-run.

## What is faithful, and what is not

Faithful, because it is the same code:

- Every pixel of the chat page, the canvas and their chrome is sharedUI's own composables in the
  Touch idiom, at phone density and size, in the Letta palette.
- The insets are read through the real APIs: `WindowInsets.ime`, `.navigationBars`, `.statusBars`
  and `.safeDrawing`, and `imePadding()`. So `TouchComposerBar`'s padding, `TouchCanvasDock`, the
  canvas's keyboard camera and `ChatComposerPanel` react to the simulated keyboard exactly as to a
  real one, frame by frame while it slides.
- Font scale and display size change `LocalDensity` the way Android's settings do.

Not faithful:

- **Fonts.** Android's theme uses Inter and JetBrains Mono from `:designsystem` (Android resources).
  The preview uses Material 3's type scale and shapes (Android's sizes) in the desktop's fonts, so
  glyph widths and line breaks differ a little.
- **Density.** The preview maps one phone dp to one window dp (or less, see *Window*). Pixel
  rounding and the physical size of a dp differ from a 3.125x Pixel screen.
- **IME.** The keyboard is a stand-in: it has the right height and slide, but its keys do not type
  (the desktop keyboard does). There is no IME composition, autocorrect or suggestion strip
  behaviour, and no `WindowInsetsAnimation` callbacks beyond the inset values moving.
- **System bars.** Drawn by the preview with fixed heights. No real notification shade, no
  three-button navigation, no landscape cutout on the side, and the status bar does not change
  contrast with the content under it.
- **Back.** There is no system Back gesture. Escape collapses the full-screen chat to the canvas,
  as Android's Back does on that page.
- **Touch.** With Ctrl+T on, the left mouse button reaches Compose as `PointerType.Touch` (drag to
  scroll, one-finger canvas pan, touch text selection). It is one pointer: no pinch or two-finger
  gestures (use the wheel and Ctrl+wheel), no haptics. The mouse is fed to the app's own
  `ComposeTouchInjector` (the path Windows fingers take) as one finger; if a Compose upgrade moves
  the scene it reaches, it logs `PHONE: mouse-as-touch unavailable` and the mouse stays a mouse.
- **Popups and sheets.** Menus, dialogs and bottom sheets are Compose Desktop layers: a full-window
  sheet covers the device frame too, and they do not read the simulated insets.
- **Rive mascot.** The native bridge `android-compose/desktop/rive_desktop_bridge.dll` is git-ignored.
  Without it the runners log a `WARNING` (when the Gradle task starts, and from the app) and agents draw as
  gradient orbs (live mode) or the teal stand-in (playground). Copy it in, or pass `-PriveBridge=<dll>`.
- **Platform services.** No dictation (the mic is a stand-in in the playground and absent live),
  no Android share sheet, photo picker, notifications or permissions.
- **Desktop panes in the drawer.** The live mode's drawer reuses the desktop's rail and sidebar, and
  destinations other than the chat are the desktop's pages at phone width, not Android's screens.

## How the insets are simulated

Compose Multiplatform's skiko targets read every `WindowInsets.*` value from the
`LocalPlatformWindowInsets` composition local, and the inset-padding modifiers from the nearest
`PlatformWindowInsetsProviderNode`. A desktop window reports zero for both. `DesktopPhoneScreen`
installs `PhoneWindowInsets` at both seams (`desktop/.../phone/DesktopPhoneInsets.kt`), computed on
read from snapshot state, so neither sharedUI nor Android needed a change. The seam is
`@InternalComposeUiApi` (CMP-9379 plans to replace it); a Compose bump that moves it breaks only that
dev-only file. The playground and the phone shell's tests (`DesktopPhoneShellTest`) use the same
screen.

## Adding a screen to the playground

The screens are `PhoneScene`s in `android-compose/sharedUI-devfixtures` (a JVM module that only
sharedUI's `jvmTest`, desktop's `test` and desktop's `phonePlayground` source set depend on):

1. If the screen needs new data, add it to `PhoneFixtures` (a `ChatUiState`, a composer state, a
   message list). Reuse the existing conversation where you can.
2. Add a `PhoneScene` to `PhoneScenes`, usually as a `copy` of a neighbour, with a unique `id` (the
   snapshot's file name), a `label` for the list, and the `presentation`, `dark`, `withCanvas`,
   `dock` and `keyboardUp` it needs. Append it to `PhoneScenes.all` where it belongs in the list.
3. If it should also be a snapshot, add a test to `ChatSurfacePhoneSnapshotTest`:
   `@Test fun myScreen() = snapshot(PhoneScenes.myScreen)`. It writes
   `sharedUI/build/chat-surface-snapshots/<id>.png`.
4. Run `./gradlew :desktop:runPhonePlayground` (or `LETTA_PLAYGROUND_SCENES=<id>` to open on it).

Behaviour the fixture port does not cover (approvals, A2UI actions, commands) belongs in
`FixtureChatSessionPort`; keep it server-free.
