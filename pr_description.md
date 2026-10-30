🎯 **What:**
Added a test to ensure `IrohAppServerTransportAdapter.createTransport` correctly rejects non-"iroh" schemes (like "wss").
Shared implementation searched: `android-compose/sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/transport/iroh/IrohAppServerTransportAdapter.kt`

📊 **Coverage:**
The newly added test `createTransport throws exception when scheme is not iroh` asserts the exception is thrown for invalid parameters.

✨ **Result:**
Prevents developers from mistakenly passing non-Iroh connections into this adapter and improves the overall reliability of the codebase.
