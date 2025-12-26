package com.ratelimiter.benchmark;

import com.ratelimiter.algorithms.SlidingWindowRateLimiter;
import com.ratelimiter.algorithms.TokenBucketRateLimiter;
import com.ratelimiter.core.RateLimitConfig;
import com.ratelimiter.core.RateLimiter;
import com.ratelimiter.storage.RedisRateLimitStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance benchmarks for rate limiters.
 * 
 * Run these with Redis running locally:
 *   docker run -d -p 6379:6379 redis:alpine
 * 
 * Enable with: export RUN_BENCHMARKS=true
 */
@EnabledIfEnvironmentVariable(named = "RUN_BENCHMARKS", matches = "true")
public class RateLimiterBenchmark {
    
    private static RedisRateLimitStorage storage;
    private static SimpleMeterRegistry meterRegistry;
    
    @BeforeAll
    static void setup() {
        storage = new RedisRateLimitStorage("localhost", 6379);
        meterRegistry = new SimpleMeterRegistry();
        
        // Verify Redis is available
        if (!storage.isAvailable()) {
            throw new RuntimeException("Redis not available. Start with: docker run -p 6379:6379 redis:alpine");
        }
        
        System.out.println("=".repeat(80));
        System.out.println("RATE LIMITER PERFORMANCE BENCHMARKS");
        System.out.println("=".repeat(80));
    }
    
    @Test
    void benchmarkSlidingWindow_SingleKey() throws Exception {
        RateLimitConfig config = RateLimitConfig.builder()
                .maxPermits(100000)  // High limit for benchmark
                .window(Duration.ofMinutes(1))
                .enableLocalCache(true)
                .localCacheTtl(Duration.ofMillis(50))
                .build();
        
        RateLimiter limiter = new SlidingWindowRateLimiter(storage, config, meterRegistry);
        
        int numThreads = 10;
        int requestsPerThread = 10000;
        
        BenchmarkResult result = runBenchmark(
                "Sliding Window (Single Key, 10 threads)",
                limiter,
                "user123",
                numThreads,
                requestsPerThread
        );
        
        printResults(result);
    }
    
    @Test
    void benchmarkSlidingWindow_MultipleKeys() throws Exception {
        RateLimitConfig config = RateLimitConfig.builder()
                .maxPermits(1000)
                .window(Duration.ofSeconds(10))
                .enableLocalCache(true)
                .build();
        
        RateLimiter limiter = new SlidingWindowRateLimiter(storage, config, meterRegistry);
        
        int numThreads = 20;
        int requestsPerThread = 1000;
        
        BenchmarkResult result = runBenchmark(
                "Sliding Window (Multiple Keys, 20 threads)",
                limiter,
                null, // Use different key per thread
                numThreads,
                requestsPerThread
        );
        
        printResults(result);
    }
    
    @Test
    void benchmarkTokenBucket() throws Exception {
        RateLimitConfig config = RateLimitConfig.builder()
                .maxPermits(50000)
                .window(Duration.ofMinutes(1))
                .refillRate(10000.0) // 10k tokens/sec
                .build();
        
        RateLimiter limiter = new TokenBucketRateLimiter(storage, config, meterRegistry);
        
        int numThreads = 10;
        int requestsPerThread = 5000;
        
        BenchmarkResult result = runBenchmark(
                "Token Bucket (10 threads)",
                limiter,
                "user123",
                numThreads,
                requestsPerThread
        );
        
        printResults(result);
    }
    
