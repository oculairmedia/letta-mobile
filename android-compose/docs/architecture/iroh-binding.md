# Iroh Kotlin Binding Integration

**Bead:** `letta-mobile-g3cva.1`  
**Date:** 2026-06-27  
**Status:** ✅ Verified working in JVM test environment

## Overview

This document describes the integration of the [iroh-ffi](https://github.com/n0-computer/iroh-ffi) Kotlin/JVM binding into letta-mobile. Iroh is a peer-to-peer networking library that provides QUIC-based connections, NAT traversal, and relay support for building distributed systems.

## Dependency

**Maven Coordinate:** `computer.iroh:iroh:1.0.0`  
**Source:** Maven Central  
**License:** MIT OR Apache-2.0  
**Documentation:** https://n0-computer.github.io/iroh-ffi/ (Kotlin API docs)  
**Concepts:** https://docs.iroh.computer/quickstart

## Source Set Placement

**CRITICAL:** The iroh binding is a **JVM/Android-only** dependency using JNI to call native Rust code. It **MUST NOT** be placed in `commonMain` as it would break Kotlin/Native compilation.

### Current Configuration

In `sharedLogic/build.gradle.kts`:

```kotlin
val jvmAndAndroid by creating {
    dependsOn(commonMain.get())
    dependencies {
        // ... other dependencies ...
        
        // iroh-ffi Kotlin/JVM binding (JNI-backed, Android + JVM only).
        // NOT in commonMain — native lib binding won't work with Kotlin/Native.
        // g3cva.1: prove iroh endpoint create/connect works.
        api("computer.iroh:iroh:1.0.0")
    }
}
```

This makes iroh available to:
- ✅ `jvmMain` (desktop/server)
- ✅ `androidMain` (Android app)
- ✅ `jvmTest` (JVM tests)
- ❌ `commonMain` (would break native)
- ❌ `hostNative` (macOS/Linux/Windows native targets)

### JVM Target Requirement

Iroh 1.0.0 requires **JVM 21**. The `jvm` target has been upgraded:

```kotlin
jvm {
    compilerOptions {
        // JVM 21 required for iroh-ffi binding (computer.iroh:iroh:1.0.0)
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
```

**Note:** The `android` target remains at JVM 17 (Android compatibility).

## API Usage

### Basic Endpoint Creation

```kotlin
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions

suspend fun createIrohEndpoint(): Endpoint {
    // Create endpoint with default options (n0 production preset: relays + discovery)
    val options = EndpointOptions()
    
    // Or customize:
    // val options = EndpointOptions(
    //     secretKey = myPersistedKey,  // 32-byte ed25519 key
    //     alpns = listOf("/my-protocol/0".toByteArray()),
    //     bindAddr = "0.0.0.0:0"  // or specific port
    // )
    
    val endpoint = Endpoint.bind(options)
    return endpoint
}
```

### Getting Endpoint Identity

```kotlin
val endpointId: EndpointId = endpoint.id()  // 32-byte ed25519 public key
val shortId: String = endpointId.fmtShort()  // e.g., "d6dfd71206"

val endpointAddr: EndpointAddr = endpoint.addr()  // ID + known addresses
val relayUrl: String? = endpointAddr.relayUrl()  // e.g., "https://relay.iroh.network"
val directAddrs: List<String> = endpointAddr.directAddresses()  // IP:port pairs
```

### Connecting to a Remote Endpoint

```kotlin
suspend fun connectToRemote(endpoint: Endpoint, remoteAddr: EndpointAddr) {
    val alpn = "/my-protocol/0".toByteArray()
    
    // Await full connection (handshake completes)
    val connection: Connection = endpoint.connect(remoteAddr, alpn)
    
    // Or get pre-handshake handle
    val connecting: Connecting = endpoint.connectPending(remoteAddr, alpn)
    // ... inspect ALPN, drop early, etc.
}
```

### Bidirectional Streams

```kotlin
// Client opens a bidirectional stream
val biStream: BiStream = connection.openBi()
biStream.send.write("Hello".toByteArray())
biStream.send.finish()

val response = biStream.recv.readToEnd(maxBytes = 1024)
println("Received: ${response.decodeToString()}")

// Server accepts incoming streams
val (recvStream, sendStream) = serverConnection.acceptBi()
val request = recvStream.recv.readToEnd(1024)
sendStream.send.write("Response".toByteArray())
sendStream.send.finish()
```

### Cleanup

```kotlin
// Graceful shutdown
endpoint.shutdown()
endpoint.close()  // or use .use { } for auto-close
```

## Android-Specific Setup

**IMPORTANT:** Android apps must call `IrohAndroid.installAndroidContext(context)` once during startup (typically from `Application.onCreate()` or before constructing any `Endpoint`). This initializes the JNI bridge so iroh's DNS resolver can access Android's `LinkProperties`.

```kotlin
import computer.iroh.IrohAndroid

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        IrohAndroid.installAndroidContext(this)
    }
}
```

## Native Library Resolution

### JVM (Desktop/Tests)

The iroh JAR bundles native libraries for multiple platforms in a standard JNA layout:
- `linux-x86-64/libiroh_ffi.so`
- `darwin-aarch64/libiroh_ffi.dylib` (macOS ARM)
- `win32-x86-64/iroh_ffi.dll`

JNA automatically extracts and loads the correct library for the host platform. No manual `java.library.path` configuration is needed.

**Verified in JVM tests:** Native lib loads successfully on Linux x86-64 in Gradle test environment.

### Android

The iroh JAR includes native libraries for Android ABIs. Gradle's Android plugin automatically packages the appropriate `.so` files into the APK.

**Supported ABIs:**
- `arm64-v8a` (primary modern Android devices)
- `armeabi-v7a` (older 32-bit ARM)
- `x86_64` (emulators, rare devices)
- `x86` (older emulators)

The Android system loads the correct ABI library at runtime. No special configuration needed.

## Testing

### JVM Unit Tests

The `IrohBindingSpikeTest` in `sharedLogic/src/jvmTest/kotlin/com/letta/mobile/data/transport/iroh/` proves:

1. ✅ Dependency resolves from Maven Central
2. ✅ Native library (JNI) loads successfully in JVM tests
3. ✅ `Endpoint.bind()` creates an endpoint
4. ✅ `endpoint.id()` and `endpoint.addr()` work
5. ✅ Custom secret keys and ALPNs can be configured
6. ✅ Endpoint binds to sockets and reports bound addresses

**Run the spike tests:**
```bash
cd android-compose
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
ANDROID_HOME=/opt/android-sdk \
./gradlew :sharedLogic:jvmTest --tests 'com.letta.mobile.data.transport.iroh.IrohBindingSpikeTest'
```

**Verify native compilation still works:**
```bash
./gradlew :sharedLogic:compileKotlinHostNative
```

### Full Connection Tests

The spike tests create endpoints and query their properties, but do NOT test full connection establishment + byte transfer between two endpoints. That requires:

- Relay server access (for NAT traversal)
- Discovery mechanism (to exchange `EndpointAddr`)
- Async accept/connect coordination

**These will be tested in:**
- Integration tests (bead g3cva.2+)
- On-device Android tests
- Desktop integration environment

## Known Limitations / Gotchas

1. **JVM 21 required**: Tests and desktop builds must use Java 21. Android stays at JVM 17 bytecode compatibility.

2. **Not multiplatform**: Iroh is JVM/Android only. Cannot be used from `commonMain` or Kotlin/Native targets.

3. **Async API**: Most iroh operations are `suspend` functions. Ensure you call them from a coroutine context.

4. **Resource cleanup**: `Endpoint` implements `AutoCloseable`. Always call `shutdown()` + `close()` or use `.use { }` to avoid leaking native resources.

5. **Android context required**: Android apps MUST call `IrohAndroid.installAndroidContext()` before creating endpoints.

6. **Relay/discovery setup**: Full P2P connectivity requires relay servers and discovery. The default `n0` preset uses iroh's public relays. For production, consider self-hosted relays or the minimal preset with custom relay config.

## Next Steps (Bead g3cva.2)

- [ ] Integrate iroh endpoint lifecycle with app-server-controller
- [ ] Implement transport adapter for iroh connections
- [ ] Add relay configuration (self-hosted or n0 public)
- [ ] Test full connection establishment between two letta-mobile instances
- [ ] Implement protocol handler for letta messaging over iroh

## References

- **Kotlin API docs:** https://n0-computer.github.io/iroh-ffi/
- **Quickstart guide:** https://docs.iroh.computer/quickstart
- **iroh-ffi repo:** https://github.com/n0-computer/iroh-ffi
- **Maven Central:** https://central.sonatype.com/artifact/computer.iroh/iroh/1.0.0
