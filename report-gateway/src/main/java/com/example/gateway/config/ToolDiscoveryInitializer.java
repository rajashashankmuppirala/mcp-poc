package com.example.gateway.config;

import com.example.gateway.model.SkillDefinition;
import com.example.gateway.service.McpClientService;
import com.example.gateway.service.SkillRegistry;
import com.example.gateway.service.ToolCallValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Discovers tools and validates skill capabilities from all MCP servers at startup.
 * Tools are discovered via MCP protocol (tools/list).
 * Skills are loaded from markdown files and validated against GET /skills on each server.
 */
@Component
public class ToolDiscoveryInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ToolDiscoveryInitializer.class);

    private final McpClientService mcpClientService;
    private final ToolCallValidator validator;
    private final SkillRegistry skillRegistry;
    private final Map<String, String> serverUrls;

    public ToolDiscoveryInitializer(
            McpClientService mcpClientService,
            ToolCallValidator validator,
            SkillRegistry skillRegistry,
            @Value("${mcp.client.servers.reports.url:http://localhost:8081}") String reportsUrl,
            @Value("${mcp.client.servers.ops.url:http://localhost:8083}") String opsUrl) {
        this.mcpClientService = mcpClientService;
        this.validator = validator;
        this.skillRegistry = skillRegistry;
        this.serverUrls = Map.of("reports", reportsUrl, "ops", opsUrl);
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

        validateSkillsAgainstServers();
    }

    /**
     * Fetch skills from each MCP server via GET /skills and validate against local markdown.
     * Logs warnings if server capabilities don't match declared skills.
     * Dynamically adds new skills discovered on servers that aren't in local files.
     */
    private void validateSkillsAgainstServers() {
        WebClient webClient = WebClient.builder().build();

        for (Map.Entry<String, String> entry : serverUrls.entrySet()) {
            String serverId = entry.getKey();
            String baseUrl = entry.getValue();
            try {
                List<SkillDefinition> serverSkills = webClient.get()
                        .uri(baseUrl + "/skills")
                        .retrieve()
                        .bodyToFlux(SkillDefinition.class)
                        .collectList()
                        .block();

                if (serverSkills != null && !serverSkills.isEmpty()) {
                    log.info("Discovered {} skills from MCP server '{}': {}",
                            serverSkills.size(), serverId,
                            serverSkills.stream().map(SkillDefinition::name).toList());
                    skillRegistry.validateAgainstServer(serverId, serverSkills);
                } else {
                    log.info("MCP server '{}' returned no skills via GET /skills", serverId);
                }
            } catch (Exception e) {
                log.warn("Could not validate skills against MCP server '{}': {}. Continuing with local skills only.",
                        serverId, e.getMessage());
            }
        }

        log.info("Skill validation complete. {} skills loaded", skillRegistry.getAllSkills().size());
    }
}
