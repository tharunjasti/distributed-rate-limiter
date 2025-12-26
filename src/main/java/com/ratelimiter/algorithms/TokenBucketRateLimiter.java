package com.ratelimiter.algorithms;

import com.ratelimiter.core.RateLimitConfig;
import com.ratelimiter.core.RateLimiter;
import com.ratelimiter.storage.RateLimitStorage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

/**
 * Token Bucket algorithm implementation.
 * 
 * Allows burst traffic while maintaining average rate limit.
 * Tokens are added at a constant rate (refillRate) up to bucket capacity.
 * 
 * Good for:
 * - APIs that need to allow occasional bursts
 * - Smoothing traffic patterns
 * - When you want "credits" to accumulate during idle periods
 * 
 * Uses Redis Lua script for atomic refill + consume operation.
 * This ensures distributed correctness without race conditions.
 */
@Slf4j
public class TokenBucketRateLimiter implements RateLimiter {
    
    private final RateLimitStorage storage;
    private final RateLimitConfig config;
    private final double refillRate; // tokens per millisecond
    private final Counter allowedRequests;
    private final Counter rejectedRequests;
    
    // Lua script for atomic token bucket operations
    // Returns: remaining tokens, or -1 if insufficient
    private static final String LUA_SCRIPT = 
            "local key = KEYS[1]\n" +
            "local capacity = tonumber(ARGV[1])\n" +
            "local refill_rate = tonumber(ARGV[2])\n" +
            "local requested = tonumber(ARGV[3])\n" +
            "local now = tonumber(ARGV[4])\n" +
            "local ttl = tonumber(ARGV[5])\n" +
            "\n" +
            "local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')\n" +
            "local tokens = tonumber(bucket[1])\n" +
            "local last_refill = tonumber(bucket[2])\n" +
            "\n" +
            "if tokens == nil then\n" +
            "  tokens = capacity\n" +
            "  last_refill = now\n" +
            "end\n" +
            "\n" +
            "-- Refill tokens based on elapsed time\n" +
            "local elapsed = now - last_refill\n" +
            "local tokens_to_add = elapsed * refill_rate\n" +
            "tokens = math.min(capacity, tokens + tokens_to_add)\n" +
            "\n" +
            "-- Try to consume requested tokens\n" +
            "if tokens >= requested then\n" +
            "  tokens = tokens - requested\n" +
            "  redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)\n" +
            "  redis.call('PEXPIRE', key, ttl)\n" +
            "  return {1, tokens}\n" +
            "else\n" +
            "  return {0, tokens}\n" +
            "end";
    
    public TokenBucketRateLimiter(
            RateLimitStorage storage,
            RateLimitConfig config,
            MeterRegistry meterRegistry) {
        
        config.validate();
        
        if (config.getRefillRate() <= 0) {
            throw new IllegalArgumentException(
                    "Token bucket requires positive refillRate. Use RateLimitConfig.builder().refillRate(...)");}
        
        this.storage = storage;
        this.config = config;
        
        // Convert to tokens per millisecond for precision
        this.refillRate = config.getRefillRate() / 1000.0;
        
        this.allowedRequests = Counter.builder("ratelimiter.tokenbucket.allowed")
                .description("Allowed requests (token bucket)")
                .register(meterRegistry);
        
        this.rejectedRequests = Counter.builder("ratelimiter.tokenbucket.rejected")
                .description("Rejected requests (token bucket)")
                .register(meterRegistry);
        
        log.info("TokenBucket initialized: capacity={}, refillRate={}/sec", 
                config.getMaxPermits(), config.getRefillRate());
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
        
        if (permits > config.getMaxPermits()) {
            // Can never fulfill this request
            log.warn("Requested permits ({}) exceeds bucket capacity ({})", 
                    permits, config.getMaxPermits());
            rejectedRequests.increment();
            return false;
        }
        
        String bucketKey = "tb:" + key;
        long now = System.currentTimeMillis();
        
        List<String> keys = Arrays.asList(bucketKey);
        List<String> args = Arrays.asList(
                String.valueOf(config.getMaxPermits()),
                String.valueOf(refillRate),
                String.valueOf(permits),
                String.valueOf(now),
                String.valueOf(config.getWindow().toMillis() * 2) // TTL: 2x window for safety
        );
        
        Object result = storage.evalScript(LUA_SCRIPT, keys, args);
        
        @SuppressWarnings("unchecked")
        List<Long> response = (List<Long>) result;
        boolean allowed = response.get(0) == 1;
        
        if (allowed) {
            allowedRequests.increment();
        } else {
            rejectedRequests.increment();
        }
        
        return allowed;
    }
    
    @Override
    public long getAvailablePermits(String key) {
        // This is an approximation - actual value depends on time since last refill
        String bucketKey = "tb:" + key;
        long tokens = storage.get(bucketKey);
        return tokens;
    }
    
    @Override
    public void reset(String key) {
        String bucketKey = "tb:" + key;
        storage.delete(bucketKey);
        log.debug("Reset token bucket for key: {}", key);
    }
}
