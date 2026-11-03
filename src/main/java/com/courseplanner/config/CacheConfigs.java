package com.courseplanner.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * 两套缓存实现，同一组缓存名，业务代码只写 @Cacheable。
 * 默认 Caffeine：单机进程内，零依赖，微秒级。
 * Redis：多实例/重启不丢，代价是一次网络往返。
 */
@Configuration
public class CacheConfigs {

    public static final String PROGRAM_DETAIL = "programDetail";

    @Bean
    @ConditionalOnProperty(name = "app.cache.mode", havingValue = "caffeine", matchIfMissing = true)
    public CacheManager caffeineCacheManager(AppProperties props) {
        CaffeineCacheManager manager = new CaffeineCacheManager(PROGRAM_DETAIL);
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(2_000)
                .expireAfterWrite(Duration.ofSeconds(props.getCache().getProgramTtlSeconds()))
                .recordStats());
        return manager;
    }

    @Bean
    @ConditionalOnProperty(name = "app.cache.mode", havingValue = "redis")
    public CacheManager redisCacheManager(RedisConnectionFactory factory, AppProperties props) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(props.getCache().getProgramTtlSeconds()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();
        return RedisCacheManager.builder(factory).cacheDefaults(base).build();
    }
}
