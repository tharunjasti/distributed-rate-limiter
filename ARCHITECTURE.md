# Architecture Deep Dive

## Design Goals

1. **Distributed Correctness**: Multiple app instances must enforce the same rate limit
2. **High Performance**: Support 100K+ requests/second per instance
3. **Low Latency**: p99 latency under 1ms for cached path
4. **Fault Tolerance**: Degrade gracefully when Redis is unavailable
5. **Flexible**: Support multiple algorithms and use cases

## Key Design Decisions

### 1. Sliding Window vs Fixed Window

**Why Sliding Window?**

Fixed windows have a critical flaw:

```
Fixed Window Problem:
┌────────┬────────┐
│ Win 1  │ Win 2  │  Limit: 10/min
└────────┴────────┘
    100 req│    100 req  ← Spike of 200 in 1 second!
```

Sliding window smooths this out by weighting previous + current windows.

**Implementation:**
```java
current_weight = (current_time % window_size) / window_size
prev_weight = 1 - current_weight
total = (prev_count * prev_weight) + current_count
```

**Trade-off:** ~5% inaccuracy at boundaries vs true sliding log
- Memory: O(1) instead of O(n) per key
- Acceptable for 99% of use cases

### 2. Two-Tier Caching Strategy

```
Request Flow:
1. Check local cache (Caffeine) ──┐
   ├─ Hit → Fast path (no Redis)  │ ~70% of requests
   └─ Miss → Check Redis ──────────┘
```

**Why Caffeine?**
- High-performance Java cache (10M+ ops/sec)
- Window TinyLFU eviction (better than LRU)
- Built-in expiration

**Cache TTL Selection:**
- Too high → Stale data, inaccurate limits
- Too low → Excessive Redis calls
- Sweet spot: 50-100ms for most APIs

**Accuracy Impact:**
```
No cache:     100% accurate
50ms cache:   ~99.5% accurate
100ms cache:  ~99% accurate
500ms cache:  ~95% accurate
```

### 3. Redis Operations - Atomic Updates

**Challenge:** Race condition in check-then-increment:
```java
// ❌ WRONG - Race condition
if (redis.get(key) < limit) {
    redis.incr(key);  // Another thread could sneak in here
}
```

**Solution:** Atomic increment with expiry
```java
// ✅ CORRECT - Single atomic operation
count = redis.eval("""
    local count = redis.call('INCR', KEYS[1])
    if count == 1 then
        redis.call('PEXPIRE', KEYS[1], ARGV[1])
    end
    return count
""", [key], [ttl])
```

### 4. Token Bucket Implementation

**Why Lua Scripts?**

Token bucket requires:
1. Read current tokens
2. Calculate refill based on time
3. Update tokens and timestamp

All must be atomic in distributed env.

**Lua Script Benefits:**
- Single round-trip to Redis
- Atomic execution guaranteed
- No race conditions

**Alternative Considered:** Redis Streams
- More complex
- Higher memory overhead
- No clear benefit for this use case

### 5. Connection Pooling Strategy

```java
JedisPoolConfig poolConfig = new JedisPoolConfig();
poolConfig.setMaxTotal(128);     // Max connections
poolConfig.setMaxIdle(32);       // Keep 32 idle
poolConfig.setMinIdle(16);       // Always have 16 ready
poolConfig.setMaxWaitMillis(2000); // Timeout if pool exhausted
```

**Sizing Calculation:**
```
Expected QPS: 50,000
Avg latency:  1ms
Required connections: (50000 * 0.001) * safety_factor
                    ≈ 50 * 2 = 100 connections
```

### 6. Error Handling Strategy

**Philosophy:** Fail open vs fail closed

```java
try {
    return rateLimiter.tryAcquire(key);
} catch (StorageException e) {
    // Decision point:
    // Fail open  → Allow request (risk overload)
    // Fail closed → Deny request (risk false negatives)
    
    // Our choice: Fail open with logging
    log.error("Rate limiter error - allowing request", e);
    return true;
}
```

**Reasoning:**
- Better UX during partial outages
- Monitoring alerts on errors
- Can be overridden per endpoint

### 7. Retry Logic

