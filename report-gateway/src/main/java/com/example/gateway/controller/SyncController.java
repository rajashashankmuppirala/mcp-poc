package com.example.gateway.controller;

import com.example.gateway.service.SyncService;
import com.example.gateway.service.SyncService.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Admin endpoints for syncing tools and RAG data without gateway restart.
 */
@RestController
@RequestMapping("/admin")
public class SyncController {

    private static final Logger log = LoggerFactory.getLogger(SyncController.class);

    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    /**
     * Full sync: rediscover all tools from all MCP servers, refresh RAG data.
     */
    @PostMapping(value = "/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> syncAll() {
        log.info("Admin: triggering full sync");
        return syncService.syncAll().map(result -> toResponse("full", result));
    }

    /**
     * Refresh a specific MCP server's tools.
     * Example: POST /admin/sync/reports
     */
    @PostMapping(value = "/sync/{serverId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> refreshServer(@PathVariable String serverId) {
        log.info("Admin: triggering sync for server '{}'", serverId);
        return syncService.refreshServer(serverId).map(result -> toResponse("server:" + serverId, result));
    }

    /**
     * Refresh report schemas from Domain API.
     */
    @PostMapping(value = "/sync/reports-metadata", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> refreshReportSchemas() {
        log.info("Admin: triggering report schema refresh");
        return syncService.refreshReportSchemas().map(result -> toResponse("reports-metadata", result));
    }

    /**
     * Get current sync status (cached tool count, RAG doc count).
     */
    @GetMapping(value = "/sync/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> status() {
        var tools = syncService.allTools();
        var serverCounts = syncService.getServerToolCounts();
        return Mono.just(Map.of(
                "toolCount", tools.size(),
                "ragDocCount", syncService.ragDocCount(),
                "toolsByServer", serverCounts
        ));
    }

    private Map<String, Object> toResponse(String scope, SyncResult result) {
        return Map.of(
                "scope", scope,
                "success", result.success(),
                "toolCount", result.toolCount(),
                "ragDocCount", result.ragDocCount(),
                "durationMs", result.durationMs(),
                "errors", result.errors()
        );
    }
}
