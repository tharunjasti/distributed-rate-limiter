package com.ratelimiter.config;

import com.ratelimiter.algorithms.SlidingWindowRateLimiter;
import com.ratelimiter.algorithms.TokenBucketRateLimiter;
import com.ratelimiter.core.RateLimitConfig;
import com.ratelimiter.core.RateLimiter;
import com.ratelimiter.storage.RateLimitStorage;
import com.ratelimiter.storage.RedisRateLimitStorage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Spring configuration for rate limiter components
 */
@Slf4j
@Configuration
public class RateLimiterConfig {
    
    @Value("${redis.host:localhost}")
    private String redisHost;
    
    @Value("${redis.port:6379}")
    private int redisPort;
    
    @Bean
    public RateLimitStorage rateLimitStorage() {
        log.info("Initializing Redis storage at {}:{}", redisHost, redisPort);
        return new RedisRateLimitStorage(redisHost, redisPort);
    }
    
    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
    
    /**
     * Default rate limiter for API endpoints
     * 100 requests per minute with sliding window
     */
    @Bean(name = "apiRateLimiter")
    public RateLimiter apiRateLimiter(
            RateLimitStorage storage,
            MeterRegistry meterRegistry) {
        
        RateLimitConfig config = RateLimitConfig.builder()
                .maxPermits(100)
                .window(Duration.ofMinutes(1))
                .enableLocalCache(true)
                .localCacheTtl(Duration.ofMillis(100))
                .build();
        
        return new SlidingWindowRateLimiter(storage, config, meterRegistry);
    }
    
    /**
     * Stricter rate limiter for auth endpoints
     * 10 login attempts per minute
     */
    @Bean(name = "authRateLimiter")
    public RateLimiter authRateLimiter(
            RateLimitStorage storage,
            MeterRegistry meterRegistry) {
        
        RateLimitConfig config = RateLimitConfig.builder()
                .maxPermits(10)
                .window(Duration.ofMinutes(1))
                .enableLocalCache(false) // More strict, no caching
                .build();
        
        return new SlidingWindowRateLimiter(storage, config, meterRegistry);
    }
    
    /**
     * Token bucket for burst-friendly scenarios
     * Allows bursts up to 50 requests, refills at 10/sec
     */
    @Bean(name = "burstRateLimiter")
    public RateLimiter burstRateLimiter(
            RateLimitStorage storage,
            MeterRegistry meterRegistry) {
        
        RateLimitConfig config = RateLimitConfig.builder()
                .maxPermits(50)
                .window(Duration.ofMinutes(1))
                .refillRate(10.0) // 10 tokens per second
                .build();
        
        return new TokenBucketRateLimiter(storage, config, meterRegistry);
    }
}
