package com.example.gateway.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * A single exchange (turn) in a conversation session.
 * Stores the user prompt, extracted filters, and response metadata.
 * No actual data values are stored - only metadata.
 */
public record ConversationTurn(
        int turnNumber,
        Instant timestamp,
        String userPrompt,
        ExtractedFilters extractedFilters,
        String responseType,
        String responseTitle,
        String chartType
) {
    public ConversationTurn {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("userPrompt cannot be null or blank");
        }
        // Note: turnNumber validation happens in ConversationService.saveTurn()
        // to allow ContextInjector to create turns before assignment
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int turnNumber;
        private Instant timestamp = Instant.now();
        private String userPrompt;
        private ExtractedFilters extractedFilters;
        private String responseType;
        private String responseTitle;
        private String chartType;

        public Builder turnNumber(int turnNumber) {
            this.turnNumber = turnNumber;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder userPrompt(String userPrompt) {
            this.userPrompt = userPrompt;
            return this;
        }

        public Builder extractedFilters(ExtractedFilters extractedFilters) {
            this.extractedFilters = extractedFilters;
            return this;
        }

        public Builder responseType(String responseType) {
            this.responseType = responseType;
            return this;
        }

        public Builder responseTitle(String responseTitle) {
            this.responseTitle = responseTitle;
            return this;
        }

        public Builder chartType(String chartType) {
            this.chartType = chartType;
            return this;
        }

        public ConversationTurn build() {
            return new ConversationTurn(turnNumber, timestamp, userPrompt,
                    extractedFilters, responseType, responseTitle, chartType);
        }
    }
}
