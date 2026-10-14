# :plugin-api

The Kotlin SPI and LCP wire DTOs a canvas plugin is written against (letta-mobile-s416w.26).

Only the jvm plugin host consumes it: `:sharedLogic` depends on it from `jvmMain`, so the Android
APK and the wasm bundle do not ship it (letta-mobile-cdgpr.1).

Binary-compatibility validation (the committed API dump and `PluginApiStabilityTest`) was removed
because the first plugin is a Node process plugin that speaks the wire protocol, not this jar. It
returns when a jar plugin or a published `:plugin-api` artifact exists.
