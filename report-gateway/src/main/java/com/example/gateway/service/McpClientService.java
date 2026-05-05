package com.example.gateway.service;

import com.example.gateway.model.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
public class McpClientService {

    private static final Logger log = LoggerFactory.getLogger(McpClientService.class);

    private final String mcpUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpClientService(@Value("${mcp-server.url:http://localhost:8081}") String mcpUrl) {
        this.mcpUrl = mcpUrl;
    }

    public StreamingResponseBody executeToolCall(ToolCall toolCall, String correlationId) {
        log.info("[{}] Executing tool: {}", correlationId, toolCall.tool());

        return outputStream -> {
            HttpURLConnection conn = null;
            long startTime = System.currentTimeMillis();
            int chunkCount = 0;
            try {
                URL url = URI.create(mcpUrl + "/tools/generate-report").toURL();
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setReadTimeout(60_000);
                conn.setConnectTimeout(10_000);
                conn.setRequestProperty(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                conn.setRequestProperty("X-Correlation-ID", correlationId);

                // Build request body from tool parameters
                ObjectNode request = mapper.createObjectNode();
                toolCall.parameters().fields().forEachRemaining(entry ->
                        request.set(entry.getKey(), entry.getValue()));

                try (OutputStream reqOut = conn.getOutputStream()) {
                    reqOut.write(mapper.writeValueAsBytes(request));
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
                            log.info("[{}] First chunk delivered in {}ms", correlationId, elapsed);
                        }
                        log.debug("[{}] Chunk #{} at {}ms: {}", correlationId, chunkCount, elapsed, line);
                    }
                }
            } catch (Exception e) {
                log.error("[{}] Stream failed: {}", correlationId, e.getMessage());
                String errorChunk = "{\"type\":\"error\",\"data\":{\"message\":\"Stream failed: " + e.getMessage().replace("\"", "\\\"") + "\"}}\n";
                outputStream.write(errorChunk.getBytes(StandardCharsets.UTF_8));
            } finally {
                long totalMs = System.currentTimeMillis() - startTime;
                log.info("[{}] Stream complete: {} chunks in {}ms", correlationId, chunkCount, totalMs);
                if (conn != null) conn.disconnect();
            }
        };
    }
}
