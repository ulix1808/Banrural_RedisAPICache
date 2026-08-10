package com.banrural.cache.model;

/**
 * Resultado de una consulta con información de si vino de Redis o del API original.
 */
public record CachedApiResult(
        String data,
        boolean fromCache,
        String cacheKey,
        Long ttlRemainingSeconds
) {
    public static CachedApiResult fromApi(String data, String cacheKey) {
        return new CachedApiResult(data, false, cacheKey, null);
    }

    public static CachedApiResult fromCache(String data, String cacheKey, Long ttlRemainingSeconds) {
        return new CachedApiResult(data, true, cacheKey, ttlRemainingSeconds);
    }
}
