package com.ratelimiter.core;

import lombok.Builder;
import lombok.Value;

import java.time.Duration;

/**
 * Configuration for rate limiting behavior.
 * Immutable to prevent accidental modifications after creation.
 */
@Value
@Builder
public class RateLimitConfig {
    
    /**
     * Maximum number of permits allowed in the time window
     */
    long maxPermits;
    
    /**
     * Time window duration for the rate limit
     */
    Duration window;
    
    /**
     * Refill rate for token bucket (permits per second)
     * Not used in sliding window algorithm
     */
    @Builder.Default
    double refillRate = 0.0;
    
    /**
     * Enable local caching to reduce Redis calls
     * Trades perfect accuracy for better performance
     */
    @Builder.Default
    boolean enableLocalCache = true;
    
    /**
     * How long to cache results locally before checking Redis again
     */
    @Builder.Default
    Duration localCacheTtl = Duration.ofMillis(100);
    
    public void validate() {
        if (maxPermits <= 0) {
            throw new IllegalArgumentException("maxPermits must be positive");
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be a positive duration");
        }
        if (refillRate < 0) {
            throw new IllegalArgumentException("refillRate cannot be negative");
        }
    }
    
    /**
     * Quick factory for common use cases
     */
    public static RateLimitConfig perSecond(long maxPermits) {
        return RateLimitConfig.builder()
                .maxPermits(maxPermits)
                .window(Duration.ofSeconds(1))
                .build();
    }
    
    public static RateLimitConfig perMinute(long maxPermits) {
        return RateLimitConfig.builder()
                .maxPermits(maxPermits)
                .window(Duration.ofMinutes(1))
                .build();
    }
    
    public static RateLimitConfig perHour(long maxPermits) {
        return RateLimitConfig.builder()
                .maxPermits(maxPermits)
                .window(Duration.ofHours(1))
                .build();
    }
}
