package com.example.gateway.service;

import com.example.gateway.model.ToolCall;
import com.example.gateway.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MockLlmProviderTest {

    private final MockLlmProvider provider = new MockLlmProvider();
    private final List<ToolDefinition> emptyTools = List.of();

    @Test
    void shouldReturnGenerateReportTool() {
        ToolCall result = provider.generateToolCall("Show me revenue", emptyTools);

        assertEquals("generate_report", result.tool());
    }

    @Test
    void shouldExtractRegionFromPrompt() {
        ToolCall result = provider.generateToolCall("Show me sales for us-east", emptyTools);

        assertEquals("us-east", result.parameters().get("region").asText());
    }

    @Test
    void shouldExtractEuWestRegion() {
        ToolCall result = provider.generateToolCall("Revenue report for eu-west", emptyTools);

        assertEquals("eu-west", result.parameters().get("region").asText());
    }

    @Test
    void shouldDefaultReportTypeToRevenue() {
        ToolCall result = provider.generateToolCall("Show me data", emptyTools);

        assertEquals("revenue", result.parameters().get("reportType").asText());
    }

    @Test
    void shouldReturnMockProviderName() {
        assertEquals("mock", provider.providerName());
    }
}
