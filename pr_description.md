💡 **What:** Replaced the `split` call in `recentBackends()` with `splitToSequence` and deferred evaluation until `toList()`.
🎯 **Why:** To optimize memory allocations by avoiding intermediate `List` instantiations during `map` and `filter` operations in a string processing chain.
📊 **Measured Improvement:** The user opted out of requiring a performance measurement benchmark for this minor optimization.
