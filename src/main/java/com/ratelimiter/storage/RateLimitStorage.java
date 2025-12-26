package com.ratelimiter.storage;

import java.time.Duration;
import java.util.List;

/**
 * Abstraction over distributed storage (Redis, Memcached, etc.)
 * Allows swapping backends without changing rate limiter logic
 */
public interface RateLimitStorage {
    
    /**
     * Increment a counter and set TTL atomically.
     * Used by sliding window counters.
     *
     * @param key storage key
     * @param ttl time-to-live for the key
     * @return new value after increment
     */
    long incrementAndExpire(String key, Duration ttl);
    
    /**
     * Get current value of a counter
     */
    long get(String key);
    
    /**
     * Set a value with expiration
     */
    void set(String key, long value, Duration ttl);
    
    /**
     * Atomic compare-and-set operation
     * Returns true if value was updated
     */
    boolean compareAndSet(String key, long expect, long update);
    
    /**
     * Delete a key
     */
    void delete(String key);
    
    /**
     * Add entry to a sorted set (for sliding window log)
     * Score is typically timestamp
     */
    void zAdd(String key, double score, String member);
    
    /**
     * Remove entries from sorted set with score less than min
     * Returns number of elements removed
     */
    long zRemoveRangeByScore(String key, double min, double max);
    
    /**
     * Count entries in sorted set within score range
     */
    long zCount(String key, double min, double max);
    
    /**
     * Execute Lua script atomically
     * Critical for maintaining consistency in distributed env
     */
    Object evalScript(String script, List<String> keys, List<String> args);
    
    /**
     * Health check
     */
    boolean isAvailable();
}
