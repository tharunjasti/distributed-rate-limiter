package com.ratelimiter.storage;

import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.util.List;

/**
 * Redis-backed storage implementation.
 * Handles connection pooling, retries, and failover scenarios.
 */
@Slf4j
public class RedisRateLimitStorage implements RateLimitStorage {
    
    private final JedisPool jedisPool;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 10;
    
    public RedisRateLimitStorage(String host, int port) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(128);
        poolConfig.setMaxIdle(32);
        poolConfig.setMinIdle(16);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setMaxWaitMillis(2000);
        
        this.jedisPool = new JedisPool(poolConfig, host, port);
        log.info("Redis storage initialized: {}:{}", host, port);
    }
    
    @Override
    public long incrementAndExpire(String key, Duration ttl) {
        return executeWithRetry(() -> {
            try (var jedis = jedisPool.getResource()) {
                var pipe = jedis.pipelined();
                var incrResp = pipe.incr(key);
                pipe.pexpire(key, ttl.toMillis());
                pipe.sync();
                
                return incrResp.get();
            }
        });
    }
    
    @Override
    public long get(String key) {
        return executeWithRetry(() -> {
            try (var jedis = jedisPool.getResource()) {
                String val = jedis.get(key);
                return val != null ? Long.parseLong(val) : 0;
            }
        });
    }
    
    @Override
    public void set(String key, long value, Duration ttl) {
        executeWithRetry(() -> {
            try (var jedis = jedisPool.getResource()) {
                SetParams params = new SetParams().px(ttl.toMillis());
                jedis.set(key, String.valueOf(value), params);
                return null;
            }
        });
    }
    
    @Override
    public boolean compareAndSet(String key, long expect, long update) {
        return executeWithRetry(() -> {
            try (var jedis = jedisPool.getResource()) {
                // Watch key for changes
                jedis.watch(key);
                String current = jedis.get(key);
                long currentVal = current != null ? Long.parseLong(current) : 0;
                
                if (currentVal != expect) {
                    jedis.unwatch();
                    return false;
                }
                
                var trans = jedis.multi();
                trans.set(key, String.valueOf(update));
                var result = trans.exec();
                return result != null && !result.isEmpty();
            }
        });
    }
    
    @Override
    public void delete(String key) {
        executeWithRetry(() -> {
            try (var jedis = jedisPool.getResource()) {
                jedis.del(key);
                return null;
            }
        });
    }
    
    @Override
    public void zAdd(String key, double score, String member) {
        executeWithRetry(() -> {
            try (var jedis = jedisPool.getResource()) {
                jedis.zadd(key, score, member);
                return null;
            }
        });
    }
    
    @Override
    public long zRemoveRangeByScore(String key, double min, double max) {
        return executeWithRetry(() -> {
            try (var jedis = jedisPool.getResource()) {
                return jedis.zremrangeByScore(key, min, max);
            }
        });
    }
    
    @Override
    public long zCount(String key, double min, double max) {
        return executeWithRetry(() -> {
            try (var jedis = jedisPool.getResource()) {
                return jedis.zcount(key, min, max);
            }
        });
    }
    
    @Override
    public Object evalScript(String script, List<String> keys, List<String> args) {
        return executeWithRetry(() -> {
            try (var jedis = jedisPool.getResource()) {
                return jedis.eval(script, keys, args);
            }
        });
    }
    
    @Override
    public boolean isAvailable() {
        try (var jedis = jedisPool.getResource()) {
            return "PONG".equals(jedis.ping());
        } catch (Exception e) {
            log.warn("Redis health check failed", e);
            return false;
        }
    }
    
    /**
     * Simple retry wrapper for transient failures
     * In production, you'd want exponential backoff and circuit breaker
     */
    private <T> T executeWithRetry(StorageOperation<T> operation) {
        Exception lastException = null;
        
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                return operation.execute();
            } catch (Exception e) {
                lastException = e;
                log.warn("Storage operation failed (attempt {}/{}): {}", 
                        i + 1, MAX_RETRIES, e.getMessage());
                
                if (i < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS * (i + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        throw new StorageException("Operation failed after " + MAX_RETRIES + " retries", lastException);
    }
    
    @FunctionalInterface
    private interface StorageOperation<T> {
        T execute() throws Exception;
    }
    
    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            log.info("Redis connection pool closed");
        }
    }
}
