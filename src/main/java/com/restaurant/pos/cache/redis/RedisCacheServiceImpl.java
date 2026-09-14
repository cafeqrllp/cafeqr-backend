package com.restaurant.pos.cache.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Robust implementation of {@link RedisCacheService} backed by {@link StringRedisTemplate},
 * Jackson JSON serialization, and {@link RedisCircuitBreaker}.
 */
@Slf4j
@Service
public class RedisCacheServiceImpl implements RedisCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RedisCircuitBreaker circuitBreaker;
    private final RedisScanService scanService;

    public RedisCacheServiceImpl(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RedisCircuitBreaker circuitBreaker,
            RedisScanService scanService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.scanService = scanService;
    }

    @Override
    public boolean isAvailable() {
        return redisTemplate != null && circuitBreaker.isAvailable();
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> clazz) {
        if (!isAvailable() || key == null || key.isBlank()) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(objectMapper.readValue(json, clazz));
        } catch (Exception ex) {
            circuitBreaker.trip("GET", key, ex);
            return Optional.empty();
        }
    }

    @Override
    public <T> Optional<T> get(String key, TypeReference<T> typeRef) {
        if (!isAvailable() || key == null || key.isBlank()) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(objectMapper.readValue(json, typeRef));
        } catch (Exception ex) {
            circuitBreaker.trip("GET", key, ex);
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> getString(String key) {
        if (!isAvailable() || key == null || key.isBlank()) {
            return Optional.empty();
        }
        try {
            String val = redisTemplate.opsForValue().get(key);
            if (val == null || val.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(val);
        } catch (Exception ex) {
            circuitBreaker.trip("GET_STRING", key, ex);
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        if (!isAvailable() || key == null || value == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            if (ttl != null) {
                redisTemplate.opsForValue().set(key, json, ttl);
            } else {
                redisTemplate.opsForValue().set(key, json);
            }
        } catch (Exception ex) {
            circuitBreaker.trip("PUT", key, ex);
        }
    }

    @Override
    public void putString(String key, String value, Duration ttl) {
        if (!isAvailable() || key == null || value == null) {
            return;
        }
        try {
            if (ttl != null) {
                redisTemplate.opsForValue().set(key, value, ttl);
            } else {
                redisTemplate.opsForValue().set(key, value);
            }
        } catch (Exception ex) {
            circuitBreaker.trip("PUT_STRING", key, ex);
        }
    }

    @Override
    public boolean delete(String key) {
        if (!isAvailable() || key == null || key.isBlank()) {
            return false;
        }
        try {
            Boolean deleted = redisTemplate.delete(key);
            return Boolean.TRUE.equals(deleted);
        } catch (Exception ex) {
            circuitBreaker.trip("DELETE", key, ex);
            return false;
        }
    }

    @Override
    public long deleteByPrefix(String prefix) {
        return scanService.deleteByPrefix(prefix);
    }

    @Override
    public Long increment(String key) {
        if (!isAvailable() || key == null || key.isBlank()) {
            return null;
        }
        try {
            return redisTemplate.opsForValue().increment(key);
        } catch (Exception ex) {
            circuitBreaker.trip("INCREMENT", key, ex);
            return null;
        }
    }
}
