package com.example.gateway.service;

import com.example.gateway.model.ToolCall;
import com.example.gateway.model.ToolDefinition;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Provider-agnostic LLM integration.
 * Implementations handle auth, request formatting, and response parsing
 * for specific LLM providers while exposing a uniform tool-calling contract.
 */
public interface LlmProvider {

    /**
     * Convert a user prompt into a structured tool call.
     *
     * @param userMessage  the validated natural language prompt
     * @param tools        the list of available tool definitions
     * @param systemPrompt optional custom system prompt (from a skill); uses default if null
     * @return a Mono emitting a ToolCall containing the selected tool name and its arguments,
     *         or empty if the prompt does not match any available tool
     */
    Mono<ToolCall> generateToolCall(String userMessage, List<ToolDefinition> tools, String systemPrompt);

    /**
     * Generate free-form text (no tool calling) — used for chart spec generation, summaries, etc.
     *
     * @param userMessage  the prompt
     * @param systemPrompt optional custom system prompt
     * @return a Mono emitting the generated text, or empty if the provider cannot generate
     */
    Mono<String> generateText(String userMessage, String systemPrompt);

    /** Returns the provider identifier for logging (e.g., "azure-openai", "mock") */
    String providerName();
}
