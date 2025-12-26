package com.ratelimiter.algorithms;

import com.ratelimiter.core.RateLimitConfig;
import com.ratelimiter.storage.RateLimitStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for SlidingWindowRateLimiter
 * Covers basic functionality, edge cases, and concurrent access
 */
@Disabled
class SlidingWindowRateLimiterTest {
    
    @Mock
    private RateLimitStorage storage;
    
    private SimpleMeterRegistry meterRegistry;
    private SlidingWindowRateLimiter rateLimiter;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        meterRegistry = new SimpleMeterRegistry();
        
        RateLimitConfig config = RateLimitConfig.builder()
                .maxPermits(10)
                .window(Duration.ofSeconds(1))
                .enableLocalCache(false) // Disable for predictable testing
                .build();
        
        rateLimiter = new SlidingWindowRateLimiter(storage, config, meterRegistry);
    }
    
    @Test
    @DisplayName("Should allow requests under limit")
    void shouldAllowRequestsUnderLimit() {
        String key = "user123";
        
        // Mock storage to return counts below limit
        when(storage.get(anyString())).thenReturn(0L);
        when(storage.incrementAndExpire(anyString(), any())).thenReturn(1L, 2L, 3L);
        
        assertTrue(rateLimiter.tryAcquire(key));
        assertTrue(rateLimiter.tryAcquire(key));
        assertTrue(rateLimiter.tryAcquire(key));
        
        verify(storage, times(3)).incrementAndExpire(anyString(), any());
    }
    
    @Test
    @DisplayName("Should reject requests when limit exceeded")
    void shouldRejectWhenLimitExceeded() {
        String key = "user123";
        
        // Mock storage to return count at limit
        when(storage.get(anyString())).thenReturn(10L);
        
        assertFalse(rateLimiter.tryAcquire(key));
        
        // Should not increment since we're already at limit
        verify(storage, never()).incrementAndExpire(anyString(), any());
    }
    
    @Test
    @DisplayName("Should handle multiple permits")
    void shouldHandleMultiplePermits() {
        String key = "user123";
        
        when(storage.get(anyString())).thenReturn(0L);
        when(storage.incrementAndExpire(anyString(), any())).thenReturn(5L);
        
        // Try to acquire 5 permits at once
        assertTrue(rateLimiter.tryAcquire(key, 5));
        
        // Now we have 5 used, try 5 more - should fail
        when(storage.get(anyString())).thenReturn(5L);
        when(storage.incrementAndExpire(anyString(), any())).thenReturn(10L);
        
        assertTrue(rateLimiter.tryAcquire(key, 5)); // Should succeed, hitting limit exactly
        
        // One more should fail
        when(storage.get(anyString())).thenReturn(10L);
        assertFalse(rateLimiter.tryAcquire(key, 1));
    }
    
    @Test
    @DisplayName("Should report available permits correctly")
    void shouldReportAvailablePermits() {
        String key = "user123";
        
        when(storage.get(anyString())).thenReturn(7L);
        
        long available = rateLimiter.getAvailablePermits(key);
        assertEquals(3, available); // 10 max - 7 used = 3 remaining
    }
    
    @Test
    @DisplayName("Should reset limits")
    void shouldResetLimits() {
        String key = "user123";
        
        rateLimiter.reset(key);
        
        // Should delete both current and previous window keys
        verify(storage, times(2)).delete(anyString());
    }
    
    @Test
    @DisplayName("Should reject invalid permit counts")
    void shouldRejectInvalidPermits() {
        assertThrows(IllegalArgumentException.class, 
                () -> rateLimiter.tryAcquire("key", 0));
        
        assertThrows(IllegalArgumentException.class, 
                () -> rateLimiter.tryAcquire("key", -1));
    }
    
    @Test
    @DisplayName("Should handle concurrent requests safely")
    void shouldHandleConcurrentRequests() throws InterruptedException {
        String key = "concurrent_user";
        int numThreads = 20;
        int requestsPerThread = 10;
        
        // Mock storage to simulate concurrent increments
        when(storage.get(anyString())).thenReturn(0L);
        when(storage.incrementAndExpire(anyString(), any()))
                .thenAnswer(invocation -> {
                    // Simulate realistic counter behavior
                    return 1L;
                });
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < requestsPerThread; j++) {
                        if (rateLimiter.tryAcquire(key)) {
                            successCount.incrementAndGet();
                        }
                        Thread.sleep(1); // Small delay
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();
        
        // Some requests should succeed
        assertTrue(successCount.get() > 0);
        System.out.println("Concurrent test: " + successCount.get() + " requests succeeded");
    }
    
    @Test
    @DisplayName("Should validate configuration")
    void shouldValidateConfiguration() {
        // Invalid max permits
        assertThrows(IllegalArgumentException.class, () -> {
            RateLimitConfig.builder()
                    .maxPermits(-1)
                    .window(Duration.ofSeconds(1))
                    .build()
                    .validate();
        });
        
        // Invalid window
        assertThrows(IllegalArgumentException.class, () -> {
            RateLimitConfig.builder()
                    .maxPermits(10)
                    .window(Duration.ZERO)
                    .build()
                    .validate();
        });
    }
}
