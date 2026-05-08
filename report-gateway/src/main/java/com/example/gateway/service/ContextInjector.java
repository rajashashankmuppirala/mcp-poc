package com.example.gateway.service;

import com.example.gateway.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Interface for building prompts with conversation context.
 * Handles context injection, filter extraction, and summarization.
 */
public interface ContextInjector {

    /**
     * Builds a prompt with conversation context prepended.
     * Uses sliding window of recent turns (default: last 8 turns).
     *
     * @param currentPrompt the current user message
     * @param session the conversation session (may be null)
     * @return full prompt with history context
     */
    String buildContextPrompt(String currentPrompt, ConversationSession session);

    /**
     * Extracts structured filters from a user prompt and tool call.
     * Called after LLM generates tool call to capture extracted parameters.
     *
     * @param userPrompt the original user message
     * @param toolCall the LLM-generated tool call
     * @return structured filters (never null, may be empty)
     */
    ExtractedFilters extractFilters(String userPrompt, ToolCall toolCall);

    /**
     * Creates a new conversation turn from the current interaction.
     *
     * @param userPrompt the user message
     * @param toolCall the generated tool call
     * @param responseMetadata metadata about the response
     * @return new conversation turn
     */
    ConversationTurn createTurn(String userPrompt, ToolCall toolCall,
                                   ResponseMetadata responseMetadata);

    /**
     * Summarizes conversation when it exceeds max turns.
     * Uses the provided LLM provider to generate a condensed summary.
     *
     * @param llmProvider the LLM to use for summarization
     * @param turns full conversation turns (100+)
     * @return condensed summary string
     */
    Mono<String> summarizeConversation(LlmProvider llmProvider, List<ConversationTurn> turns);

    /**
     * Metadata about a system response.
     */
    record ResponseMetadata(
            String type,        // "chart", "stream", "clarification", "error"
            String title,       // e.g., "Revenue 2026"
            String chartType,   // "bar", "pie", "line", "area", "scatter"
            String errorMessage // if type is "error"
    ) {}
}
