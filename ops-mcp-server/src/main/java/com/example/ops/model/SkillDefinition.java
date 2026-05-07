package com.example.ops.model;

import java.util.List;

/**
 * Skill metadata exposed by MCP servers via GET /skills.
 */
public record SkillDefinition(
        String name,
        String description,
        List<String> triggers,
        List<String> allowedTools
) {}
