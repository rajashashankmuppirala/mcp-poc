package com.example.gateway.service;

import com.example.gateway.model.ToolCall;
import com.example.gateway.model.ToolDefinition;

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
     * @param userMessage the validated natural language prompt
     * @param tools       the list of available tool definitions
     * @return a ToolCall containing the selected tool name and its arguments,
     *         or null if the prompt does not match any available tool
     */
    ToolCall generateToolCall(String userMessage, List<ToolDefinition> tools);

    /** Returns the provider identifier for logging (e.g., "azure-openai", "mock") */
    String providerName();
}
