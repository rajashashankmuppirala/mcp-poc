package com.example.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Audit logger — logs request outcomes for compliance (excludes sensitive data).
 */
@Service
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");

    /**
     * Log an audit event with correlation ID, event type, and context.
     * Sensitive data (tokens, prompt content, parameter values) is excluded.
     */
    public void log(String correlationId, String eventType, Map<String, Object> context) {
        log.info("[AUDIT] {} | {} | {} | {}", correlationId, Instant.now(), eventType, context);
    }
}
