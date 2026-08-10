package com.banrural.cache.service;

import com.banrural.cache.config.CacheProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.RedisClient;

@Service
public class RedisCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    private final RedisClient redis;
    private final CacheProperties cacheProperties;

    public RedisCacheService(RedisClient redis, CacheProperties cacheProperties) {
        this.redis = redis;
        this.cacheProperties = cacheProperties;
    }

    public String get(String key) {
        String fullKey = buildKey(key);
        String value = redis.get(fullKey);
        log.debug("{} → key={}", value != null ? "CACHE HIT" : "CACHE MISS", fullKey);
        return value;
    }

    public void put(String key, String value, long ttlSeconds) {
        String fullKey = buildKey(key);
        redis.setex(fullKey, ttlSeconds, value);
        log.debug("CACHE SET → key={}, ttl={}s", fullKey, ttlSeconds);
    }

    public void evict(String key) {
        String fullKey = buildKey(key);
        redis.del(fullKey);
        log.info("CACHE EVICT → key={}", fullKey);
    }

    public Long getTtlRemaining(String key) {
        long ttl = redis.ttl(buildKey(key));
        return ttl > 0 ? ttl : null;
    }

    private String buildKey(String key) {
        return cacheProperties.keyPrefix() + key;
    }
}
