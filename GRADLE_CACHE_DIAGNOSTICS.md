# Gradle Configuration Cache Diagnostics Guide
## letta-mobile

This guide helps diagnose and resolve configuration cache issues.

---

## Quick Diagnostics

### Is Configuration Cache Enabled?

```bash
cd android-compose
grep "org.gradle.configuration-cache" gradle.properties
```

**Expected output**:
```
org.gradle.configuration-cache=true
```

### Is Configuration Cache Working?

```bash
cd android-compose

# Clear cache first
rm -rf .gradle/configuration-cache

# First build (creates cache)
./gradlew :app:compileRootDebugKotlin --info 2>&1 | grep -i "configuration cache"

# Expected: "Configuration cache entry stored."
```

### Check Cache Hit Rate

```bash
cd android-compose

# Second build (should reuse cache)
./gradlew :app:compileRootDebugKotlin --info 2>&1 | grep -i "configuration cache"

# Expected: "Configuration cache entry reused."
```

---

## Common Issues & Solutions

### Issue 1: "Configuration cache entry reused" NOT appearing

**Symptoms**:
- Second build takes same time as first build
- No "Configuration cache entry reused" message

**Causes**:
1. Cache was invalidated (config changed)
2. Cache directory was deleted
3. Different Gradle invocation (different tasks)

**Solutions**:

```bash
cd android-compose

# Check what changed
git diff gradle.properties
git diff build.gradle.kts
git diff android-compose/gradle.properties

# If nothing changed, cache may have been deleted
ls -la .gradle/configuration-cache/

# If empty, recreate cache
./gradlew :app:compileRootDebugKotlin

# Verify cache was created
ls -la .gradle/configuration-cache/
```

### Issue 2: "Configuration cache entry stored" with WARNINGS

**Symptoms**:
```
Configuration cache entry stored with 1 problem(s).
```

**Causes**:
- Non-serializable object in task inputs
- Eager Project access at config time
- File I/O at configuration time

**Solutions**:

```bash
cd android-compose

# Generate detailed report
./gradlew :app:compileRootDebugKotlin --configuration-cache-problems=warn

# Open HTML report
open build/reports/configuration-cache/configuration-cache-report.html
```

**Report interpretation**:

| Problem Type | Cause | Fix |
|---|---|---|
| Non-serializable object | Task input contains non-serializable class | Use `Provider<T>` or lazy evaluation |
| Eager evaluation | `project.` accessed at config time | Move to task action or use `providers.*` |
| File access | `File.exists()` at config time | Use `FileSystemOperations` or layout providers |
| Dependency resolution | Dynamic version resolution | Use version catalog or fixed versions |

### Issue 3: Build fails with "Configuration cache entry discarded"

**Symptoms**:
```
Configuration cache entry discarded with 5 problem(s).
BUILD FAILED
```

**Causes**:
- Multiple serialization errors
- Incompatible plugin version
- Gradle version mismatch

**Solutions**:

```bash
cd android-compose

# Clear cache and rebuild
rm -rf .gradle/configuration-cache
./gradlew clean :app:compileRootDebugKotlin

# Check Gradle version
./gradlew --version

# Expected: Gradle 8.11.1 or later

# If version is old, update wrapper
./gradlew wrapper --gradle-version=8.11.1

# Verify plugins are compatible
grep "com.android.application" build.gradle.kts
# Expected: version "8.9.2" or later

# If plugins are old, update build.gradle.kts
```

### Issue 4: "Daemon disappeared" or IC corruption

**Symptoms**:
```
Daemon disappeared unexpectedly (exit code: 1)
source-to-classes.tab already registered
```

**Causes**:
- Kotlin incremental compilation cache corruption
- Daemon crash due to memory pressure
- Overlapped Gradle work

**Solutions**:

```bash
cd android-compose

# Stop daemon
./gradlew --stop

# Kill stale processes
pkill -f kotlin-daemon 2>/dev/null || true

# Clean Kotlin IC caches (not full clean)
./gradlew cleanKotlinIC

# Rebuild
./gradlew :app:compileRootDebugKotlin

# If still failing, full clean
./gradlew clean :app:compileRootDebugKotlin
```

### Issue 5: Cache works locally but not in CI

**Symptoms**:
- Local builds reuse cache (fast)
- CI builds always slow (no cache reuse)

**Causes**:
- CI runner doesn't persist `.gradle/` directory
- Different Gradle invocation in CI
- Environment variables differ between runs

**Solutions**:

```yaml
# In .github/workflows/android.yml

- name: Setup Gradle
  uses: gradle/actions/setup-gradle@v6
  with:
    cache-read-only: false  # Allow cache writes
    gradle-home-cache-cleanup: true

- name: Build
  run: |
    cd android-compose
    ./gradlew :app:compileRootDebugKotlin
```

**Verify CI cache is working**:

```bash
# Check GitHub Actions workflow
cat .github/workflows/android.yml | grep -A 5 "setup-gradle"

# Expected: setup-gradle@v6 with cache-read-only: false
```

---

## Advanced Diagnostics

### Inspect Configuration Cache Contents

