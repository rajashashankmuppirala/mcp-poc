package com.example.gateway.service;

import com.example.gateway.model.ConversationSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-memory implementation of SessionStore for local development.
 * Uses ConcurrentHashMap for thread-safe storage.
 * Includes automatic cleanup of expired sessions.
 */
@Service
@ConditionalOnProperty(name = "session.storage.type", havingValue = "in-memory", matchIfMissing = true)
public class InMemorySessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(InMemorySessionStore.class);

    private final ConcurrentHashMap<String, ConversationSession> sessions = new ConcurrentHashMap<>();
    private final Duration defaultTtl;
    private final ScheduledExecutorService cleanupExecutor;

    public InMemorySessionStore(org.springframework.core.env.Environment env) {
        // Read TTL from config, default to 30 minutes
        String ttlMinutes = env.getProperty("session.timeout-minutes", "30");
        this.defaultTtl = Duration.ofMinutes(Long.parseLong(ttlMinutes));

        // Schedule cleanup every 5 minutes
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupExecutor.scheduleAtFixedRate(
                this::cleanupExpiredSessions, 5, 5, TimeUnit.MINUTES);

        log.info("InMemorySessionStore initialized with TTL={} minutes", defaultTtl.toMinutes());
    }

    @Override
    public Mono<ConversationSession> find(String sessionId) {
        return Mono.fromCallable(() -> {
            ConversationSession session = sessions.get(sessionId);
            if (session == null) {
                log.debug("Session not found: {}", sessionId);
                return null;
            }
            // Check if expired
            Instant expiry = session.lastActivityAt().plus(defaultTtl);
            if (Instant.now().isAfter(expiry)) {
                log.debug("Session expired: {}", sessionId);
                sessions.remove(sessionId);
                return null;
            }
            log.debug("Session found: {} ({} turns)", sessionId, session.turns().size());
            return session.updateActivity();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<ConversationSession> save(ConversationSession session) {
        return Mono.fromCallable(() -> {
            sessions.put(session.sessionId(), session);
            log.debug("Session saved: {} ({} turns, TTL={}min)",
                    session.sessionId(), session.turns().size(), defaultTtl.toMinutes());
            return session;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Boolean> delete(String sessionId) {
        return Mono.fromCallable(() -> {
            ConversationSession removed = sessions.remove(sessionId);
            if (removed != null) {
                log.info("Session deleted: {} (had {} turns)", sessionId, removed.turns().size());
                return true;
            }
            log.debug("Session not found for delete: {}", sessionId);
            return false;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Boolean> exists(String sessionId) {
        return Mono.fromCallable(() -> {
            ConversationSession session = sessions.get(sessionId);
            if (session == null) {
                return false;
            }
            // Check expiry
            Instant expiry = session.lastActivityAt().plus(defaultTtl);
            if (Instant.now().isAfter(expiry)) {
                sessions.remove(sessionId);
                return false;
            }
            return true;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Boolean> expire(String sessionId, Duration ttl) {
        // In-memory: expiry is handled by cleanup task
        // Just verify session exists
        return exists(sessionId);
    }

    /**
     * Get active session count (for metrics).
     */
    public Mono<Integer> getActiveSessionCount() {
        return Mono.fromCallable(() -> {
            cleanupExpiredSessions();
            return sessions.size();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void cleanupExpiredSessions() {
        Instant now = Instant.now();
        int removed = 0;
        for (var entry : sessions.entrySet()) {
            Instant expiry = entry.getValue().lastActivityAt().plus(defaultTtl);
            if (now.isAfter(expiry)) {
                sessions.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Cleaned up {} expired sessions ({} remaining)", removed, sessions.size());
        }
    }
}
