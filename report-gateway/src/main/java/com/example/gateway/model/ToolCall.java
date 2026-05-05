package com.example.gateway.model;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolCall(
        String tool,
        JsonNode parameters
) {}
