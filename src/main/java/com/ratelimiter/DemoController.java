package com.ratelimiter;

import com.ratelimiter.core.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Demo API controller showing different rate limiting strategies
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class DemoController {
    
    private final RateLimiter apiRateLimiter;
    private final RateLimiter authRateLimiter;
    private final RateLimiter burstRateLimiter;
    
    public DemoController(
            @Qualifier("apiRateLimiter") RateLimiter apiRateLimiter,
            @Qualifier("authRateLimiter") RateLimiter authRateLimiter,
            @Qualifier("burstRateLimiter") RateLimiter burstRateLimiter) {
        
        this.apiRateLimiter = apiRateLimiter;
        this.authRateLimiter = authRateLimiter;
        this.burstRateLimiter = burstRateLimiter;
    }
    
    /**
     * Standard API endpoint with 100 req/min limit
     * Rate limited by client IP
     */
    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> getData(
            @RequestHeader(value = "X-User-ID", required = false) String userId) {
        
        String key = userId != null ? userId : "anonymous";
        
        if (!apiRateLimiter.tryAcquire(key)) {
            return rateLimitExceeded(apiRateLimiter, key);
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Success!");
        response.put("remaining", apiRateLimiter.getAvailablePermits(key));
        response.put("data", Map.of("timestamp", System.currentTimeMillis()));
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Auth endpoint with strict 10 req/min limit
     * Prevents brute force attacks
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, String> credentials) {
        
        String username = credentials.getOrDefault("username", "unknown");
        
        if (!authRateLimiter.tryAcquire(username)) {
            log.warn("Rate limit exceeded for login attempts: {}", username);
            return rateLimitExceeded(authRateLimiter, username);
        }
        
        // Simulate auth logic
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Login successful");
        response.put("remaining_attempts", authRateLimiter.getAvailablePermits(username));
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Batch endpoint using token bucket
     * Allows bursts but maintains average rate
     */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> processBatch(
            @RequestBody Map<String, Object> batch,
            @RequestHeader("X-User-ID") String userId) {
        
        int batchSize = (int) batch.getOrDefault("size", 1);
        
        // Try to acquire multiple permits at once
        if (!burstRateLimiter.tryAcquire(userId, batchSize)) {
            return rateLimitExceeded(burstRateLimiter, userId);
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Batch processed");
        response.put("items_processed", batchSize);
        response.put("tokens_remaining", burstRateLimiter.getAvailablePermits(userId));
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Health check endpoint (not rate limited)
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> status = new HashMap<>();
        status.put("status", "UP");
        status.put("timestamp", String.valueOf(System.currentTimeMillis()));
        return ResponseEntity.ok(status);
    }
    
    /**
     * Admin endpoint to reset rate limit for a user
     */
    @DeleteMapping("/admin/reset/{userId}")
    public ResponseEntity<Map<String, String>> resetUserLimit(@PathVariable String userId) {
        apiRateLimiter.reset(userId);
        authRateLimiter.reset(userId);
        burstRateLimiter.reset(userId);
        
        Map<String, String> response = new HashMap<>();
        response.put("message", "Rate limits reset for user: " + userId);
        return ResponseEntity.ok(response);
    }
    
    private ResponseEntity<Map<String, Object>> rateLimitExceeded(
            RateLimiter limiter, String key) {
        
        Map<String, Object> error = new HashMap<>();
        error.put("error", "Rate limit exceeded");
        error.put("message", "Too many requests. Please try again later.");
        error.put("remaining", limiter.getAvailablePermits(key));
        
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .body(error);
    }
}
