package com.letta.mobile.data.transport.appserver

/**
 * letta-mobile-by4vm.110 D1: the one letta-code version. The Android embedded runtime, the desktop
 * bundled runtime and the wire contract the Kotlin client is verified against are all this
 * release; its source of truth is `letta-code-runtime/letta-code-runtime.properties`.
 * User-facing text cites this instead of spelling the version out. `LettaCodeVersionPinsTest`
 * (appserver-cli) fails when it disagrees with the pin file, the contract matrix, the desktop
 * runtime package files or the CI job. The README next to this file has the full pin table.
 */
object LettaCodeContract {
    /**
     * THE pin: the protocol `.d.ts` corpus the Kotlin wire types are verified against, equal to the
     * desktop and on-device runtimes. This is the supported baseline, not a claim that every
     * runtime feature has the same introduction version. Durable input fields are separately
     * gated by the advertised server version. Decoding remains additive-tolerant for extra fields;
     * neither tolerance nor a field gate certifies support for another runtime release.
     */
    const val VERSION: String = "0.34.14"
}
