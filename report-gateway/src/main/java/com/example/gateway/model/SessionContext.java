package com.example.gateway.model;

/**
 * Container for passing session information through the request pipeline.
 * Extracted from headers and loaded from session store.
 */
public record SessionContext(
        String sessionId,
        ConversationSession session,
        boolean isNewSession
) {
    public SessionContext {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId cannot be null or blank");
        }
    }

    public static SessionContext createNew() {
        ConversationSession newSession = ConversationSession.createNew();
        return new SessionContext(newSession.sessionId(), newSession, true);
    }

    public static SessionContext createNew(String userId) {
        ConversationSession newSession = ConversationSession.createNew(userId);
        return new SessionContext(newSession.sessionId(), newSession, true);
    }

    public static SessionContext fromExisting(String sessionId, ConversationSession session) {
        return new SessionContext(sessionId, session, false);
    }

    public boolean hasSession() {
        return session != null;
    }

    public SessionContext withSession(ConversationSession updatedSession) {
        return new SessionContext(sessionId, updatedSession, isNewSession);
    }

    public SessionContext withUpdatedSession(ConversationSession updatedSession) {
        return new SessionContext(sessionId, updatedSession, false);
    }
}
