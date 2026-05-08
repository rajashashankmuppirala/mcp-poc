package com.example.gateway.service;

import com.example.gateway.config.SessionConfig;
import com.example.gateway.model.ConversationSession;
import com.example.gateway.model.ConversationTurn;
import com.example.gateway.model.ExtractedFilters;
import com.example.gateway.model.SessionContext;
import com.example.gateway.service.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Core service for conversation session management.
 * Coordinates between session storage and request handling.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final SessionStore sessionStore;
    private final SessionConfig sessionConfig;
    private final ContextInjector contextInjector;
    private final LlmProvider llmProvider;

    public ConversationService(SessionStore sessionStore, SessionConfig sessionConfig,
                               ContextInjector contextInjector, LlmProvider llmProvider) {
        this.sessionStore = sessionStore;
        this.sessionConfig = sessionConfig;
        this.contextInjector = contextInjector;
        this.llmProvider = llmProvider;
    }

    /**
     * Load or create a session context from a session ID.
     * If sessionId is null or invalid, creates a new session.
     *
     * @param sessionId the session ID from request header (may be null)
     * @return Mono containing SessionContext (existing or new)
     */
    public Mono<SessionContext> loadOrCreateSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            log.debug("No session ID provided, creating new session");
            return Mono.just(SessionContext.createNew());
        }

        // Validate UUID format
        if (!isValidUuid(sessionId)) {
            log.warn("Invalid session ID format: {}, creating new session", sessionId);
            return Mono.just(SessionContext.createNew());
        }

        return sessionStore.find(sessionId)
                .map(session -> {
                    log.info("Existing session loaded: {} ({} turns)",
                            sessionId, session.turns().size());
                    return SessionContext.fromExisting(sessionId, session);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("Session not found: {}, creating new session", sessionId);
                    return Mono.just(SessionContext.createNew());
                }))
                .onErrorResume(e -> {
                    log.error("Error loading session {}: {}, creating new session",
                            sessionId, e.getMessage());
                    return Mono.just(SessionContext.createNew());
                });
    }

    /**
     * Save a conversation turn to the session.
     *
     * @param context the current session context
     * @param turn the turn to add
     * @return Mono containing updated SessionContext
     */
    public Mono<SessionContext> saveTurn(SessionContext context, ConversationTurn turn) {
        // Determine the turn number based on session state
        int turnNumber;
        ConversationSession sessionToUpdate;

        if (!context.hasSession()) {
            // Create a new session - this is turn 1
            turnNumber = 1;
            sessionToUpdate = ConversationSession.createNew();
        } else {
            // Existing session - get next turn number
            turnNumber = context.session().getCurrentTurnNumber();
            sessionToUpdate = context.session();
        }

        // Build turn with proper turn number
        ConversationTurn turnWithNumber = ConversationTurn.builder()
                .turnNumber(turnNumber)
                .userPrompt(turn.userPrompt())
                .extractedFilters(turn.extractedFilters())
                .responseType(turn.responseType())
                .responseTitle(turn.responseTitle())
                .chartType(turn.chartType())
                .build();

        ConversationSession updated = sessionToUpdate.addTurn(turnWithNumber);

        // Check if summarization is needed
        if (updated.needsSummarization()) {
            log.info("Session {} reached {} turns, triggering summarization",
                    updated.sessionId(), ConversationSession.MAX_TURNS);
            return contextInjector.summarizeConversation(llmProvider, updated.turns())
                    .flatMap(summary -> {
                        ConversationSession summarized = updated.withSummary(summary);
                        return sessionStore.save(summarized)
                                .map(saved -> context.withUpdatedSession(saved));
                    })
                    .doOnSuccess(c -> log.info("Session {} summarized: {} turns cleared, summary saved",
                            c.session().sessionId(), updated.turns().size()));
        }

        return sessionStore.save(updated)
                .map(saved -> context.withUpdatedSession(saved))
                .doOnSuccess(c -> log.debug("Turn {} saved to session {}",
                        turnWithNumber.turnNumber(), c.session().sessionId()));
    }

    /**
     * Clear all conversation history for a session.
     *
     * @param sessionId the session ID to clear
     * @return Mono containing true if cleared, false if not found
     */
    public Mono<Boolean> clearSession(String sessionId) {
        return sessionStore.delete(sessionId)
                .doOnSuccess(result -> {
                    if (result) {
                        log.info("Session cleared: {}", sessionId);
                    }
                });
    }

    /**
     * Get the full session by ID.
     *
     * @param sessionId the session ID
     * @return Mono containing the session, or empty if not found
     */
    public Mono<ConversationSession> getSession(String sessionId) {
        return sessionStore.find(sessionId);
    }

    /**
     * Check if a session exists.
     *
     * @param sessionId the session ID
     * @return Mono containing true if exists, false otherwise
     */
    public Mono<Boolean> sessionExists(String sessionId) {
        return sessionStore.exists(sessionId);
    }

    private boolean isValidUuid(String sessionId) {
        try {
            UUID.fromString(sessionId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
