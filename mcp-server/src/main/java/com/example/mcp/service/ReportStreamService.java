package com.example.mcp.service;

import com.example.mcp.model.GenerateReportRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

@Service
public class ReportStreamService {

    private static final Logger log = LoggerFactory.getLogger(ReportStreamService.class);

    private final String domainApiUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReportStreamService(@Value("${domain-api.url:http://localhost:8082}") String domainApiUrl) {
        this.domainApiUrl = domainApiUrl;
    }

    public StreamingResponseBody streamReport(GenerateReportRequest request, String correlationId) {
        log.info("[{}] Streaming report: type={}, limit={}", correlationId, request.reportType(), request.effectiveLimit());

        return outputStream -> {
            HttpURLConnection conn = null;
            long startTime = System.currentTimeMillis();
            int chunkCount = 0;
            try {
                URL url = URI.create(domainApiUrl + "/api/v1/reports/stream").toURL();
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setReadTimeout(60_000);
                conn.setConnectTimeout(10_000);
                conn.setRequestProperty(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                conn.setRequestProperty("X-Correlation-ID", correlationId);

                // Write request body
                try (OutputStream reqOut = conn.getOutputStream()) {
                    byte[] payload = mapper.writeValueAsBytes(request);
                    reqOut.write(payload);
                    reqOut.flush();
                }

                // Stream response line-by-line — no buffering
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        outputStream.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                        chunkCount++;
                        long elapsed = System.currentTimeMillis() - startTime;
                        if (chunkCount == 1) {
                            log.info("[{}] First chunk received in {}ms", correlationId, elapsed);
                        }
                        log.debug("[{}] Chunk #{} forwarded at {}ms", correlationId, chunkCount, elapsed);
                    }
                }
            } catch (Exception e) {
                log.error("[{}] Stream failed: {}", correlationId, e.getMessage());
                String errorChunk = "{\"type\":\"error\",\"data\":{\"message\":\"Stream failed: " + e.getMessage().replace("\"", "\\\"") + "\"}}\n";
                outputStream.write(errorChunk.getBytes(StandardCharsets.UTF_8));
            } finally {
                long totalMs = System.currentTimeMillis() - startTime;
                log.info("[{}] Stream complete: {} chunks in {}ms", correlationId, chunkCount, totalMs);
                if (conn != null) {
                    conn.disconnect();
                }
            }
        };
    }
}
