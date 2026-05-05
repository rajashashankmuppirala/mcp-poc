package com.example.mcp.integration;

import com.example.mcp.model.GenerateReportRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class McpServerStreamingIntegrationTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> 18081);
        registry.add("domain-api.url", () -> "http://localhost:8082");
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldStreamReportFromDomainApi() throws Exception {
        // Skip if domain API is not running
        org.junit.jupiter.api.Assumptions.assumeTrue(isServiceRunning("http://localhost:8082"));

        // Arrange
        URL url = URI.create("http://localhost:18081/tools/generate-report").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-Correlation-ID", UUID.randomUUID().toString());

        String body = mapper.writeValueAsString(
                java.util.Map.of("reportType", "revenue", "limit", 50));
        conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().flush();

        int responseCode = conn.getResponseCode();
        assertEquals(200, responseCode);

        // Assert: Read streaming response
        int lineCount = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    lineCount++;
                    assertTrue(line.startsWith("row"), "Each line should start with 'row'");
                }
            }
        }

        assertTrue(lineCount >= 30, "Should receive at least 30 rows, got: " + lineCount);
    }

    @Test
    void shouldRejectInvalidRequest() throws Exception {
        URL url = URI.create("http://localhost:18081/tools/generate-report").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setReadTimeout(10_000);
        conn.setRequestProperty("Content-Type", "application/json");

        // Missing reportType
        String body = mapper.writeValueAsString(java.util.Map.of("limit", 50));
        conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().flush();

        int responseCode = conn.getResponseCode();
        assertEquals(400, responseCode, "Missing reportType should return 400");
    }

    private boolean isServiceRunning(String url) {
        try {
            HttpURLConnection testConn = (HttpURLConnection) new URI(url).toURL().openConnection();
            testConn.setConnectTimeout(2000);
            testConn.setRequestMethod("GET");
            int code = testConn.getResponseCode();
            return code == 200 || code == 404; // 404 means server is running but no handler
        } catch (Exception e) {
            return false;
        }
    }
}
