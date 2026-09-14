# Native Rive on desktop (spike, letta-mobile-0s5bi)

`rive_desktop_bridge.dll` runs a `.riv` natively inside Compose Desktop, with no webview:

```
rive-runtime (state machine, data binding)
  -> Rive Renderer, D3D11 backend, offscreen texture
  -> pixel readback (premultiplied RGBA)
  -> JNA -> Skia bitmap -> an ordinary Compose node (RiveDesktopSurface)
```

**Why the Rive Renderer specifically:** vector feathering (soft glow, blur) only exists in Rive's
own GPU renderer. Skia-backed bridges (e.g. Rive-CMP's desktop target) and the web runtime's
Canvas2D build silently draw feathered art flat.

The Kotlin side lives in `desktop/src/main/kotlin/com/letta/mobile/desktop/avatar/rive/`. Its
input sink implements the same `RiveInputSink` Android uses, so `RiveAvatarRuntime` and
`RiveAvatarContract` are unchanged.

## Building (Windows, VS 2022 MSVC)

Nothing beyond VS 2022 C++ tools, the Windows SDK, Git for Windows and Python 3 is needed.

1. Clone and build rive-runtime. Verified at `02bea09bc68eb923498a3fe77da257a96e48d2e9`.
   Three workarounds apply to a plain VS 2022 install, all in `tools/`:
   - `--with_rive_canvas` (on by default) pulls in the Ore D3D12 backend, which older MSVC
     (14.34) fails to compile against the pinned DirectX-Headers. The bridge does not use it, so
     `RIVE_PREMAKE_ARGS` drops it. `--no_gl` is also passed, though two GL sources still compile.
   - Without `--with_rive_canvas` those GL sources miss an include of `rive/renderer/texture.hpp`
     (`rive-runtime-texture-include.patch`). Worth reporting upstream.
   - The shader step shells out to GNU `make` and `python3`. `tools/make.cmd` + `make_shim.py`
     replicate the Makefile's `minify` + `d3d` rules with the SDK's `fxc.exe`, and
     `tools/python3.cmd` sidesteps the Microsoft Store `python3` stub.

   ```bash
   git clone https://github.com/rive-app/rive-runtime && cd rive-runtime
   git apply <this dir>/tools/rive-runtime-texture-include.patch
   cd renderer
   export RIVE_PREMAKE_ARGS="--with_rive_text --with_rive_layout --no_gl"
   export PATH="<this dir>/tools:/c/Program Files/Microsoft Visual Studio/2022/Community/MSBuild/Current/Bin:$PATH"
   ../build/build_rive.sh --toolset=msc release   # ~3 min; path_fiddle fails, the libraries do not
   ```

2. Build the bridge. Its defines and CRT must match rive-runtime's generated projects exactly;
   `build.ps1` carries them.

   ```powershell
   .\build.ps1 -RiveRuntime C:\path\to\rive-runtime
   ```

3. Run the spike window. The mascot column always shows the shipped
   `avatar/renderer-rive/src/androidMain/res/raw/mascot.riv`; `-PriveFile` adds a second column
   for any other file (its trigger inputs become buttons):

   ```bash
   cd android-compose
   ./gradlew --no-daemon -q :desktop:runRiveSpike -PriveBridge=C:/rive-spike/bridge/rive_desktop_bridge.dll -PriveSelfTest=true
   ./gradlew :desktop:runRiveSpike -PriveBridge=<...>/rive_desktop_bridge.dll \
     -PriveFile=<file.riv> -PriveStateMachine="State Machine 1" -PriveTriggers=Happy,Sad,Angry,Crazy
   ```

   A **bare** `-PriveFile` (no `-PriveStateMachine`, no `-PriveTriggers`) is taken as a build of the
   mascot itself - a `--solo` or `--probe` variant - and drives the bench instead of the shipped
   file, so the scenarios, the onion skin and the telemetry panel all look at the same scene:

   ```bash
   # MOTION-PIPELINE section 6, tier 2: a probed build in the bench, one signature on startup
   python avatar/renderer-rive/rive/mascot/gen_scene.py C:/tmp/probe/scene.rml --probe   # then rive C:/tmp/probe --once
   ./gradlew --no-daemon :desktop:runRiveSpike -PriveBridge=C:/rive-spike/bridge/rive_desktop_bridge.dll \
     -PriveFile=C:/tmp/probe/build/mascot.riv -PriveOnion=true -PriveRecord=enter-thinking
   ```

   `-PriveSelfTest=true` cycles every mascot state and identity (and fires each `-PriveTriggers`
   trigger) without input, printing each step - run it in the background and tail the log.
   `-PriveOnion=true` opens with the live onion skin on; `-PriveRecord=<scenario>` presses "record
   signature" once the file has loaded and prints `probe.py`'s JSON to stdout, so a signature can be
   taken without touching the window.

   Bridge entry points the bench needs beyond the original set - `rive_bridge_load_artboard` (load a
   named artboard, e.g. the `Harness`), `rive_bridge_vm_get_number` (read a probe number back) and
   `rive_bridge_vm_number_names` (list them) - are looked up by symbol on the Kotlin side, so a DLL
   built before they existed still runs the bench, with the telemetry panel empty.
   On this machine the DLL lives at `C:/rive-spike/bridge/`, built from a rive-runtime clone in
   `C:/rive-spike/`. If a relaunch dies in Skiko's D3D redrawer, the previous window was still
   exiting; launch again.

## Result (Windows 11, RTX 3090)

- A feathered community file (glow, blurred body, glass rim) renders identically to Rive's WebGL2
  runtime; its state machine triggers fire from Kotlin.
- The shipped mascot is driven by the unchanged `RiveAvatarRuntime` over the desktop sink:
  `speaking` turns green with `mouthOpen` opening the mouth, `error` turns red.
- Render + readback: ~5-6 ms per frame at ~1050x1050 px, on the UI thread.

## Open questions for productionising

- macOS (Metal) and Linux (Vulkan/GL) offscreen backends; the bridge is D3D11-only.
- CI builds per OS and packaging the DLL into the installer, like `letta_mermaid_renderer`.
- Readback cost at large sizes; a shared GPU texture into Skiko would remove it.
- Pinning rive-runtime to the version rive-android ships, so both platforms read the same files.
