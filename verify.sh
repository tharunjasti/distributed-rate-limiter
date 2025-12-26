#!/bin/bash

echo "=========================================="
echo "  Verifying Project Structure"
echo "=========================================="
echo ""

ERRORS=0

# Check for files that SHOULD exist
REQUIRED_FILES=(
    "src/main/java/com/ratelimiter/DemoController.java"
    "src/main/java/com/ratelimiter/RateLimiterApplication.java"
    "src/main/java/com/ratelimiter/algorithms/SlidingWindowRateLimiter.java"
    "src/main/java/com/ratelimiter/algorithms/TokenBucketRateLimiter.java"
    "src/main/java/com/ratelimiter/config/RateLimiterConfig.java"
    "src/main/java/com/ratelimiter/core/RateLimitConfig.java"
    "src/main/java/com/ratelimiter/core/RateLimiter.java"
    "src/main/java/com/ratelimiter/storage/RateLimitStorage.java"
    "src/main/java/com/ratelimiter/storage/RedisRateLimitStorage.java"
    "src/main/java/com/ratelimiter/storage/StorageException.java"
    "pom.xml"
    "README.md"
)

echo "✓ Checking required files..."
for file in "${REQUIRED_FILES[@]}"; do
    if [ ! -f "$file" ]; then
        echo "  ✗ MISSING: $file"
        ((ERRORS++))
    fi
done

if [ $ERRORS -eq 0 ]; then
    echo "  ✓ All required files present"
fi

# Check for files that should NOT exist
FORBIDDEN_FILES=(
    "src/main/java/com/ratelimiter/Examples.java"
    "src/main/java/com/ratelimiter/RateLimiterBenchmark.java"
    "src/main/java/com/ratelimiter/core/RateLimiterBuilder.java"
    "src/main/java/com/ratelimiter/storage/RedisRateLimiter.java"
)

echo ""
echo "✓ Checking for problematic files..."
for file in "${FORBIDDEN_FILES[@]}"; do
    if [ -f "$file" ]; then
        echo "  ✗ FOUND (should not exist): $file"
        ((ERRORS++))
    fi
done

# Check for wrong directories
if [ -d "src/main/java/com/ratelimiter/strategy" ]; then
    echo "  ✗ FOUND wrong directory: src/main/java/com/ratelimiter/strategy (should be 'algorithms')"
    ((ERRORS++))
fi

if [ $ERRORS -eq 0 ]; then
    echo "  ✓ No problematic files found"
fi

# Summary
echo ""
echo "=========================================="
if [ $ERRORS -eq 0 ]; then
    echo "✅ VERIFICATION PASSED"
    echo "Your project structure is correct!"
    echo "Safe to commit and push to GitHub."
else
    echo "❌ VERIFICATION FAILED"
    echo "Found $ERRORS issue(s)"
    echo ""
    echo "DO NOT push to GitHub yet."
    echo "Delete the problematic files first."
fi
echo "=========================================="

exit $ERRORS
