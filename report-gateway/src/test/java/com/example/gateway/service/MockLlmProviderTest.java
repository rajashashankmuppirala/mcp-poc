package com.example.gateway.service;

import com.example.gateway.model.ToolCall;
import com.example.gateway.model.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MockLlmProviderTest {

    private final MockLlmProvider provider = new MockLlmProvider();
    private final List<ToolDefinition> reportTools = List.of(
            new ToolDefinition("generate_report", "Generate a report", new ObjectMapper().createObjectNode()));
    private final List<ToolDefinition> opsTools = List.of(
            new ToolDefinition("list_failed_jobs", "List failed jobs", new ObjectMapper().createObjectNode()),
            new ToolDefinition("list_successful_dataflows", "List successful dataflows", new ObjectMapper().createObjectNode()));
    private final List<ToolDefinition> allTools = List.copyOf(
            new java.util.LinkedHashSet<>(java.util.stream.Stream.concat(reportTools.stream(), opsTools.stream()).toList()));

    private ToolCall callGenerateToolCall(String prompt, List<ToolDefinition> tools, String systemPrompt) {
        return provider.generateToolCall(prompt, tools, systemPrompt).block(Duration.ofSeconds(5));
    }

    private String callGenerateText(String prompt) {
        return provider.generateText(prompt, null).block(Duration.ofSeconds(5));
    }

    @Test
    void shouldReturnGenerateReportTool() {
        ToolCall result = callGenerateToolCall("Show me revenue", allTools, null);

        assertEquals("generate_report", result.tool());
    }

    @Test
    void shouldExtractRegionFromPrompt() {
        ToolCall result = callGenerateToolCall("Show me sales for us-east", allTools, null);

        assertEquals("us-east", result.parameters().get("region").asText());
    }

    @Test
    void shouldExtractEuWestRegion() {
        ToolCall result = callGenerateToolCall("Revenue report for eu-west", allTools, null);

        assertEquals("eu-west", result.parameters().get("region").asText());
    }

    @Test
    void shouldDefaultReportTypeToRevenue() {
        ToolCall result = callGenerateToolCall("Show me data", allTools, null);

        assertEquals("revenue", result.parameters().get("reportType").asText());
    }

    @Test
    void shouldReturnMockProviderName() {
        assertEquals("mock", provider.providerName());
    }

    @Test
    void shouldReturnEmptyForUnmatchedPrompt() {
        Optional<ToolCall> result = provider.generateToolCall("What is the weather today?", allTools, null)
                .blockOptional(Duration.ofSeconds(5));

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnFailedJobsForOpsPrompt() {
        ToolCall result = callGenerateToolCall("Show me failed jobs in the last 24 hours", opsTools, null);

        assertNotNull(result);
        assertEquals("list_failed_jobs", result.tool());
        assertEquals(24, result.parameters().get("hours").asInt());
    }

    @Test
    void shouldReturnDataflowsForOpsPrompt() {
        ToolCall result = callGenerateToolCall("What is the dataflow status", opsTools, null);

        assertNotNull(result);
        assertEquals("list_successful_dataflows", result.tool());
    }
}