```bash
cd android-compose

# List cache entries
ls -lah .gradle/configuration-cache/

# Expected structure:
# .gradle/configuration-cache/
# ├── cache-actions.bin
# ├── cache-metadata.bin
# ├── cache-state.bin
# └── ...
```

### Analyze Configuration Cache Report

```bash
cd android-compose

# Generate report with all problems
./gradlew :app:compileRootDebugKotlin \
  --configuration-cache-problems=warn \
  --info 2>&1 | tee /tmp/gradle-config-cache.log

# Open HTML report
open build/reports/configuration-cache/configuration-cache-report.html

# Or parse report programmatically
cat build/reports/configuration-cache/configuration-cache-report.html | \
  grep -oE '<li>.*?</li>' | head -20
```

### Monitor Cache Hit Rate Over Time

```bash
cd android-compose

# Create a log file
LOG_FILE="/tmp/gradle-cache-hits.log"

# Run multiple builds and log cache status
for i in {1..5}; do
  echo "=== Build $i ===" >> $LOG_FILE
  ./gradlew :app:compileRootDebugKotlin --info 2>&1 | \
    grep -i "configuration cache" >> $LOG_FILE
  echo "" >> $LOG_FILE
done

# View results
cat $LOG_FILE
```

### Check Gradle Daemon Status

```bash
cd android-compose

# List running daemons
./gradlew --status

# Expected output:
#   PID VERSION                 STATUS
#   123 8.11.1                  IDLE
#   456 8.11.1                  BUSY
```

### Verify Provider Usage

```bash
cd android-compose

# Search for eager Project access (anti-pattern)
grep -r "System.getenv" . --include="*.gradle.kts" || echo "✅ No System.getenv found"
grep -r "project\." . --include="*.gradle.kts" | grep -v "project.layout" | head -5

# Search for lazy provider usage (good pattern)
grep -r "providers\." . --include="*.gradle.kts" | head -10
```

---

## Performance Profiling

### Measure Configuration Time

```bash
cd android-compose

# Profile configuration phase
./gradlew :app:compileRootDebugKotlin \
  --profile \
  --info 2>&1 | grep -E "Configuration|Execution"

# Open profile report
open build/reports/profile/profile-*.html
```

### Compare Build Times

```bash
cd android-compose

# Disable cache and measure
time ./gradlew :app:compileRootDebugKotlin \
  -Dorg.gradle.configuration-cache=false

# Enable cache and measure (second run for cache hit)
time ./gradlew :app:compileRootDebugKotlin

# Expected: Second run should be 10-20x faster
```

### Identify Slow Tasks

```bash
cd android-compose

# Build with task timing
./gradlew :app:compileRootDebugKotlin \
  --info 2>&1 | grep -E "Task|took" | tail -20

# Or use build scan
./gradlew :app:compileRootDebugKotlin --scan
```

---

## Recovery Procedures

### Clear Configuration Cache

```bash
cd android-compose

# Remove cache directory
rm -rf .gradle/configuration-cache

# Next build will recreate cache
./gradlew :app:compileRootDebugKotlin
```

### Disable Configuration Cache Temporarily

```bash
cd android-compose

# One-time disable
./gradlew :app:compileRootDebugKotlin \
  -Dorg.gradle.configuration-cache=false

# Or edit gradle.properties
sed -i 's/org.gradle.configuration-cache=true/org.gradle.configuration-cache=false/' gradle.properties

# Re-enable after debugging
sed -i 's/org.gradle.configuration-cache=false/org.gradle.configuration-cache=true/' gradle.properties
```

### Full Gradle Reset

```bash
cd android-compose

# Stop daemon
./gradlew --stop

# Kill stale processes
pkill -f kotlin-daemon 2>/dev/null || true

# Clean everything
./gradlew clean

# Clear caches
rm -rf .gradle/

# Rebuild
./gradlew :app:compileRootDebugKotlin
```

---

## Verification Checklist

- [ ] Configuration cache enabled in `gradle.properties`
- [ ] First build creates cache entry ("Configuration cache entry stored")
- [ ] Second build reuses cache entry ("Configuration cache entry reused")
- [ ] Second build is 10-20x faster than first build
- [ ] No configuration cache warnings or errors
- [ ] CI workflow uses `setup-gradle@v6`
- [ ] CI builds reuse cache between runs
- [ ] Local builds reuse cache between invocations
- [ ] Cache invalidates when `gradle.properties` changes
- [ ] Cache invalidates when `build.gradle.kts` changes

---

## References

- **Gradle Configuration Cache**: https://docs.gradle.org/8.11.1/userguide/configuration_cache.html
- **Configuration Cache Debugging**: https://docs.gradle.org/8.11.1/userguide/configuration_cache_debugging.html
- **Gradle Daemon**: https://docs.gradle.org/8.11.1/userguide/gradle_daemon.html
- **Build Scans**: https://scans.gradle.com/

---

## Support

If you encounter issues not covered here:

1. Check the configuration cache HTML report: `build/reports/configuration-cache/configuration-cache-report.html`
2. Review Gradle logs: `./gradlew --info 2>&1 | grep -i "configuration cache"`
3. Run verification script: `./scripts/verify-gradle-config-cache.sh`
4. Check Gradle documentation: https://docs.gradle.org/8.11.1/userguide/configuration_cache.html

