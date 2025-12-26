package com.ratelimiter.algorithms;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ratelimiter.core.RateLimitConfig;
import com.ratelimiter.core.RateLimiter;
import com.ratelimiter.storage.RateLimitStorage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Sliding window counter implementation.
 * 
 * Combines fixed window buckets with weighted calculation to approximate
 * true sliding window behavior with much better memory efficiency.
 * 
 * How it works:
 * - Split time into fixed windows (buckets)
 * - Current bucket gets weight based on how much time has passed
 * - Previous bucket gets inverse weight
 * - Total = (prev_count * prev_weight) + (curr_count * curr_weight)
 * 
 * Example: 100 req/min, current time is 30s into minute
 * - prev_weight = 50% (30s remaining from prev window)
 * - curr_weight = 50% (30s passed in curr window)
 * 
 * Trade-off: Not perfectly accurate at window boundaries, but close enough
 * for most use cases and MUCH more efficient than sliding window log
 */
@Slf4j
public class SlidingWindowRateLimiter implements RateLimiter {
    
    private final RateLimitStorage storage;
    private final RateLimitConfig config;
    private final Cache<String, Long> localCache;
    
    // Metrics
    private final Counter allowedRequests;
    private final Counter rejectedRequests;
    private final Counter cacheHits;
    
    public SlidingWindowRateLimiter(
            RateLimitStorage storage,
            RateLimitConfig config,
            MeterRegistry meterRegistry) {
        
        config.validate();
        this.storage = storage;
        this.config = config;
        
        // Local cache to reduce Redis round trips
        // Short TTL to balance performance vs accuracy
        if (config.isEnableLocalCache()) {
            this.localCache = Caffeine.newBuilder()
                    .expireAfterWrite(config.getLocalCacheTtl().toMillis(), TimeUnit.MILLISECONDS)
                    .maximumSize(10000)
                    .build();
        } else {
            this.localCache = null;
        }
        
        // Set up metrics
        this.allowedRequests = Counter.builder("ratelimiter.requests.allowed")
                .description("Number of allowed requests")
                .register(meterRegistry);
        
        this.rejectedRequests = Counter.builder("ratelimiter.requests.rejected")
                .description("Number of rejected requests")
                .register(meterRegistry);
        
        this.cacheHits = Counter.builder("ratelimiter.cache.hits")
                .description("Number of local cache hits")
                .register(meterRegistry);
    }
    
    @Override
    public boolean tryAcquire(String key) {
        return tryAcquire(key, 1);
    }
    
    @Override
    public boolean tryAcquire(String key, int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive");
        }
        
        // Quick check: if we recently rejected this key, reject again
        // Reduces load on Redis during attacks
        if (localCache != null) {
            Long cachedCount = localCache.getIfPresent(key);
            if (cachedCount != null && cachedCount >= config.getMaxPermits()) {
                cacheHits.increment();
                rejectedRequests.increment();
                return false;
            }
        }
        
        long currentCount = getCurrentCount(key);
        
        if (currentCount + permits > config.getMaxPermits()) {
            // Cache the rejection to avoid hammering Redis
            if (localCache != null) {
                localCache.put(key, currentCount);
            }
            rejectedRequests.increment();
            return false;
        }
        
        // Increment counter atomically
        long windowMs = config.getWindow().toMillis();
        String currentKey = getWindowKey(key, System.currentTimeMillis(), windowMs);
        long newCount = storage.incrementAndExpire(currentKey, config.getWindow());
        
        // Update local cache with new value
        if (localCache != null) {
            localCache.put(key, newCount);
        }
        
        boolean allowed = newCount <= config.getMaxPermits();
        if (allowed) {
            allowedRequests.increment();
        } else {
            rejectedRequests.increment();
        }
        
        return allowed;
    }
    
    @Override
    public long getAvailablePermits(String key) {
        long current = getCurrentCount(key);
        return Math.max(0, config.getMaxPermits() - current);
    }
    
    @Override
    public void reset(String key) {
        long now = System.currentTimeMillis();
        long windowMs = config.getWindow().toMillis();
        
        // Clear current and previous windows
        storage.delete(getWindowKey(key, now, windowMs));
        storage.delete(getWindowKey(key, now - windowMs, windowMs));
        
        if (localCache != null) {
            localCache.invalidate(key);
        }
        
        log.debug("Reset rate limit for key: {}", key);
    }
    
    /**
     * Calculate current request count using sliding window formula
     */
    private long getCurrentCount(String key) {
        long now = System.currentTimeMillis();
        long windowMs = config.getWindow().toMillis();
        
        // Get counts from current and previous windows
        String currKey = getWindowKey(key, now, windowMs);
        String prevKey = getWindowKey(key, now - windowMs, windowMs);
        
        long currCount = storage.get(currKey);
        long prevCount = storage.get(prevKey);
        
        // Calculate weights based on position in current window
        double percentageInCurrWindow = (double) (now % windowMs) / windowMs;
        double prevWeight = 1.0 - percentageInCurrWindow;
        
        // Weighted sum approximates true sliding window
        long estimated = (long) (prevCount * prevWeight + currCount);
        
        log.trace("Sliding window for {}: curr={}, prev={}, weight={}, total={}", 
                key, currCount, prevCount, prevWeight, estimated);
        
        return estimated;
    }
    
    /**
     * Generate Redis key for a specific time window
     */
    private String getWindowKey(String key, long timestampMs, long windowMs) {
        long windowStart = (timestampMs / windowMs) * windowMs;
        return String.format("rl:%s:%d", key, windowStart);
    }
}
