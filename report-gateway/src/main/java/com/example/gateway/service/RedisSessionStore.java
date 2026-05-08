package com.example.gateway.service;

import com.example.gateway.config.SessionConfig;
import com.example.gateway.model.ConversationSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Redis implementation of SessionStore for production use.
 * Uses JSON serialization for ConversationSession storage.
 */
@Service
@ConditionalOnProperty(name = "session.storage.type", havingValue = "redis")
public class RedisSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionStore.class);
    private static final String KEY_PREFIX = "session:";

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final Duration defaultTtl;

    public RedisSessionStore(ReactiveRedisTemplate<String, Object> redisTemplate,
                             SessionConfig sessionConfig) {
        this.redisTemplate = redisTemplate;
        this.defaultTtl = sessionConfig.getTimeout();
        log.info("RedisSessionStore initialized with TTL={} minutes", defaultTtl.toMinutes());
    }

    @Override
    public Mono<ConversationSession> find(String sessionId) {
        String key = buildKey(sessionId);
        return redisTemplate.opsForValue()
                .get(key)
                .cast(ConversationSession.class)
                .flatMap(session -> {
                    // Update activity time and reset TTL
                    ConversationSession updated = session.updateActivity();
                    return save(updated).thenReturn(updated);
                })
                .doOnSuccess(session -> log.debug("Session found: {} ({} turns)",
                        sessionId, session.turns().size()))
                .doOnError(e -> log.error("Error finding session {}: {}", sessionId, e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    @Override
    public Mono<ConversationSession> save(ConversationSession session) {
        String key = buildKey(session.sessionId());
        return redisTemplate.opsForValue()
                .set(key, session, defaultTtl)
                .thenReturn(session)
                .doOnSuccess(s -> log.debug("Session saved: {} ({} turns, TTL={}min)",
                        session.sessionId(), session.turns().size(), defaultTtl.toMinutes()));
    }

    @Override
    public Mono<Boolean> delete(String sessionId) {
        String key = buildKey(sessionId);
        return redisTemplate.opsForValue()
                .delete(key)
                .cast(Boolean.class)
                .doOnSuccess(result -> {
                    if (Boolean.TRUE.equals(result)) {
                        log.info("Session deleted: {}", sessionId);
                    } else {
                        log.debug("Session not found for delete: {}", sessionId);
                    }
                });
    }

    @Override
    public Mono<Boolean> exists(String sessionId) {
        String key = buildKey(sessionId);
        return redisTemplate.hasKey(key)
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<Boolean> expire(String sessionId, Duration ttl) {
        String key = buildKey(sessionId);
        return redisTemplate.expire(key, ttl)
                .defaultIfEmpty(false);
    }

    private String buildKey(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
