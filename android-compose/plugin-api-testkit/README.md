# :plugin-api-testkit

`FakePluginHost` and `PluginConformance.run` for jvm (in-process jar) canvas plugins
(letta-mobile-s416w.26).

Frozen until `InProcessJarDriver` exists: the first shipping plugin is a Node process plugin, so no
jar plugin runs against this kit yet. Keep it compiling; do not extend it (letta-mobile-cdgpr.1).

The keystore provider kit (`com.letta.mobile.plugin.testkit.keystore`, `KeystoreConformance.run`)
is separate from the frozen canvas kit above: provider modules run it against their
`KeystoreConformanceFixture`. Contract: `docs/reference/contracts/keystore-v1.md`.
