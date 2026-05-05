package com.example.gateway.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A tool available for LLM invocation.
 */
public record ToolDefinition(
        String name,
        String description,
        JsonNode parameters  // JSON Schema object
) {}
