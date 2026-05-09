package com.example.gateway.config;

import com.example.gateway.service.SyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic sync — re-discovers tools from MCP servers and refreshes RAG data.
 * Runs every 5 minutes by default (configurable via sync.interval-minutes).
 */
@Component
@EnableScheduling
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final SyncService syncService;

    public SyncScheduler(SyncService syncService) {
        this.syncService = syncService;
    }

    /**
     * Periodic full sync — rediscover tools from all MCP servers, refresh RAG.
     * Non-blocking — errors are logged but don't affect the running application.
     */
    @Scheduled(fixedDelayString = "${sync.interval-minutes:5}m", initialDelayString = "${sync.initial-delay-minutes:10}m")
    public void scheduledSync() {
        log.info("Scheduled sync starting...");
        syncService.syncAll()
                .doOnSuccess(result -> {
                    if (result.success()) {
                        log.info("Scheduled sync complete — {} tools, {} RAG docs in {}ms",
                                result.toolCount(), result.ragDocCount(), result.durationMs());
                    } else {
                        log.warn("Scheduled sync completed with errors: {}", result.errors());
                    }
                })
                .subscribe();
    }
}
