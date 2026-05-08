package com.example.gateway.controller;

import com.example.gateway.model.ConversationSession;
import com.example.gateway.model.ConversationTurn;
import com.example.gateway.service.ConversationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for conversation session management.
 * Provides endpoints for viewing history, clearing sessions, and checking status.
 */
@RestController
@RequestMapping("/session")
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    private final ConversationService conversationService;

    private static final String SESSION_ID_HEADER = "X-Session-ID";
    private static final String SESSION_COOKIE_NAME = "session-id";

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /**
     * Get conversation history for a session.
     * Returns the session metadata and all turns.
     */
    @GetMapping(value = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> getHistory(
            @RequestHeader(value = SESSION_ID_HEADER, required = false) String sessionIdHeader,
            @CookieValue(value = SESSION_COOKIE_NAME, required = false) String sessionIdCookie) {

        String sessionId = sessionIdHeader != null ? sessionIdHeader : sessionIdCookie;

        if (sessionId == null || sessionId.isBlank()) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("error", "NO_SESSION", "message", "No session ID provided")));
        }

        return conversationService.getSession(sessionId)
                .map(session -> {
                    List<Map<String, Object>> turns = session.turns().stream()
                            .map(this::turnToMap)
                            .toList();

                    Map<String, Object> response = Map.of(
                            "sessionId", session.sessionId(),
                            "userId", session.userId() != null ? session.userId() : "anonymous",
                            "createdAt", session.createdAt().toString(),
                            "lastActivityAt", session.lastActivityAt().toString(),
                            "turnCount", session.turnCount(),
                            "needsSummarization", session.needsSummarization(),
                            "turns", turns
                    );
                    return ResponseEntity.ok(response);
                })
                .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "SESSION_NOT_FOUND", "message", "Session not found or expired"))))
                .doOnSuccess(resp -> log.info("Retrieved history for session: {} (status={})",
                        sessionId, resp.getStatusCode()));
    }

    /**
     * Clear all conversation history for a session.
     */
    @PostMapping(value = "/clear", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> clearSession(
            @RequestHeader(value = SESSION_ID_HEADER, required = false) String sessionIdHeader,
            @CookieValue(value = SESSION_COOKIE_NAME, required = false) String sessionIdCookie,
            ServerWebExchange exchange) {

        String sessionId = sessionIdHeader != null ? sessionIdHeader : sessionIdCookie;

        if (sessionId == null || sessionId.isBlank()) {
            // No session to clear - return success since goal is achieved
            Map<String, Object> response = Map.of(
                    "cleared", true,
                    "message", "No active session to clear"
            );
            return Mono.just(ResponseEntity.ok(response));
        }

        return conversationService.clearSession(sessionId)
                .map(cleared -> {
                    if (cleared) {
                        // Clear the session cookie
                        exchange.getResponse().addCookie(
                                org.springframework.http.ResponseCookie.from(SESSION_COOKIE_NAME, "")
                                        .maxAge(0)
                                        .path("/")
                                        .build()
                        );
                        Map<String, Object> response = Map.of(
                                "cleared", true,
                                "sessionId", sessionId,
                                "message", "Conversation history cleared"
                        );
                        return ResponseEntity.ok(response);
                    } else {
                        Map<String, Object> response = Map.of(
                                "cleared", false,
                                "message", "Session not found or already expired"
                        );
                        return ResponseEntity.ok(response);
                    }
                })
                .doOnSuccess(resp -> log.info("Cleared session: {} (result={})",
                        sessionId, resp.getBody() != null ? resp.getBody().get("cleared") : "unknown"));
    }

    /**
     * Get session status and metadata.
     */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> getStatus(
            @RequestHeader(value = SESSION_ID_HEADER, required = false) String sessionIdHeader,
            @CookieValue(value = SESSION_COOKIE_NAME, required = false) String sessionIdCookie) {

        String sessionId = sessionIdHeader != null ? sessionIdHeader : sessionIdCookie;

        if (sessionId == null || sessionId.isBlank()) {
            // Return status for a new session that would be created
            Map<String, Object> response = Map.of(
                    "active", false,
                    "sessionId", UUID.randomUUID().toString(),
                    "turnCount", 0,
                    "message", "No active session - a new one will be created on next request"
            );
            return Mono.just(ResponseEntity.ok(response));
        }

        return conversationService.sessionExists(sessionId)
                .flatMap(exists -> {
                    if (!exists) {
                        Map<String, Object> response = Map.of(
                                "active", false,
                                "sessionId", sessionId,
                                "turnCount", 0,
                                "message", "Session expired or not found"
                        );
                        return Mono.just(ResponseEntity.ok(response));
                    }
                    return conversationService.getSession(sessionId)
                            .map(session -> {
                                Map<String, Object> response = Map.of(
                                        "active", true,
                                        "sessionId", session.sessionId(),
                                        "userId", session.userId() != null ? session.userId() : "anonymous",
                                        "createdAt", session.createdAt().toString(),
                                        "lastActivityAt", session.lastActivityAt().toString(),
                                        "turnCount", session.turnCount(),
                                        "needsSummarization", session.needsSummarization()
                                );
                                return ResponseEntity.ok(response);
                            });
                })
                .doOnSuccess(resp -> log.info("Retrieved status for session: {}", sessionId));
    }

    /**
     * Convert a ConversationTurn to a Map for JSON serialization.
     * Excludes any potentially sensitive data.
     */
    private Map<String, Object> turnToMap(ConversationTurn turn) {
        return Map.of(
                "turnNumber", turn.turnNumber(),
                "timestamp", turn.timestamp().toString(),
                "userPrompt", turn.userPrompt(),
                "extractedFilters", turn.extractedFilters() != null ?
                        Map.of(
                                "reportType", turn.extractedFilters().reportType() != null ? turn.extractedFilters().reportType() : "",
                                "region", turn.extractedFilters().region() != null ? turn.extractedFilters().region() : "",
                                "hasDateRange", turn.extractedFilters().hasDateRange()
                        ) : Map.of(),
                "responseType", turn.responseType() != null ? turn.responseType() : "",
                "responseTitle", turn.responseTitle() != null ? turn.responseTitle() : "",
                "chartType", turn.chartType() != null ? turn.chartType() : ""
        );
    }
}