**Simple exponential backoff:**
```java
for (int attempt = 1; attempt <= 3; attempt++) {
    try {
        return operation.execute();
    } catch (Exception e) {
        if (attempt < 3) {
            Thread.sleep(10 * attempt); // 10ms, 20ms, 30ms
        }
    }
}
```

**Why not more sophisticated?**
- Redis operations are fast (< 1ms)
- Transient failures are rare
- Don't want to increase latency tail
- Circuit breaker added if needed

### 8. Metrics & Observability

**Key Metrics:**
```
ratelimiter.requests.allowed   - Successful requests
ratelimiter.requests.rejected  - Rate limited requests
ratelimiter.cache.hits         - Local cache hit rate
ratelimiter.storage.latency    - Redis latency
```

**Why Micrometer?**
- Vendor-neutral (works with Prometheus, Datadog, etc.)
- Low overhead
- Spring Boot integration

## Performance Characteristics

### Latency Breakdown

```
Cold path (cache miss):
├─ Caffeine lookup: 50ns
├─ Redis call:     800μs
└─ Total:          ~800μs

Hot path (cache hit):
├─ Caffeine lookup: 50ns
└─ Total:          ~50ns  ← 16,000x faster!
```

### Memory Usage

Per key memory usage:

```
Sliding Window:
- Redis:  ~100 bytes (2 counters + metadata)
- Local:  ~200 bytes (Caffeine overhead)
- Total:  ~300 bytes per unique key

Token Bucket:
- Redis:  ~120 bytes (hash with 2 fields)
- Local:  Not cached
- Total:  ~120 bytes per unique key
```

For 1M active users:
- Sliding Window: ~300 MB
- Token Bucket: ~120 MB

### Scalability Limits

**Theoretical:**
- Redis: 100K+ ops/sec (single instance)
- Local cache: 10M+ ops/sec
- Network: Usually the bottleneck

**Observed (single instance):**
- Without cache: ~25K req/sec
- With cache: ~80K req/sec
- With cluster: ~500K+ req/sec

## Alternative Approaches Considered

### 1. Database-backed rate limiting
❌ Too slow (10-50ms latency)  
❌ Scaling challenges  
✅ Good for: Billing, quota tracking

### 2. In-memory only (no Redis)
❌ Not distributed  
❌ Loses state on restart  
✅ Good for: Single-instance apps

### 3. Memcached instead of Redis
❌ No atomic operations  
❌ No Lua scripts  
✅ Good for: Simple caching

### 4. Sliding Window Log (exact)
❌ Memory intensive O(n)  
❌ Slower  
✅ Good for: Critical financial transactions

## Production Deployment

### Redis Setup

**Option 1: Redis Sentinel** (HA)
```yaml
Sentinel:
  - Master: writes
  - Replicas: read scaling
  - Sentinels: automatic failover
  
Failover time: ~1-3 seconds
```

**Option 2: Redis Cluster** (Sharding + HA)
```yaml
Cluster:
  - 3+ master nodes
  - 3+ replica nodes
  - Automatic sharding
  
Failover time: ~1-2 seconds
```

### Monitoring Queries

```sql
-- Rate limit violation rate
SELECT 
  rate(ratelimiter_requests_rejected[5m]) / 
  rate(ratelimiter_requests_total[5m]) as rejection_rate

-- Cache effectiveness
SELECT 
  rate(ratelimiter_cache_hits[5m]) / 
  rate(ratelimiter_requests_total[5m]) as cache_hit_rate

-- Redis latency
SELECT 
  histogram_quantile(0.99, ratelimiter_storage_latency_bucket)
```

### Capacity Planning

For 100K req/sec:
- Redis: 3-node cluster
- App instances: 5-10 (with load balancer)
- Network: 1 Gbps
- Estimated cost: ~$500/month (AWS)

## Future Optimizations

1. **Bloom filters** for negative caching
2. **Consistent hashing** for better key distribution
3. **gRPC** for lower latency
4. **Batch operations** for bulk checks
5. **Adaptive caching** based on access patterns

## Questions for Interviewers

When discussing this project:
1. How would you handle Redis failover scenarios?
2. What's the trade-off between accuracy and performance?
3. How would you test this under load?
4. What metrics would you monitor in production?
5. How would you handle distributed clock skew?
