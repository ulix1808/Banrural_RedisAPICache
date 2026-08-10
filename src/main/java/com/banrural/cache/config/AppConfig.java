package com.banrural.cache.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import redis.clients.jedis.RedisClient;

@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class AppConfig {

    @Bean(destroyMethod = "close")
    public RedisClient redisClient(
            @Value("${redis.host}") String host,
            @Value("${redis.port}") int port,
            @Value("${redis.password:}") String password) {
        if (password != null && !password.isBlank()) {
            return RedisClient.create(host, port, null, password);
        }
        return RedisClient.create(host, port);
    }

    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }
}
