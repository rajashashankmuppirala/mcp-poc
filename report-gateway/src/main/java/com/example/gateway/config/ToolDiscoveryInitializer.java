package com.example.gateway.config;

import com.example.gateway.service.McpClientService;
import com.example.gateway.service.ToolCallValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Discovers tools from the MCP server at startup and registers them
 * with the validator and LLM provider pipeline.
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
        log.info("Discovering tools from MCP server...");
        mcpClientService.discoverAndCacheTools()
                .subscribe(tools -> {
                    // Update validator allowlist
                    validator.updateAllowedTools(
                            tools.stream().map(t -> t.name()).collect(java.util.stream.Collectors.toSet()));
                });
    }
}
