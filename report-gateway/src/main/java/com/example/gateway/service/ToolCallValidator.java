package com.example.gateway.service;

import com.example.gateway.model.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class ToolCallValidator {

    private static final Logger log = LoggerFactory.getLogger(ToolCallValidator.class);

    // Dynamically populated from MCP server tool discovery
    private final Set<String> allowedTools = ConcurrentHashMap.newKeySet();

    private static final Map<String, Pattern> PARAM_PATTERNS = Map.of(
            "reportType", Pattern.compile("^[a-zA-Z_]+$"),
            "startDate", Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$"),
            "endDate", Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$"),
            "region", Pattern.compile("^[a-z]+-[a-z]+$")
    );

    /**
     * Update the allowed tool allowlist from discovered MCP tools.
     */
    public void updateAllowedTools(Set<String> toolNames) {
        allowedTools.clear();
        allowedTools.addAll(toolNames);
        log.info("ToolCallValidator allowlist updated to: {}", allowedTools);
    }

    public void validate(ToolCall toolCall) {
        if (toolCall.tool() == null || toolCall.tool().isBlank()) {
            throw new IllegalArgumentException("Tool name is empty");
        }
        if (allowedTools.isEmpty()) {
            throw new IllegalStateException("No tools registered in allowlist");
        }
        if (!allowedTools.contains(toolCall.tool())) {
            throw new IllegalArgumentException("Unknown tool: " + toolCall.tool()
                    + ". Available: " + String.join(", ", allowedTools));
        }
        if (toolCall.parameters() == null) {
            throw new IllegalArgumentException("Tool parameters are null");
        }

        JsonNode params = toolCall.parameters();
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, Pattern> entry : PARAM_PATTERNS.entrySet()) {
            JsonNode value = params.get(entry.getKey());
            if (value != null && !value.isNull()) {
                String str = value.asText();
                if (!entry.getValue().matcher(str).matches()) {
                    errors.add("$.%s: does not match the required pattern".formatted(entry.getKey()));
                }
            }
        }

        if (!errors.isEmpty()) {
            String msg = String.join("; ", errors);
            log.warn("Tool call validation failed: {}", msg);
            throw new IllegalArgumentException("Invalid tool parameters: " + msg);
        }
    }
}
