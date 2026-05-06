package com.example.gateway.config;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class McpClientConfig {

    @Value("${mcp.client.server-url:http://localhost:8081}")
    private String serverUrl;

    @Bean(destroyMethod = "closeGracefully")
    public McpAsyncClient mcpAsyncClient() {
        var transport = HttpClientSseClientTransport.builder(serverUrl).build();
        return McpClient.async(transport)
                .clientInfo(new McpSchema.Implementation("report-gateway-client", "1.0.0"))
                .requestTimeout(Duration.ofSeconds(60))
                .initializationTimeout(Duration.ofSeconds(30))
                .build();
    }
}
