package com.example.gateway.service;

import com.example.gateway.model.ConversationSession;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Interface for session storage operations.
 * Implementations may use Redis, in-memory, or database storage.
 */
public interface SessionStore {

    /**
     * Find a session by ID.
     *
     * @param sessionId the session identifier
     * @return Mono containing the session, or empty if not found
     */
    Mono<ConversationSession> find(String sessionId);

    /**
     * Save a session to storage.
     *
     * @param session the session to save
     * @return Mono containing the saved session
     */
    Mono<ConversationSession> save(ConversationSession session);

    /**
     * Delete a session from storage.
     *
     * @param sessionId the session identifier
     * @return Mono containing true if deleted, false if not found
     */
    Mono<Boolean> delete(String sessionId);

    /**
     * Check if a session exists.
     *
     * @param sessionId the session identifier
     * @return Mono containing true if exists, false otherwise
     */
    Mono<Boolean> exists(String sessionId);

    /**
     * Update the TTL/expiration for a session.
     *
     * @param sessionId the session identifier
     * @param ttl time to live
     * @return Mono containing true if updated, false if not found
     */
    Mono<Boolean> expire(String sessionId, Duration ttl);
}
