package com.example.mcp.model;

import java.util.List;

/**
 * Skill metadata exposed by MCP servers via GET /skills.
 * Describes a group of related tools with trigger keywords for prompt matching.
 */
public record SkillDefinition(
        String name,
        String description,
        List<String> triggers,
        List<String> allowedTools
) {}
