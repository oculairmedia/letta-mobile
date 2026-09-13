# rivdump + riv2rml: lifting vector art out of existing `.riv` files

The Rive CLI has no `.riv` importer (SVG/Lottie/`.riv` import lives in the editor). These two
tools cover the gap for *art*: dump a file's object tree with the runtime, then emit the drawable
part as RML that drops into our components.

```
rivdump.exe <file.riv> schema.tsv > dump.json      # every artboard, every object, typed properties
python riv2rml.py dump.json EyeBrows --standalone out/scene.rml   # one artboard as an RML fragment
```

- `gen_schema.py` reads rive-runtime's generated headers and writes `schema.tsv`: for each type,
  its authored properties and their field kinds. `rivdump` reads a property off an object only when
  the object `isTypeOf` the owning type, so nothing is guessed.
- `rivdump.cpp` loads the file with `NoOpFactory` (no GPU) and prints JSON, one object per line.
  Component names are stripped in runtime builds; indices and `parentId` give the tree.
- `riv2rml.py` emits Node/Shape/paths/vertices/paints/clips with the runtime's own property names
  (which are RML's), remaps ids to a fresh client range, and drops bones, skins, joysticks,
  constraints and animations. What you get is the **rest pose** at frame 0.

Build: `.\build.ps1 -RiveRuntime C:\path\to\rive-runtime` (same MSVC config as the desktop bridge).

Licensing: community files carry their own license. The expression grid (erdemediz) is CC BY 4.0;
anything lifted from it into the shipped mascot needs attribution in the app's credits.
