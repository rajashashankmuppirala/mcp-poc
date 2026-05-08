package com.example.gateway.model;

import java.util.List;

/**
 * Interactive clarification returned when the LLM/UI needs user input
 * to disambiguate a request (e.g., chart type, report type, date range).
 */
public record ClarificationResponse(
        String type,
        String question,
        List<Option> options,
        String context,
        String originalPrompt
) {
    public record Option(String label, String value, String description) {
        public Option(String label, String value) {
            this(label, value, null);
        }
    }
}
