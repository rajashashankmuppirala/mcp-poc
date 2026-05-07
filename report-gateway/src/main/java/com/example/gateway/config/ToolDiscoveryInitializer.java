package com.example.gateway.config;

import com.example.gateway.service.McpClientService;
import com.example.gateway.service.ToolCallValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Discovers tools from all MCP servers at startup and populates the validator allowlist.
 */
@Component
public class ToolDiscoveryInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ToolDiscoveryInitializer.class);

    private final McpClientService mcpClientService;
    private final ToolCallValidator validator;

    public ToolDiscoveryInitializer(McpClientService mcpClientService, ToolCallValidator validator) {
        this.mcpClientService = mcpClientService;
        this.validator = validator;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Starting tool discovery from all MCP servers...");
        mcpClientService.discoverAndCacheTools().block();

        var tools = mcpClientService.getDiscoveredTools();
        validator.updateAllowedTools(
                tools.stream().map(com.example.gateway.model.ToolDefinition::name)
                        .collect(java.util.stream.Collectors.toSet()));

        log.info("Tool discovery complete. Allowlist: {}",
                tools.stream().map(com.example.gateway.model.ToolDefinition::name).toList());
    }
}
