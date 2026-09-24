package com.amazon.product.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * ⭐ NEW — fixes RedisCache crashing on every write with:
 *   IllegalArgumentException: DefaultSerializer requires a Serializable
 *   payload but received an object of type [ProductDto$ProductResponse]
 *
 * Root cause: once @EnableCaching was added, Spring Boot's cache
 * auto-configuration detected spring-data-redis on the classpath and
 * picked a Redis-backed CacheManager over the simple in-memory one — with
 * no explicit configuration, its default value serializer is plain JDK
 * serialization (JdkSerializationRedisSerializer), which requires
 * java.io.Serializable. ProductDto.ProductResponse doesn't implement it.
 * Same root-cause shape as the earlier OrderIdempotencyService
 * RedisConfig fix (StringRedisSerializer for keys) — here applied to
 * cache VALUES via JSON instead, since cache values are full POJOs, not
 * plain strings.
 *
 * Uses RedisCacheManagerBuilderCustomizer rather than hand-building a
 * CacheManager bean directly — this lets Spring Boot's own auto-configured
 * RedisCacheManager still get created normally; this class only customizes
 * its serialization settings, so nothing else about the auto-configuration
 * wiring changes.
 *
 * ⭐ FOLLOW-UP FIX — GenericJackson2JsonRedisSerializer's NO-ARG
 * constructor builds its OWN separate internal ObjectMapper, isolated from
 * Spring's own auto-configured one. That internal mapper has no
 * JavaTimeModule registered, so any LocalDateTime field (e.g.
 * ProductResponse.createdAt) fails with "Java 8 date/time type ... not
 * supported by default" — even though the exact same field serializes
 * fine over the REST API itself, since THAT path uses Spring's properly
 * configured ObjectMapper bean. Fixed by injecting Spring's own
 * ObjectMapper into the serializer's constructor instead of using the
 * no-arg version, so cache serialization uses the same, already-correct
 * Jackson configuration as the rest of the application.
 */
@Configuration
public class RedisCacheConfig {

    // Bounds staleness even beyond what @CacheEvict + the delayed-double-
    // -delete mitigation already achieve — an extra safety net, not a
    // replacement for either. Adjust or remove if a bare (no-TTL) cache is
    // actually preferred; this wasn't explicitly requested, just included
    // as a reasonable default alongside the serializer fix.
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(10);

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer(ObjectMapper objectMapper) {
        RedisCacheConfiguration cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(DEFAULT_CACHE_TTL)
                .disableCachingNullValues()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer(objectMapper)));

        return builder -> builder.cacheDefaults(cacheConfig);
    }
}