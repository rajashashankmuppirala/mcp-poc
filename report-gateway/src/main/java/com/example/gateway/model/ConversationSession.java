package com.example.gateway.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A conversation session containing all turns in a single user session.
 * Stored in external session storage (Redis) for stateless gateway operation.
 */
public record ConversationSession(
        String sessionId,
        String userId,
        Instant createdAt,
        Instant lastActivityAt,
        List<ConversationTurn> turns,
        String summary,
        int turnCount
) {
    public static final int MAX_TURNS = 100;

    public ConversationSession {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId cannot be null or blank");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt cannot be null");
        }
        if (lastActivityAt == null) {
            throw new IllegalArgumentException("lastActivityAt cannot be null");
        }
        // Make turns mutable list for adding new turns
        turns = turns != null ? new ArrayList<>(turns) : new ArrayList<>();
        // Ensure summary is never null
        summary = summary != null ? summary : "";
    }

    public static ConversationSession createNew() {
        Instant now = Instant.now();
        return new ConversationSession(
                UUID.randomUUID().toString(),
                null,
                now,
                now,
                new ArrayList<>(),
                "",
                0
        );
    }

    public static ConversationSession createNew(String userId) {
        Instant now = Instant.now();
        return new ConversationSession(
                UUID.randomUUID().toString(),
                userId,
                now,
                now,
                new ArrayList<>(),
                "",
                0
        );
    }

    public ConversationSession withUserId(String userId) {
        return new ConversationSession(
                sessionId, userId, createdAt, lastActivityAt,
                turns, summary, turnCount
        );
    }

    public ConversationSession addTurn(ConversationTurn turn) {
        if (turn == null) {
            throw new IllegalArgumentException("turn cannot be null");
        }
        List<ConversationTurn> newTurns = new ArrayList<>(turns);
        newTurns.add(turn);
        return new ConversationSession(
                sessionId, userId, createdAt, Instant.now(),
                newTurns, summary, turnCount + 1
        );
    }

    public ConversationSession updateActivity() {
        return new ConversationSession(
                sessionId, userId, createdAt, Instant.now(),
                turns, summary, turnCount
        );
    }

    public ConversationSession withSummary(String newSummary) {
        return new ConversationSession(
                sessionId, userId, createdAt, Instant.now(),
                new ArrayList<>(), newSummary, turnCount
        );
    }

    public boolean needsSummarization() {
        return turns.size() >= MAX_TURNS;
    }

    public List<ConversationTurn> getRecentTurns(int count) {
        if (turns.isEmpty() || count <= 0) {
            return Collections.emptyList();
        }
        int start = Math.max(0, turns.size() - count);
        return List.copyOf(turns.subList(start, turns.size()));
    }

    public ConversationTurn getLastTurn() {
        return turns.isEmpty() ? null : turns.get(turns.size() - 1);
    }

    public int getCurrentTurnNumber() {
        return turns.size() + 1;
    }

    public boolean isEmpty() {
        return turns.isEmpty();
    }
}