    @Test
    void benchmarkLocalCacheImpact() throws Exception {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("LOCAL CACHE IMPACT COMPARISON");
        System.out.println("=".repeat(80));
        
        int numThreads = 10;
        int requestsPerThread = 5000;
        String key = "cache_test";
        
        // Without cache
        RateLimitConfig noCacheConfig = RateLimitConfig.builder()
                .maxPermits(100000)
                .window(Duration.ofMinutes(1))
                .enableLocalCache(false)
                .build();
        
        RateLimiter noCacheLimiter = new SlidingWindowRateLimiter(
                storage, noCacheConfig, meterRegistry);
        
        BenchmarkResult noCacheResult = runBenchmark(
                "Without Local Cache",
                noCacheLimiter,
                key,
                numThreads,
                requestsPerThread
        );
        
        // With cache
        RateLimitConfig cacheConfig = RateLimitConfig.builder()
                .maxPermits(100000)
                .window(Duration.ofMinutes(1))
                .enableLocalCache(true)
                .localCacheTtl(Duration.ofMillis(100))
                .build();
        
        RateLimiter cacheLimiter = new SlidingWindowRateLimiter(
                storage, cacheConfig, meterRegistry);
        
        BenchmarkResult cacheResult = runBenchmark(
                "With Local Cache (100ms TTL)",
                cacheLimiter,
                key,
                numThreads,
                requestsPerThread
        );
        
        printResults(noCacheResult);
        printResults(cacheResult);
        
        double speedup = (double) cacheResult.throughput / noCacheResult.throughput;
        System.out.printf("\nCache Speedup: %.2fx faster\n", speedup);
    }
    
    private BenchmarkResult runBenchmark(
            String name,
            RateLimiter limiter,
            String sharedKey,
            int numThreads,
            int requestsPerThread) throws Exception {
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);
        
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong totalLatency = new AtomicLong(0);
        List<Long> latencies = new CopyOnWriteArrayList<>();
        
        List<Future<?>> futures = new ArrayList<>();
        
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    
                    String key = sharedKey != null ? sharedKey : ("user_" + threadId);
                    
                    for (int j = 0; j < requestsPerThread; j++) {
                        long start = System.nanoTime();
                        boolean allowed = limiter.tryAcquire(key);
                        long end = System.nanoTime();
                        
                        long latencyNs = end - start;
                        totalLatency.addAndGet(latencyNs);
                        latencies.add(latencyNs);
                        
                        if (allowed) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            }));
        }
        
        // Start all threads
        long startTime = System.currentTimeMillis();
        startLatch.countDown();
        
        // Wait for completion
        endLatch.await();
        long endTime = System.currentTimeMillis();
        
        executor.shutdown();
        
        long durationMs = endTime - startTime;
        long totalRequests = (long) numThreads * requestsPerThread;
        long throughput = (totalRequests * 1000) / durationMs;
        double avgLatencyUs = (totalLatency.get() / (double) totalRequests) / 1000.0;
        
        // Calculate percentiles
        latencies.sort(Long::compareTo);
        long p50 = latencies.get(latencies.size() / 2) / 1000; // to microseconds
        long p95 = latencies.get((int) (latencies.size() * 0.95)) / 1000;
        long p99 = latencies.get((int) (latencies.size() * 0.99)) / 1000;
        
        return new BenchmarkResult(
                name,
                totalRequests,
                successCount.get(),
                durationMs,
                throughput,
                avgLatencyUs,
                p50,
                p95,
                p99
        );
    }
    
    private void printResults(BenchmarkResult result) {
        System.out.println("\n" + "-".repeat(80));
        System.out.println(result.name);
        System.out.println("-".repeat(80));
        System.out.printf("Total Requests:  %,d\n", result.totalRequests);
        System.out.printf("Successful:      %,d (%.1f%%)\n", 
                result.successful, 
                100.0 * result.successful / result.totalRequests);
        System.out.printf("Duration:        %,d ms\n", result.durationMs);
        System.out.printf("Throughput:      %,d req/sec\n", result.throughput);
        System.out.printf("Avg Latency:     %.2f μs\n", result.avgLatencyUs);
        System.out.printf("Latency p50:     %,d μs\n", result.p50);
        System.out.printf("Latency p95:     %,d μs\n", result.p95);
        System.out.printf("Latency p99:     %,d μs\n", result.p99);
    }
    
    private static class BenchmarkResult {
        String name;
        long totalRequests;
        long successful;
        long durationMs;
        long throughput;
        double avgLatencyUs;
        long p50, p95, p99;
        
        BenchmarkResult(String name, long total, long successful, long durationMs,
                       long throughput, double avgLatencyUs, long p50, long p95, long p99) {
            this.name = name;
            this.totalRequests = total;
            this.successful = successful;
            this.durationMs = durationMs;
            this.throughput = throughput;
            this.avgLatencyUs = avgLatencyUs;
            this.p50 = p50;
            this.p95 = p95;
            this.p99 = p99;
        }
    }
}
