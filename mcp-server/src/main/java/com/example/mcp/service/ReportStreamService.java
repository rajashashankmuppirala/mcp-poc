package com.example.mcp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * Reactive service that calls Domain API and streams response.
 * Uses WebClient for non-blocking streaming.
 */
@Service
public class ReportStreamService {

    private static final Logger log = LoggerFactory.getLogger(ReportStreamService.class);

    private final WebClient webClient;

    public ReportStreamService(
            @Value("${domain-api.url:http://localhost:8082}") String domainApiUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(domainApiUrl)
                .build();
    }

    /**
     * Stream report from Domain API.
     * Returns Flux<String> for reactive streaming to MCP client.
     */
    public Flux<String> streamReport(Map<String, Object> request, String correlationId) {
        log.info("[{}] Streaming report: type={}", correlationId, request.get("reportType"));

        long startTime = System.currentTimeMillis();
        int[] chunkCount = {0};

        return webClient.post()
                .uri("/api/v1/reports/stream")
                .header("X-Correlation-ID", correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .map(chunk -> {
                    chunkCount[0]++;
                    long elapsed = System.currentTimeMillis() - startTime;
                    if (chunkCount[0] == 1) {
                        log.info("[{}] First chunk received in {}ms", correlationId, elapsed);
                    }
                    log.debug("[{}] Chunk #{} forwarded at {}ms", correlationId, chunkCount[0], elapsed);
                    return chunk + "\n";
                })
                .doOnComplete(() -> {
                    long totalMs = System.currentTimeMillis() - startTime;
                    log.info("[{}] Stream complete: {} chunks in {}ms", correlationId, chunkCount[0], totalMs);
                })
                .doOnError(e -> log.error("[{}] Stream failed: {}", correlationId, e.getMessage()))
                .onErrorResume(e -> Flux.just(
                        "{\"type\":\"error\",\"data\":{\"message\":\"Stream failed: "
                                + e.getMessage().replace("\"", "\\\"") + "\"}}\n"));
    }
}
