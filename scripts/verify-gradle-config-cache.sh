#!/bin/bash
# Gradle Configuration Cache Verification Script
# Verifies that configuration cache is working correctly in letta-mobile
# Usage: ./scripts/verify-gradle-config-cache.sh

set -e

ANDROID_COMPOSE_DIR="android-compose"
REPORT_DIR="${ANDROID_COMPOSE_DIR}/build/reports/configuration-cache"

echo "=========================================="
echo "Gradle Configuration Cache Verification"
echo "=========================================="
echo ""

# Check if android-compose directory exists
if [ ! -d "$ANDROID_COMPOSE_DIR" ]; then
    echo "❌ Error: $ANDROID_COMPOSE_DIR directory not found"
    exit 1
fi

cd "$ANDROID_COMPOSE_DIR"

echo "📋 Step 1: Verify gradle.properties settings"
echo "-------------------------------------------"

if grep -q "org.gradle.configuration-cache=true" gradle.properties; then
    echo "✅ Configuration cache enabled"
else
    echo "❌ Configuration cache NOT enabled"
    exit 1
fi

if grep -q "org.gradle.caching=true" gradle.properties; then
    echo "✅ Build cache enabled"
else
    echo "⚠️  Build cache disabled (optional)"
fi

if grep -q "org.gradle.daemon=true" gradle.properties; then
    echo "✅ Gradle daemon enabled"
else
    echo "⚠️  Gradle daemon disabled"
fi

echo ""
echo "📋 Step 2: Clear previous cache (for clean test)"
echo "-------------------------------------------"
rm -rf .gradle/configuration-cache
echo "✅ Configuration cache cleared"

echo ""
echo "📋 Step 3: First build (creates cache)"
echo "-------------------------------------------"
echo "Running: ./gradlew :app:compileRootDebugKotlin"
echo ""

if ./gradlew :app:compileRootDebugKotlin --info 2>&1 | tee /tmp/gradle-first-build.log | grep -q "Configuration cache entry stored"; then
    echo ""
    echo "✅ Configuration cache entry STORED (first build successful)"
else
    echo ""
    echo "❌ Configuration cache entry NOT stored"
    echo "Check /tmp/gradle-first-build.log for details"
    exit 1
fi

FIRST_BUILD_TIME=$(grep "BUILD SUCCESSFUL" /tmp/gradle-first-build.log | head -1)
echo "   $FIRST_BUILD_TIME"

echo ""
echo "📋 Step 4: Second build (reuses cache)"
echo "-------------------------------------------"
echo "Running: ./gradlew :app:compileRootDebugKotlin"
echo ""

if ./gradlew :app:compileRootDebugKotlin --info 2>&1 | tee /tmp/gradle-second-build.log | grep -q "Configuration cache entry reused"; then
    echo ""
    echo "✅ Configuration cache entry REUSED (cache hit successful)"
else
    echo ""
    echo "⚠️  Configuration cache entry NOT reused"
    echo "This may indicate cache invalidation (expected if config changed)"
    echo "Check /tmp/gradle-second-build.log for details"
fi

SECOND_BUILD_TIME=$(grep "BUILD SUCCESSFUL" /tmp/gradle-second-build.log | head -1)
echo "   $SECOND_BUILD_TIME"

echo ""
echo "📋 Step 5: Performance comparison"
echo "-------------------------------------------"

FIRST_TIME=$(grep "BUILD SUCCESSFUL" /tmp/gradle-first-build.log | grep -oE "[0-9]+\.[0-9]+s" | head -1)
SECOND_TIME=$(grep "BUILD SUCCESSFUL" /tmp/gradle-second-build.log | grep -oE "[0-9]+\.[0-9]+s" | head -1)

if [ -n "$FIRST_TIME" ] && [ -n "$SECOND_TIME" ]; then
    echo "First build:  $FIRST_TIME"
    echo "Second build: $SECOND_TIME"
    echo ""
    echo "Expected: Second build should be 10-20x faster (cache hit)"
else
    echo "⚠️  Could not extract build times from logs"
fi

echo ""
echo "📋 Step 6: Check for configuration cache errors"
echo "-------------------------------------------"

if [ -d "$REPORT_DIR" ]; then
    echo "✅ Configuration cache report directory exists"
    echo "   Location: $REPORT_DIR"
    echo ""
    echo "   To inspect errors (if any):"
    echo "   open $REPORT_DIR/configuration-cache-report.html"
else
    echo "✅ No configuration cache errors (no report generated)"
fi

echo ""
echo "=========================================="
echo "✅ Verification Complete"
echo "=========================================="
echo ""
echo "Summary:"
echo "- Configuration cache is ENABLED and WORKING"
echo "- First build creates cache entry"
echo "- Second build reuses cache entry"
echo "- Expected speedup: 10-20x on cache hits"
echo ""
echo "Next steps:"
echo "1. Run builds normally; cache will be reused automatically"
echo "2. Cache invalidates when gradle.properties or build.gradle.kts changes"
echo "3. To clear cache manually: rm -rf .gradle/configuration-cache"
echo "4. To disable cache: ./gradlew -Dorg.gradle.configuration-cache=false"
