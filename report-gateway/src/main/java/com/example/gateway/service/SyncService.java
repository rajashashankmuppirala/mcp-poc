package com.example.gateway.service;

import com.example.gateway.model.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates synchronization of tools and RAG data across all MCP servers.
 * Supports full sync (all servers), per-server refresh, and report schema refresh.
 * All operations are non-blocking and tolerate individual server failures.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final McpClientService mcpClientService;
    private final RagAutoIngestionService ragAutoIngestion;
    private final ToolCallValidator validator;
    private final Map<String, String> serverIds; // ordered map

    public SyncService(
            McpClientService mcpClientService,
            RagAutoIngestionService ragAutoIngestion,
            ToolCallValidator validator) {
        this.mcpClientService = mcpClientService;
        this.ragAutoIngestion = ragAutoIngestion;
        this.validator = validator;
        // Server IDs in sync order — reports first (primary), then ops
        this.serverIds = new LinkedHashMap<>();
        serverIds.put("reports", null); // URL not needed here, McpClientService handles connections
        serverIds.put("ops", null);
    }

    /**
     * Full sync: rediscover all tools from all servers, update allowlist, re-ingest into RAG.
     */
    public Mono<SyncResult> syncAll() {
        log.info("Starting full sync across all MCP servers...");
        long start = System.currentTimeMillis();

        return mcpClientService.discoverAndCacheTools()
                .then(Mono.fromSupplier(() -> {
                    var tools = mcpClientService.getDiscoveredTools();
                    validator.updateAllowedTools(
                            tools.stream().map(ToolDefinition::name).collect(java.util.stream.Collectors.toSet()));
                    log.info("Updated tool allowlist: {}", tools.stream().map(ToolDefinition::name).toList());

                    // Re-ingest all into RAG with deterministic IDs
                    ragAutoIngestion.ingestAll(tools);

                    long elapsed = System.currentTimeMillis() - start;
                    log.info("Full sync complete in {}ms — {} tools, {} RAG docs",
                            elapsed, tools.size(), ragAutoIngestion.ragDocCount());
                    return new SyncResult(true, tools.size(), ragAutoIngestion.ragDocCount(), elapsed, List.of());
                }))
                .onErrorResume(e -> {
                    log.warn("Full sync failed: {}", e.getMessage());
                    long elapsed = System.currentTimeMillis() - start;
                    return Mono.just(new SyncResult(false, 0, ragAutoIngestion.ragDocCount(), elapsed,
                            List.of(e.getMessage())));
                });
    }

    /**
     * Refresh a single server: rediscover its tools, update allowlist, re-ingest tool schemas into RAG.
     */
    public Mono<SyncResult> refreshServer(String serverId) {
        log.info("Starting refresh for MCP server: {}", serverId);
        long start = System.currentTimeMillis();

        return mcpClientService.refreshServerTools(serverId)
                .doOnSuccess(tools -> {
                    // Update allowlist with all tools (not just this server's)
                    var allTools = mcpClientService.getDiscoveredTools();
                    validator.updateAllowedTools(
                            allTools.stream().map(ToolDefinition::name).collect(java.util.stream.Collectors.toSet()));

                    // Re-ingest tool schemas into RAG
                    ragAutoIngestion.refreshToolSchemas(allTools);
                })
                .map(tools -> {
                    long elapsed = System.currentTimeMillis() - start;
                    log.info("Server '{}' refresh complete in {}ms — {} tools from server", serverId, elapsed, tools.size());
                    return new SyncResult(true, tools.size(), ragAutoIngestion.ragDocCount(), elapsed, List.of());
                })
                .onErrorResume(e -> {
                    log.warn("Refresh failed for server '{}': {}", serverId, e.getMessage());
                    long elapsed = System.currentTimeMillis() - start;
                    return Mono.just(new SyncResult(false, 0, ragAutoIngestion.ragDocCount(), elapsed,
                            List.of(serverId + ": " + e.getMessage())));
                });
    }

    /**
     * Refresh report schemas from Domain API only (no tool rediscovery).
     */
    public Mono<SyncResult> refreshReportSchemas() {
        log.info("Starting report schema refresh from Domain API...");
        long start = System.currentTimeMillis();

        return Mono.fromRunnable(() -> ragAutoIngestion.refreshReportSchemas())
                .then(Mono.fromSupplier(() -> {
                    long elapsed = System.currentTimeMillis() - start;
                    log.info("Report schema refresh complete in {}ms — {} RAG docs", elapsed, ragAutoIngestion.ragDocCount());
                    return new SyncResult(true, mcpClientService.getDiscoveredTools().size(),
                            ragAutoIngestion.ragDocCount(), elapsed, List.of());
                }))
                .onErrorResume(e -> {
                    log.warn("Report schema refresh failed: {}", e.getMessage());
                    long elapsed = System.currentTimeMillis() - start;
                    return Mono.just(new SyncResult(false, mcpClientService.getDiscoveredTools().size(),
                            ragAutoIngestion.ragDocCount(), elapsed, List.of(e.getMessage())));
                });
    }

    /**
     * Get current state of synced tools and servers.
     */
    public Map<String, Long> getServerToolCounts() {
        var tools = mcpClientService.getDiscoveredTools();
        return tools.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        t -> serverIdForTool(t),
                        java.util.stream.Collectors.counting()
                ));
    }

    /**
     * Returns all currently discovered tools.
     */
    public List<ToolDefinition> allTools() {
        return mcpClientService.getDiscoveredTools();
    }

    /**
     * Returns current RAG document count.
     */
    public int ragDocCount() {
        return ragAutoIngestion.ragDocCount();
    }

    /**
     * Returns a copy of the tool→server mapping for admin visibility.
     */
    private String serverIdForTool(ToolDefinition t) {
        // The actual mapping is internal to McpClientService; this is best-effort display.
        // We use a heuristic: tools owned by the first server in our ordered map are "reports", rest "ops".
        for (String serverId : serverIds.keySet()) {
            var serverTools = mcpClientService.getToolsForServer(serverId);
            if (serverTools.contains(t)) return serverId;
        }
        return "unknown";
    }

    /**
     * Result of a sync operation.
     */
    public record SyncResult(
            boolean success,
            int toolCount,
            int ragDocCount,
            long durationMs,
            List<String> errors
    ) {}
}
