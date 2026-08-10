package com.banrural.cache.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "banrural.cache")
public record CacheProperties(
        long catalogTtlSeconds,
        String keyPrefix
) {
}
