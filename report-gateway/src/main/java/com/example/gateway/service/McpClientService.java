package com.example.gateway.service;

import com.example.gateway.model.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * MCP Client wrapper that invokes tools on the MCP Server via SSE transport.
 * Uses Spring AI MCP Client auto-configured by spring-ai-starter-mcp-client-webflux.
 */
@Service
public class McpClientService {

    private static final Logger log = LoggerFactory.getLogger(McpClientService.class);

    private final McpAsyncClient mcpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpClientService(McpAsyncClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    /**
     * Execute a tool call via MCP protocol (tools/call).
     * Returns a Flux of text lines for streaming.
     */
    @SuppressWarnings("unchecked")
    public Flux<String> executeToolCall(ToolCall toolCall, String correlationId) {
        log.info("[{}] Executing MCP tool: {}", correlationId, toolCall.tool());

        long startTime = System.currentTimeMillis();
        Map<String, Object> args = mapper.convertValue(toolCall.parameters(), Map.class);

        return mcpClient.callTool(new McpSchema.CallToolRequest(toolCall.tool(), args))
                .doOnNext(result -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("[{}] First MCP chunk in {}ms", correlationId, elapsed);
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
                    log.info("[{}] MCP stream complete in {}ms", correlationId, totalMs);
                })
                .doOnError(e -> log.error("[{}] MCP tool call failed: {}", correlationId, e.getMessage()));
    }

    /**
     * List available tools from the MCP server.
     */
    public Mono<List<McpSchema.Tool>> listTools() {
        return mcpClient.listTools()
                .map(McpSchema.ListToolsResult::tools);
    }
}
