package com.example.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Detects common prompt injection patterns in user input before it reaches the LLM.
 */
@Service
public class PromptInjectionDetector {

    private static final Logger log = LoggerFactory.getLogger(PromptInjectionDetector.class);
    private static final int MAX_PROMPT_LENGTH = 4000;

    private static final List<String> INJECTION_PATTERNS = List.of(
            // Direct instruction overrides
            "ignore previous",
            "ignore all previous",
            "disregard previous",
            "disregard all",
            "forget previous",
            "forget all previous",
            // System prompt impersonation
            "system prompt",
            "system instruction",
            "you are now",
            "you are a new",
            "new instructions",
            "new system",
            "follow these new",
            // Role change attempts
            "pretend you are",
            "act as if you are",
            "role: system",
            "role: developer",
            // Output manipulation
            "output only",
            "return only",
            "don't follow",
            "do not follow",
            "bypass",
            "override your",
            // Tool name injection
            "call tool",
            "invoke tool",
            "execute tool"
    );

    private static final Set<String> SUSPICIOUS_CHARS = Set.of(
            "<<", ">>", "```", "{%", "%}"
    );

    /**
     * Validates prompt against injection patterns.
     * @throws PromptInjectionException if injection pattern detected
     */
    public void check(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new PromptInjectionException("Prompt is empty");
        }
        if (prompt.length() > MAX_PROMPT_LENGTH) {
            throw new PromptInjectionException(
                    "Prompt exceeds max length of " + MAX_PROMPT_LENGTH + " characters");
        }

        String lower = prompt.toLowerCase();
        for (String pattern : INJECTION_PATTERNS) {
            if (lower.contains(pattern)) {
                log.warn("Prompt injection pattern detected: '{}'", pattern);
                throw new PromptInjectionException(
                        "Prompt contains blocked pattern: '" + pattern + "'");
            }
        }

        for (String chars : SUSPICIOUS_CHARS) {
            if (prompt.contains(chars)) {
                log.warn("Suspicious characters in prompt: '{}'", chars);
                throw new PromptInjectionException(
                        "Prompt contains suspicious characters: '" + chars + "'");
            }
        }
    }

    public static class PromptInjectionException extends RuntimeException {
        public PromptInjectionException(String message) {
            super(message);
        }
    }
}
