package com.example.gateway.integration;

import com.example.gateway.controller.AiController;
import com.example.gateway.model.ToolCall;
import com.example.gateway.service.LlmProvider;
import com.example.gateway.service.McpClientService;
import com.example.gateway.service.ToolCallValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamingIntegrationTest {

    private final LlmProvider llmProvider = mock(LlmProvider.class);
    private final ToolCallValidator validator = mock(ToolCallValidator.class);
    private final McpClientService mcpClient = mock(McpClientService.class);
    private final WebTestClient webTestClient = WebTestClient
            .bindToController(new AiController(llmProvider, validator, mcpClient))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldReturn200ForValidRequest() {
        ToolCall mockToolCall = new ToolCall("generate_report",
                mapper.createObjectNode()
                        .put("reportType", "revenue")
                        .put("region", "us-east"));
        when(llmProvider.generateToolCall(anyString(), any())).thenReturn(mockToolCall);
        when(llmProvider.providerName()).thenReturn("mock");

        when(mcpClient.executeToolCall(any(), anyString()))
                .thenReturn(Flux.just(
                        "{\"type\":\"data\",\"data\":{\"row\":1}}",
                        "{\"type\":\"data\",\"data\":{\"row\":2}}",
                        "{\"type\":\"footer\",\"data\":{\"totalRows\":2}}"
                ));

        webTestClient.post()
                .uri("/ai/request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"prompt\": \"Show me revenue\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> {
                    assert body.contains("row");
                    assert body.contains("footer");
                });
    }

    @Test
    void shouldRejectInvalidToolCall() {
        ToolCall invalidToolCall = new ToolCall("unknown_tool", mapper.createObjectNode());
        when(llmProvider.generateToolCall(anyString(), any())).thenReturn(invalidToolCall);
        when(llmProvider.providerName()).thenReturn("mock");
        doThrow(new IllegalArgumentException("Unknown tool: unknown_tool")).when(validator).validate(any());

        webTestClient.post()
                .uri("/ai/request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"prompt\": \"Show me data\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("INVALID_TOOL_CALL");
    }

    @Test
    void shouldHandleLlmError() {
        when(llmProvider.generateToolCall(anyString(), any()))
                .thenThrow(new IllegalStateException("LLM returned natural language"));

        webTestClient.post()
                .uri("/ai/request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"prompt\": \"Show me data\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("LLM_ERROR");
    }

    @Test
    void shouldVerifyToolCallProperties() {
        ToolCall mockToolCall = new ToolCall("generate_report",
                mapper.createObjectNode()
                        .put("reportType", "revenue")
                        .put("region", "us-east")
                        .put("startDate", "2026-01-01")
                        .put("endDate", "2026-03-31"));
        when(llmProvider.generateToolCall(anyString(), any())).thenReturn(mockToolCall);
        when(llmProvider.providerName()).thenReturn("mock");
        when(mcpClient.executeToolCall(any(), anyString())).thenReturn(Flux.just("test"));

        webTestClient.post()
                .uri("/ai/request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"prompt\": \"Show me revenue for us-east in Q1\"}")
                .exchange()
                .expectStatus().isOk();

        verify(mcpClient).executeToolCall(
                argThat(tc ->
                        tc != null &&
                        tc.tool().equals("generate_report") &&
                        tc.parameters().get("reportType").asText().equals("revenue") &&
                        tc.parameters().get("region").asText().equals("us-east")),
                anyString());
    }
}
