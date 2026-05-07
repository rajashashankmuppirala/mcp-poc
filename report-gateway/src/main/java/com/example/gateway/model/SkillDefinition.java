package com.example.gateway.model;

import java.util.List;

/**
 * A domain-specific skill that maps prompts to MCP servers and LLM system prompts.
 */
public record SkillDefinition(
        String name,
        String description,
        List<String> triggers,
        String mcpServer,
        String systemPrompt,
        List<String> allowedTools
) {}
