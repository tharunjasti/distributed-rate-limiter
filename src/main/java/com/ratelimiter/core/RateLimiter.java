package com.ratelimiter.core;

/**
 * Core interface for rate limiting operations.
 * Implementations can use different algorithms (token bucket, sliding window, etc.)
 */
public interface RateLimiter {
    
    /**
     * Try to acquire a permit for the given key.
     * Returns immediately without blocking.
     *
     * @param key unique identifier (user_id, api_key, ip_address, etc.)
     * @return true if permit acquired, false if rate limit exceeded
     */
    boolean tryAcquire(String key);
    
    /**
     * Try to acquire multiple permits atomically.
     * Useful for batch operations or cost-based rate limiting.
     *
     * @param key unique identifier
     * @param permits number of permits to acquire
     * @return true if all permits acquired, false otherwise
     */
    boolean tryAcquire(String key, int permits);
    
    /**
     * Get remaining permits for a key.
     * Useful for client-side feedback (e.g., "3 requests remaining")
     *
     * @param key unique identifier
     * @return number of permits available, or -1 if unable to determine
     */
    long getAvailablePermits(String key);
    
    /**
     * Reset rate limit for a specific key.
     * Use carefully - mainly for testing or admin overrides.
     *
     * @param key unique identifier
     */
    void reset(String key);
}
