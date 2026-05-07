package com.example.gateway.service;

import com.example.gateway.model.ToolCall;
import com.example.gateway.model.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-MCP Client wrapper — manages connections to multiple MCP servers,
 * discovers tools from all of them, and auto-routes tool calls to the correct server.
 */
@Service
public class McpClientService {

    private static final Logger log = LoggerFactory.getLogger(McpClientService.class);

    private final Map<String, McpAsyncClient> mcpClients;
    private final ObjectMapper mapper = new ObjectMapper();

    /** tool name → server id mapping, built at startup */
    private final Map<String, String> toolServerMap = new ConcurrentHashMap<>();
    /** all discovered tools across all servers */
    private final List<ToolDefinition> allTools = new ArrayList<>();

    public McpClientService(Map<String, McpAsyncClient> mcpClients) {
        this.mcpClients = mcpClients;
        log.info("Initialized with {} MCP clients: {}", mcpClients.size(), mcpClients.keySet());
    }

    /**
     * Discover tools from ALL MCP servers and cache them.
     * Builds tool→server mapping for auto-routing.
     */
    public Mono<Void> discoverAndCacheTools() {
        List<Mono<Void>> discoveries = new ArrayList<>();

        for (Map.Entry<String, McpAsyncClient> entry : mcpClients.entrySet()) {
            String serverId = entry.getKey();
            McpAsyncClient client = entry.getValue();

            Mono<Void> discovery = client.listTools()
                    .map(McpSchema.ListToolsResult::tools)
                    .doOnNext(mcpTools -> {
                        for (var tool : mcpTools) {
                            toolServerMap.put(tool.name(), serverId);
                            allTools.add(new ToolDefinition(
                                    tool.name(),
                                    tool.description(),
                                    mapper.valueToTree(tool.inputSchema())));
                        }
                        log.info("Discovered {} tools from MCP server '{}': {}", mcpTools.size(), serverId,
                                mcpTools.stream().map(t -> t.name()).toList());
                    })
                    .then();
            discoveries.add(discovery);
        }

        return Mono.when(discoveries)
                .doOnSuccess(v -> log.info("Total tools discovered across all servers: {}", allTools.size()))
                .doOnError(e -> log.warn("Failed to discover tools from MCP server: {}", e.getMessage()));
    }

    /**
     * Discover tools from a specific server.
     */
    public Mono<List<ToolDefinition>> discoverToolsForServer(String serverId) {
        McpAsyncClient client = mcpClients.get(serverId);
        if (client == null) {
            return Mono.error(new IllegalArgumentException("Unknown MCP server: " + serverId));
        }
        return client.listTools()
                .map(McpSchema.ListToolsResult::tools)
                .map(mcpTools -> mcpTools.stream()
                        .peek(tool -> toolServerMap.put(tool.name(), serverId))
                        .map(tool -> new ToolDefinition(
                                tool.name(),
                                tool.description(),
                                mapper.valueToTree(tool.inputSchema())))
                        .toList())
                .doOnNext(tools -> log.info("Discovered {} tools from server '{}': {}",
                        tools.size(), serverId, tools.stream().map(ToolDefinition::name).toList()));
    }

    /**
     * Returns all discovered tools across all servers.
     */
    public List<ToolDefinition> getDiscoveredTools() {
        return List.copyOf(allTools);
    }

    /**
     * Returns tools available on a specific server.
     */
    public List<ToolDefinition> getToolsForServer(String serverId) {
        return allTools.stream()
                .filter(t -> serverId.equals(toolServerMap.get(t.name())))
                .toList();
    }

    /**
     * Execute a tool call — auto-routes to the correct MCP server based on tool name.
     */
    @SuppressWarnings("unchecked")
    public Flux<String> executeToolCall(ToolCall toolCall, String correlationId, String userToken) {
        String serverId = toolServerMap.get(toolCall.tool());
        if (serverId == null) {
            return Flux.error(new IllegalStateException("No MCP server owns tool: " + toolCall.tool()));
        }

        McpAsyncClient client = mcpClients.get(serverId);
        if (client == null) {
            return Flux.error(new IllegalStateException("Unknown MCP server: " + serverId));
        }

        log.info("[{}] Executing MCP tool: {} (server={}){}", correlationId, toolCall.tool(), serverId,
                userToken != null ? " (user token present)" : "");

        long startTime = System.currentTimeMillis();
        Map<String, Object> args = mapper.convertValue(toolCall.parameters(), Map.class);

        if (userToken != null && !userToken.isBlank()) {
            args = new java.util.HashMap<>(args);
            args.put("_userToken", userToken);
        }

        return client.callTool(new McpSchema.CallToolRequest(toolCall.tool(), args))
                .doOnNext(result -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("[{}] First MCP chunk from server '{}' in {}ms", correlationId, serverId, elapsed);
                })
                .flatMapMany(result -> {
                    if (result == null || result.content() == null || result.content().isEmpty()) {
                        return Flux.error(new IllegalStateException("MCP tool returned no content"));
                    }
                    return Flux.fromIterable(result.content())
                            .filter(content -> content instanceof McpSchema.TextContent)
                            .map(content -> ((McpSchema.TextContent) content).text())
                            .map(text -> text != null ? text : "");
                })
                .doOnComplete(() -> {
                    long totalMs = System.currentTimeMillis() - startTime;
                    log.info("[{}] MCP stream from server '{}' complete in {}ms", correlationId, serverId, totalMs);
                })
                .doOnError(e -> log.error("[{}] MCP tool call failed on server '{}': {}",
                        correlationId, serverId, e.getMessage()));
    }

    /**
     * Check if a specific MCP server is healthy (connected).
     */
    public Mono<Boolean> isServerHealthy(String serverId) {
        McpAsyncClient client = mcpClients.get(serverId);
        if (client == null) return Mono.just(false);
        return client.ping().thenReturn(true).onErrorReturn(false);
    }
}
