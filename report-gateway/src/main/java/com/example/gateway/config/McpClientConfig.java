package com.example.gateway.config;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

@Configuration
public class McpClientConfig {

    @Value("${mcp.client.request-timeout:60s}")
    private String requestTimeout;

    @Value("${mcp.client.initialization-timeout:30s}")
    private String initializationTimeout;

    @Bean(destroyMethod = "closeGracefully")
    public McpAsyncClient reportsMcpClient(
            @Value("${mcp.client.servers.reports.url:http://localhost:8081}") String serverUrl) {
        return createClient("reports", serverUrl);
    }

    @Bean(destroyMethod = "closeGracefully")
    public McpAsyncClient opsMcpClient(
            @Value("${mcp.client.servers.ops.url:http://localhost:8083}") String serverUrl) {
        return createClient("ops", serverUrl);
    }

    private McpAsyncClient createClient(String name, String serverUrl) {
        var transport = HttpClientStreamableHttpTransport.builder(serverUrl).build();
        return McpClient.async(transport)
                .clientInfo(new McpSchema.Implementation("report-gateway-client-" + name, "1.0.0"))
                .requestTimeout(Duration.parse("PT" + requestTimeout.toUpperCase().replace("S", "S")))
                .initializationTimeout(Duration.parse("PT" + initializationTimeout.toUpperCase().replace("S", "S")))
                .build();
    }

    /**
     * Expose all MCP clients as a map for dynamic routing.
     */
    @Bean
    public Map<String, McpAsyncClient> mcpClientMap(McpAsyncClient reportsMcpClient,
                                                    McpAsyncClient opsMcpClient) {
        return Map.of(
                "reports", reportsMcpClient,
                "ops", opsMcpClient
        );
    }
}
