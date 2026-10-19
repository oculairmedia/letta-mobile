# Module :core:ids

Typed identifiers and shared identifier-format rules used by runtime, data and
presentation modules. This foundation keeps identity independent of Room,
DataStore, transport engines and UI toolkit dependencies.

Use distinct ID types to prevent accidental interchange between records, routes,
canvas operations and render identities. Most wrappers serialize as bare strings;
[CanvasId](https://oculairmedia.github.io/letta-mobile/api/core/ids/com.letta.mobile.data.model/-canvas-id/index.html) intentionally remains a data class with an object-shaped persisted
representation. Changing that representation would require a storage migration.
Validation is deliberately specific to each type: some reject blanks while wire
and projection identities may retain them for graceful handling of unknown data.

# Package com.letta.mobile.data.model

Domain IDs, provider/model catalog keys, canvas and A2UI identities, and [IdFormats](https://oculairmedia.github.io/letta-mobile/api/core/ids/com.letta.mobile.data.model/-id-formats/index.html) own shared
names and default/optimistic identifier forms. These opaque values must not acquire filesystem
parsing, transport authorization or assumptions about numeric ordering. Extend shared identity
with distinct wrappers and format helpers here, preserving each type's serialization contract
and keeping parsing or permission decisions in its owning subsystem.
